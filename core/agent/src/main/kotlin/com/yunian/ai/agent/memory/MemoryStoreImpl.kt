package com.yunian.ai.agent.memory

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.uniffi.MemoryStore
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.MessageDao
import com.yunian.ai.database.dao.UnifiedMemoryDao
import com.yunian.ai.database.model.MemoryRecord
import com.yunian.ai.database.model.MemoryScope
import com.yunian.ai.database.model.MemorySource
import com.yunian.ai.database.model.MemoryType
import com.yunian.ai.database.repository.EmbeddingProvider
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust `MemoryStore` 回调实现（Agent Memory 落地层，纯 IO 无决策）。
 *
 * - 索引与内容：Room `unified_memories` 表（UnifiedMemoryDao），content 存 DB 列（零迁移）
 * - 活动时间：最后一条消息时间来自 messages / archived_messages 表
 * - 整理时间：SharedPreferences 按 companion 维度持久化（零表结构改动）
 * - 语义向量：`EmbeddingProvider`（ServiceRegistry 注入，可能为 null → 语义分跳过）
 * - 决策（召回/评分/排序/整理）全在 Rust `MemorySelector`，本类不承载任何决策
 *
 * Rust MemoryMeta 期望字段（snake_case，与 serde 默认一致）：
 * id, content, memory_type, scope, source_id, importance, confidence,
 * access_count, observed_at, last_accessed_at, expires_at, tags, embedding
 */
class MemoryStoreImpl(context: Context) : MemoryStore {

    private val appContext: Context = context.applicationContext
    private val memoryDao: UnifiedMemoryDao = AppDatabase.getDatabase(appContext).unifiedMemoryDao()
    private val messageDao: MessageDao = AppDatabase.getDatabase(appContext).messageDao()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val deviceId: String = DeviceIdProvider.getDeviceId(appContext)

    /**
     * listMemories 结果缓存（P2-7）：scope_json 键 → (写入时间, JSON)。
     * 每回合召回都全量读 Room 并序列化（含 embedding 向量）跨 JNI，短 TTL 缓存可显著
     * 降低开销；insert/update/delete 后整体失效保证一致性。
     */
    private class CacheEntry(val at: Long, val json: String)
    private val listCache = ConcurrentHashMap<String, CacheEntry>()
    private val LIST_CACHE_TTL_MS = 30_000L

    /** EmbeddingProvider 可能未注册（老配置），取不到则返回 null，Rust 侧自动跳过语义分 */
    private val embeddingProvider: EmbeddingProvider? =
        runCatching { ServiceRegistry.get(EmbeddingProvider::class.java) }.getOrNull()

    /** Rust MemoryMeta 期望的字段名（snake_case JSON） */
    private fun toMetaJson(row: MemoryRecord): JSONObject = JSONObject().apply {
        put("id", row.id.toString())
        put("content", row.content)
        put("memory_type", row.memoryType.name)
        put("scope", row.scope.name)
        put("source_id", row.sourceId)
        put("importance", row.importance)
        put("confidence", row.confidence)
        put("access_count", row.accessCount)
        put("observed_at", row.observedAt)
        put("last_accessed_at", row.lastAccessedAt)
        put("expires_at", row.expiresAt ?: JSONObject.NULL)
        put("tags", row.tags)
        row.embedding?.let { bytes ->
            embeddingProvider?.bytesToFloats(bytes)?.toList()?.let { emb ->
                put("embedding", JSONArray(emb))
            }
        }
    }

    private fun metaJsonArray(rows: List<MemoryRecord>): String =
        JSONArray().apply { rows.forEach { put(toMetaJson(it)) } }.toString()

    /**
     * 拉取作用域内记忆（排除软删除 + 已过期，含 embedding）。
     * scope_json：`{"scope":"COMPANION","source_id":123}` 或 `{"scope":null}`（全部）。
     */
    override fun listMemories(scopeJson: String): String = runBlockingOnIo {
        val now = System.currentTimeMillis()
        listCache[scopeJson]?.let { cached ->
            if (now - cached.at < LIST_CACHE_TTL_MS) return@runBlockingOnIo cached.json
        }
        val s = runCatching { JSONObject(scopeJson) }.getOrNull() ?: JSONObject()
        val scopeName = s.optString("scope").takeIf { it.isNotBlank() && it != "null" }
        val scope = scopeName?.let { runCatching { MemoryScope.valueOf(it) }.getOrNull() }
        val sourceId = if (s.has("source_id") && !s.isNull("source_id")) s.optLong("source_id") else 0L

        val rows = if (scope == null) {
            memoryDao.getAllActiveSync(deviceId)
        } else {
            memoryDao.getByScopeSync(deviceId, scope, sourceId)
        }
        val json = metaJsonArray(rows)
        listCache[scopeJson] = CacheEntry(now, json)
        json
    }

