package com.yunian.ai.agent.skill

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.uniffi.SkillStore
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.AgentSkillDao
import com.yunian.ai.database.model.AgentSkillEntity
import com.yunian.ai.database.model.SkillCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Rust `SkillStore` 回调实现（混合存储落地层，纯 IO 无决策）。
 *
 * - 索引：Room `agent_skills` 表（AgentSkillDao）
 * - 内容：文件系统 `filesDir/agent_skills/<skillId>/content.md`（SkillFileStore）
 * - 一致性：写文件 → 写索引；读索引 → 读文件 → 校验 SHA-256
 * - 决策（选择/排序/拼 prompt）全在 Rust `SkillSelector`，本类不承载任何决策
 */
class SkillStoreImpl(context: Context) : SkillStore {

    private val appContext: Context = context.applicationContext
    private val dao: AgentSkillDao = AppDatabase.getDatabase(appContext).agentSkillDao()
    private val fileStore: SkillFileStore =
        SkillFileStore(File(appContext.filesDir, "agent_skills"))

    /**
     * 索引/正文缓存（P2-7）：listSkills 按 companion 键、getSkillContent 按 skillId 键，
     * 短 TTL；save/delete 后整体失效保证一致性（技能变更低频，整体失效足够）。
     */
    private class CacheEntry(val at: Long, val value: String?)

    /**
     * ⚠️ 键类型必须是**非空** Long：`ConcurrentHashMap` 是 Java 实现，运行时对 null 键
     * 直接抛 `NullPointerException`（`get`/`putVal` 处），Kotlin 写成 `Long?` 只是类型系统假象。
     * `listSkills(null)`（全量查询）用 [CACHE_KEY_ALL] 这个不可能出现的 companionId 作哨兵键。
     */
    private val listCache = ConcurrentHashMap<Long, CacheEntry>()
    private val contentCache = ConcurrentHashMap<String, CacheEntry>()
    private val CACHE_TTL_MS = 30_000L

