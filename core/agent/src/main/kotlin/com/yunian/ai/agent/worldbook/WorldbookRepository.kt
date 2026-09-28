package com.yunian.ai.agent.worldbook

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.WorldbookDao
import com.yunian.ai.database.model.WorldbookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 世界书仓库（③：持久化 + 激活管理 + 注入 AgentRuntime）。
 *
 * 世界书为社区 SillyTavern World Info 格式 JSON；激活者经 AgentFacade.setWorldbook
 * 注入 Rust 引擎（回合组装自动扫描注入，keys/正则/constant/scan_depth/预算语义）。
 */
class WorldbookRepository(private val context: Context) {

    private val dao: WorldbookDao =
        AppDatabase.getDatabase(context.applicationContext).worldbookDao()

    suspend fun list(): List<WorldbookEntity> = withContext(Dispatchers.IO) { dao.all() }
    suspend fun active(): WorldbookEntity? = withContext(Dispatchers.IO) { dao.active() }

    /** 伴侣级激活世界书（无则回退全局；伴侣 id <= 0 时仅全局）。 */
    suspend fun activeFor(companionId: Long): WorldbookEntity? =
        withContext(Dispatchers.IO) {
            if (companionId > 0L) dao.activeForCompanion(companionId) ?: dao.active() else dao.active()
        }