    /** 保存记忆：id 为空串时生成新记录；否则按主键 REPLACE 更新。返回新 id 或更新后 id。 */
    override fun insertMemory(metaJson: String): String {
        return try {
            val m = JSONObject(metaJson)
            val id = m.optString("id").toLongOrNull()
            val now = System.currentTimeMillis()
            val memoryType = parseType(m.optString("memory_type"))
            val scope = parseScope(m.optString("scope"))
            val sourceId = m.optLong("source_id")
            val embeddingBytes = m.optJSONArray("embedding")?.let { arr ->
                val floats = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                embeddingProvider?.floatsToBytes(floats)
            }

            // getEmbeddingModelName() 为 suspend，需在 runBlockingOnIo 内调用
            val embeddingModelName = if (embeddingBytes != null) {
                runBlockingOnIo { embeddingProvider?.getEmbeddingModelName() ?: "" }
            } else ""

            val record = MemoryRecord(
                id = id ?: 0L,
                memoryType = memoryType,
                scope = scope,
                source = if (scope == MemoryScope.GROUP) MemorySource.GROUP_CHAT else MemorySource.CHAT,
                content = m.optString("content").ifBlank { return "" },
                confidence = m.optDouble("confidence", 1.0).toFloat(),
                importance = m.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f),
                sourceId = sourceId,
                createdAt = if (id != null) m.optLong("created_at", now) else now,
                updatedAt = now,
                lastAccessedAt = m.optLong("last_accessed_at", now),
                observedAt = m.optLong("observed_at", now),
                expiresAt = if (m.isNull("expires_at")) null else m.optLong("expires_at"),
                accessCount = m.optInt("access_count", 1).coerceAtLeast(1),
                tags = m.optString("tags"),
                deviceId = deviceId,
                embedding = embeddingBytes,
                embeddingModel = embeddingModelName,
            )
            runBlockingOnIo { memoryDao.insert(record) }.toString().also { listCache.clear() }
        } catch (e: Exception) {
            Log.e(TAG, "insertMemory failed", e)
            ""
        }
    }

    /** 按 metaJson 整体更新（Rust 当前整理流程暂不调用，保留契约完整性） */
    override fun updateMemory(metaJson: String): Boolean {
        return try {
            val m = JSONObject(metaJson)
            val id = m.optString("id").toLongOrNull() ?: return false
            val existing = runBlockingOnIo { memoryDao.getByIdSync(id) } ?: return false
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                content = m.optString("content", existing.content),
                memoryType = parseType(m.optString("memory_type", existing.memoryType.name)),
                scope = parseScope(m.optString("scope", existing.scope.name)),
                importance = m.optDouble("importance", existing.importance.toDouble()).toFloat(),
                confidence = m.optDouble("confidence", existing.confidence.toDouble()).toFloat(),
                sourceId = m.optLong("source_id", existing.sourceId),
                lastAccessedAt = m.optLong("last_accessed_at", now),
                observedAt = m.optLong("observed_at", existing.observedAt),
                expiresAt = if (m.isNull("expires_at")) null else m.optLong("expires_at", existing.expiresAt ?: 0L),
                tags = m.optString("tags", existing.tags),
                accessCount = m.optInt("access_count", existing.accessCount).coerceAtLeast(1),
                updatedAt = now,
            )
            (runBlockingOnIo { memoryDao.insert(updated) } > 0).also { listCache.clear() }
        } catch (e: Exception) {
            Log.e(TAG, "updateMemory failed", e)
            false
        }
    }

    /** 软删除 */
    override fun deleteMemory(id: String): Boolean {
        val longId = id.toLongOrNull() ?: return false
        return (runBlockingOnIo { memoryDao.softDelete(longId) > 0 }).also { listCache.clear() }
    }

    /** 读记忆正文（Rust 校验/复核用） */
    override fun getMemoryContent(id: String): String? {
        val longId = id.toLongOrNull() ?: return null
        return runBlockingOnIo { memoryDao.getByIdSync(longId) }?.content
    }

    /**
     * 活动时间戳：`{"last_activity_at":ms,"last_consolidated_at":ms}`（0 = 无记录）。
     * - last_activity_at：该 companion 最后一条消息时间（messages + archived_messages，含 AI 与用户消息）
     * - last_consolidated_at：上次记忆整理时间（SharedPreferences 按 companion 维度）
     */
    override fun getActivityTimestamps(companionId: Long?): String = runBlockingOnIo {
        val lastActivity = companionId?.let { cid ->
            val recent = messageDao.getRecentMessageMetadataSync(cid, "chat", 1).firstOrNull()
            val archived = messageDao.getLastArchivedMessageMetadata(cid, "chat")
            (recent ?: archived)?.timestamp ?: 0L
        } ?: 0L
        val lastConsolidated = prefs.getLong(keyFor(companionId), 0L)
        JSONObject().apply {
            put("last_activity_at", lastActivity)
            put("last_consolidated_at", lastConsolidated)
        }.toString()
    }

    /** 记录整理时间（按 companion 维度） */
    override fun setLastConsolidatedAt(now: Long, companionId: Long?): Boolean {
        return try {
            prefs.edit().putLong(keyFor(companionId), now).commit()
        } catch (e: Exception) {
            Log.e(TAG, "setLastConsolidatedAt failed", e)
            false
        }
    }

    /** 生成文本嵌入（失败/不支持返回 null，Rust 侧跳过语义分） */
    override fun embedText(text: String): String? {
        val provider = embeddingProvider ?: return null
        return try {
            val floats = runBlockingOnIo { provider.embed(text) } ?: return null
            JSONArray(floats.toList()).toString()
        } catch (e: Exception) {
            Log.w(TAG, "embedText failed", e)
            null
        }
    }

    private fun parseType(raw: String): MemoryType =
        runCatching { MemoryType.valueOf(raw.trim().uppercase()) }.getOrDefault(MemoryType.SEMANTIC)

    private fun parseScope(raw: String): MemoryScope =
        runCatching { MemoryScope.valueOf(raw.trim().uppercase()) }.getOrDefault(MemoryScope.COMPANION)

    private fun keyFor(companionId: Long?): String =
        if (companionId == null) "consolidated_at_global" else "consolidated_at_$companionId"

    /** 回调线程（Rust 侧）非主线程，IO 用 Dispatchers.IO 兜底 */
    private inline fun <T> runBlockingOnIo(crossinline block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "MemoryStoreImpl"
        const val PREFS_NAME = "agent_memory_prefs"
    }
}