    /** Rust SkillMeta 期望的字段名（snake_case，与 serde 默认一致） */
    private fun toMetaJson(row: AgentSkillEntity): JSONObject = JSONObject().apply {
        put("skill_id", row.skillId)
        put("name", row.name)
        put("description", row.description)
        put("category", row.category.name)
        put("tags", row.tags)
        put("tools", row.tools.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        put("enabled", row.enabled)
        put("companion_id", row.companionId ?: JSONObject.NULL)
        put("version", row.version)
        put("updated_at", row.updatedAt)
    }

    private fun metaJsonArray(rows: List<AgentSkillEntity>): String =
        JSONArray().apply { rows.forEach { put(toMetaJson(it)) } }.toString()

    /** 索引 JSON 数组（含禁用项，由 Rust 侧过滤） */
    override fun listSkills(companionId: Long?): String = runBlockingOnIo {
        val now = System.currentTimeMillis()
        val cacheKey = companionId ?: CACHE_KEY_ALL // null 不能作 ConcurrentHashMap 键
        listCache[cacheKey]?.let { cached ->
            if (now - cached.at < CACHE_TTL_MS) return@runBlockingOnIo cached.value.orEmpty()
        }
        val json = metaJsonArray(dao.listSkillsAll(companionId))
        listCache[cacheKey] = CacheEntry(now, json)
        Log.i(TAG, "[debug] listSkills($companionId) -> ${json.take(300)}")
        json
    }

    /** 读正文：索引 → 文件 → SHA-256 校验；不存在/禁用/校验失败返回 null */
    override fun getSkillContent(skillId: String): String? {
        val now = System.currentTimeMillis()
        contentCache[skillId]?.let { cached ->
            if (now - cached.at < CACHE_TTL_MS) return cached.value
        }
        val row = runBlockingOnIo { dao.getBySkillId(skillId) }
        if (row == null) {
            Log.i(TAG, "[debug] getSkillContent($skillId) -> row null")
            return null
        }
        if (!row.enabled) {
            Log.i(TAG, "[debug] getSkillContent($skillId) -> disabled")
            return null
        }
        val content = fileStore.readContent(skillId)
        if (content == null) {
            Log.i(TAG, "[debug] getSkillContent($skillId) -> file null")
            return null
        }
        val actual = fileStore.sha256(content)
        if (actual != row.contentHash) {
            Log.w(TAG, "content hash mismatch for skill $skillId: expected=${row.contentHash.take(16)} actual=$actual")
            return null
        }
        Log.i(TAG, "[debug] getSkillContent($skillId) -> ok ${content.length} chars")
        // ② SKILL.md frontmatter：返回给模型的正文剥离元数据头（社区规范）
        val body = SkillContentParser.parse(content).body
        contentCache[skillId] = CacheEntry(now, body)
        return body
    }

    /** 保存：解析 meta → 写文件（content.md + meta.json）→ 写/更新索引，返回新 version；失败 -1 */
    override fun saveSkill(metaJson: String, content: String): Int {
        return try {
            val meta = JSONObject(metaJson)
            val skillId = meta.optString("skill_id").takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            // ② SKILL.md frontmatter：content 自带元数据头时覆盖（社区技能导入零手工）
            val parsed = SkillContentParser.parse(content)
            val name = parsed.name?.takeIf { it.isNotBlank() }
                ?: meta.optString("name").takeIf { it.isNotBlank() }
                ?: skillId
            val description = parsed.description?.takeIf { it.isNotBlank() }
                ?: meta.optString("description")
            val category = parseCategory(meta.optString("category"))
            val tags = meta.optString("tags")
            val tools = meta.optJSONArray("tools")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.joinToString(",")
            } ?: ""
            val enabled = meta.optBoolean("enabled", true)
            val companionId = if (meta.isNull("companion_id")) null
            else if (meta.has("companion_id")) meta.optLong("companion_id") else null

            // 1. 先写文件（原子），再写索引
            if (!fileStore.writeContent(skillId, content)) return -1
            listCache.clear()
            contentCache.clear()
            fileStore.writeMeta(skillId, meta.toString())

            val now = System.currentTimeMillis()
            val hash = fileStore.sha256(content)
            val length = content.toByteArray(Charsets.UTF_8).size.toLong()

            val existing = runBlockingOnIo { dao.getBySkillId(skillId) }
            runBlockingOnIo {
                if (existing != null) {
                    dao.update(
                        existing.copy(
                            name = name,
                            description = description,
                            category = category,
                            tags = tags,
                            tools = tools,
                            enabled = enabled,
                            companionId = companionId,
                            contentPath = "agent_skills/$skillId/content.md",
                            contentHash = hash,
                            contentLength = length,
                            version = existing.version + 1,
                            updatedAt = now,
                        )
                    )
                    existing.version + 1
                } else {
                    dao.insert(
                        AgentSkillEntity(
                            skillId = skillId,
                            name = name,
                            description = description,
                            category = category,
                            tags = tags,
                            tools = tools,
                            enabled = enabled,
                            companionId = companionId,
                            contentPath = "agent_skills/$skillId/content.md",
                            contentHash = hash,
                            contentLength = length,
                            version = 1,
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                    1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveSkill failed", e)
            -1
        }
    }

    /** 删除：先删索引行，再删目录 */
    override fun deleteSkill(skillId: String): Boolean {
        val deleted = runBlockingOnIo { dao.deleteBySkillId(skillId) }
        fileStore.deleteDir(skillId)
        listCache.clear()
        contentCache.clear()
        return deleted > 0
    }

    /** 关键字搜索索引（仅启用项） */
    override fun searchSkills(query: String, limit: UInt): String = runBlockingOnIo {
        metaJsonArray(dao.searchSkills(null, query, limit.toInt().coerceAtLeast(1)))
    }

    /** 启动巡检：扫描 FS 目录 vs Room 索引，返回不一致列表（dir 存在但无索引行） */
    suspend fun reconcile(): List<String> {
        val dirs = withContext(Dispatchers.IO) { fileStore.listSkillDirs() }
        val rows = dao.getAllSync()
        val indexed = rows.map { it.skillId }.toSet()
        return dirs.filterNot { it in indexed }
    }

    private fun parseCategory(raw: String): SkillCategory =
        runCatching { SkillCategory.valueOf(raw.trim().uppercase()) }.getOrDefault(SkillCategory.CUSTOM)

    /** 回调线程（Rust 侧）非主线程，IO 用 Dispatchers.IO 兜底 */
    private inline fun <T> runBlockingOnIo(crossinline block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "SkillStoreImpl"

        /** listCache 哨兵键：代表 `companionId == null`（全量查询）。用 Long.MIN_VALUE 确保与真实 id 不冲突。 */
        const val CACHE_KEY_ALL = Long.MIN_VALUE
    }
}