    /** 保存（新增或更新）；[companionId] 非空 = 伴侣级。成功后同步注入。 */
    suspend fun upsert(name: String, json: String, enabled: Boolean = false, companionId: Long? = null): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = dao.upsert(
                WorldbookEntity(name = name.takeIf { it.isNotBlank() } ?: "未命名世界书", json = json, enabled = enabled, companionId = companionId, updatedAt = now),
            )
            if (enabled) {
                dao.clearEnabled()
                dao.upsert(WorldbookEntity(id = id, name = name, json = json, enabled = true, companionId = companionId, updatedAt = now))
            }
            syncActiveToRuntime(companionId ?: 0L)
            id
        }

    /** 启用/停用；启用时先清除其他启用项（同作用域单激活：伴侣级只清伴侣，全局清全局）。 */
    suspend fun setEnabled(id: Long, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val book = dao.byId(id) ?: return@withContext false
        if (enabled) {
            dao.clearEnabled()
            dao.upsert(book.copy(enabled = true, updatedAt = System.currentTimeMillis()))
        } else {
            dao.upsert(book.copy(enabled = false, updatedAt = System.currentTimeMillis()))
        }
        syncActiveToRuntime(book.companionId ?: 0L)
        true
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val book = dao.byId(id)
        dao.delete(id)
        if (book?.enabled == true) syncActiveToRuntime()
        true
    }

    // ────────────────────────── Q4：伴侣级世界书实时合并 ──────────────────────────

    private val companionDao = AppDatabase.getDatabase(context.applicationContext).companionDao()

    /**
     * 读取伴侣绑定的全局世界书 id 集合（`companions.lorebookIdsJson`）；
     * 解析失败或未绑定 → 空列表（语义与本地 `feature:worldbook` 完全一致）。
     */
    suspend fun parseBoundIds(companionId: Long): List<Long> {
        val raw = runCatching { companionDao.getCompanionById(companionId)?.lorebookIdsJson }
            .getOrNull() ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optLong(i, 0L)
                    if (v > 0L) add(v)
                }
            }.distinct()
        }.getOrElse { emptyList() }
    }

    /**
     * 合成某伴侣当前应生效的世界书 JSON（Q4：**Kotlin 侧每回合实时合并，零 Rust 改动**）。
     *
     * 生效集合（§5.3b）：
     * 1. **未绑定**（`boundIds` 为空）→ 专属书 ∪ 全部全局书
     * 2. **已绑定** → 专属书 ∪ `boundIds` 命中的全局书
     * 3. 仅取 `enabled = 1` 且 `json` 非空的行
     *
     * 合成前做两项归一（§5.3c）：
     * - **按 `content` 去重**（保留 `priority` 高者，并列时保留靠后的启用项）
     * - **`insertion_order` 全局重编号**：多本书的 `insertion_order` 各自独立，
     *   直接拼接会产生大量冲突 → 按去重后的 `priority` 降序统一分配 1..N
     *
     * @return 单本 ST World Info JSON；无生效世界书时返回 `""`
     */
    suspend fun synthForCompanion(companionId: Long): String = withContext(Dispatchers.IO) {
        val rows = effectiveRows(companionId)
        if (rows.isEmpty()) return@withContext ""

        // 汇总全部条目（保留来源书本名以便回溯）
        data class Item(
            val entry: com.yunian.ai.database.model.LorebookEntryEntity,
            val bookName: String,
            val bookId: Long,
        )

        val items = ArrayList<Item>()
        for (row in rows) {
            val decoded = WorldbookJsonCodec.entries(row.json)
            for (st in decoded) {
                if (!st.enabled) continue
                val e = st.toEntity(lorebookId = 0L)
                if (e.content.isBlank()) continue
                items += Item(e, row.name, row.id)
            }
        }
        if (items.isEmpty()) return@withContext ""

        // ① 按 content 去重：保留 priority 高者；并列时保留后出现者（更晚的书覆盖早的）
        val best = LinkedHashMap<String, Item>()
        for (item in items) {
            val key = item.entry.content
            val prev = best[key]
            if (prev == null || item.entry.priority >= prev.entry.priority) best[key] = item
        }
        val deduped = best.values.toList()

        // ② insertion_order 全局重编号：priority 降序 → 序号 1..N
        val ordered = deduped.sortedWith(
            compareByDescending<Item> { it.entry.priority }.thenBy { it.entry.id },
        )
        val objs = ordered.mapIndexed { idx, item ->
            WorldbookJsonCodec.entryToJson(
                entry = item.entry,
                bookId = item.bookId,
                bookName = item.bookName,
                insertionOrder = (idx + 1).toLong(),
            )
        }

        // ③ 运行时断言（§5.3d）：合成 JSON 条目数 == 去重后实际生效条目数
        val core = rows.firstOrNull()
        val merged = WorldbookJsonCodec.assemble(
            name = core?.name ?: "世界书",
            description = null,
            scanDepth = WorldbookJsonCodec.dominantScanDepth(ordered.map { it.entry }),
            tokenBudget = WorldbookJsonCodec.DEFAULT_TOKEN_BUDGET,
            entryObjects = objs,
        )
        val actual = WorldbookJsonCodec.entryCount(merged)
        if (actual != objs.size) {
            Log.w(TAG, "世界书合成条目数异常: 期望 ${objs.size} 实际 $actual（伴侣 $companionId）")
        }
        merged
    }

    /** 取某伴侣当前生效的 `worldbooks` 行集合。 */
    private suspend fun effectiveRows(companionId: Long): List<WorldbookEntity> {
        val all = dao.all().filter { it.enabled && it.json.isNotBlank() && it.json != "{}" }
        if (all.isEmpty()) return emptyList()
        val boundIds = if (companionId > 0L) parseBoundIds(companionId) else emptyList()
        return if (boundIds.isEmpty()) {
            // 未绑定：专属书 ∪ 全部全局书
            all.filter { companionId <= 0L || it.companionId == null || it.companionId == companionId }
        } else {
            // 已绑定：专属书 ∪ boundIds 命中的全局书
            all.filter { it.companionId == companionId || (it.companionId == null && it.id in boundIds) }
        }
    }

    /**
     * 把**当前伴侣**应生效的世界书合并后注入 AgentRuntime。
     *
     * ★ 必须**每回合**调用（`ChatGenerationManager`），因为绑定的世界书集合
     * 与启用状态都可能在会话中变化，而 `AgentRuntime.setWorldbook` 是覆盖式的。
     *
     * `companionId <= 0` 时退化为「全局激活书」单本语义（启动/全局变更场景）。
     */
    suspend fun syncActiveToRuntime(companionId: Long = 0L) {
        val payload = if (companionId > 0L) {
            synthForCompanion(companionId)
        } else {
            dao.active()?.json ?: ""
        }
        runCatching {
            AgentFacade.setWorldbook(context, payload)
        }.onFailure { Log.w(TAG, "sync worldbook failed", it) }
    }

    private companion object {
        private const val TAG = "WorldbookRepository"
    }
}