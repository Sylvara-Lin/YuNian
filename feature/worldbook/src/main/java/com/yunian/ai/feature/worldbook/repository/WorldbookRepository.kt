package com.yunian.ai.feature.worldbook.repository

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.worldbook.WorldbookJsonCodec
import com.yunian.ai.agent.worldbook.WorldbookRepository as AgentWorldbookRepository
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.WorldbookDao
import com.yunian.ai.database.model.LorebookEntryEntity
import com.yunian.ai.database.model.WorldbookEntity
import com.yunian.ai.domain.EntryRole
import com.yunian.ai.domain.InjectionPosition
import com.yunian.ai.domain.Lorebook
import com.yunian.ai.domain.LorebookEntry
import com.yunian.ai.domain.LorebookProvider
import com.yunian.ai.domain.LorebookWithEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 世界书仓储 —— **结构化编辑器适配层**（阶段 5g，plan doc §5.7）。
 *
 * ## 定位
 * 本类是 `LorebookProvider`（供 `WorldbookScreens.kt` 使用的纯 CRUD 契约）在
 * **master `worldbooks` 表**之上的实现。触发/注入职责已于阶段 5f 全部移交 Rust
 * Cordis Agent 引擎（`core:agent/worldbook/WorldbookRepository.synthForCompanion`
 * 每回合合成 ST World Info JSON → `AgentFacade.setWorldbook`）。
 *
 * ## 数据源
 * - **唯一真源**：`worldbooks` 表（`id` / `name` / `json`[整本 ST World Info] /
 *   `enabled` / `companionId` / `updatedAt`）。
 * - **旧表 `lorebooks` / `lorebook_entries` 不再读写**（§5.4 冻结归档，仅作回滚数据源）。
 *   存量数据由启动期 `WorldbookMigrator` 一次性迁入。
 *
 * ## 关键设计一：编辑后必须回灌运行时
 * `worldbooks` 是 Rust 引擎的实际输入。若本层只写表不同步，UI 的编辑**永远不会生效**。
 * 因此**每一次写操作之后**都调用 `AgentWorldbookRepository.syncActiveToRuntime`。
 *
 * ## 关键设计二：ST JSON 的往返保真
 * ST World Info 规范没有 `sortOrder` / `createdAt` / `updatedAt` / 独立 `priority`
 * 的完整语义，而 `WorldbookScreens` 会把它们原样回传。为做到**编辑往返无损**，本层：
 * - `insertion_order` **重编号为 1..N**（按 UI 排序位），使 Rust 侧排序确定且唯一；
 * - `sortOrder` / `createdAt` / `updatedAt` / `priority` 存入 `extensions` 的
 *   `_sortOrder` / `_createdAt` / `_updatedAt` / `_priority`（见 [WorldbookJsonCodec]）。
 *
 * ## 关键设计三：条目 id 必须全局唯一
 * UI 的删除/启停只传 `entryId`（不带书本），故条目 id 需跨书唯一。ST JSON 的
 * `entries` map 键即 id，读回时直接复用；新增条目用「全库最大 id + 1」分配。
 *
 * @param context 应用上下文（定位数据库与 AgentRuntime）
 * @param json 仅用于 `companions.lorebookIdsJson` 的读写；ST JSON 一律走 [WorldbookJsonCodec]
 */
class WorldbookRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LorebookProvider {

    private val appContext: Context = context.applicationContext
    private val database: AppDatabase = AppDatabase.getDatabase(appContext)
    private val dao: WorldbookDao = database.worldbookDao()
    private val companionDao = database.companionDao()

    /** `core:agent` 侧仓储：仅用于把编辑结果回灌 Rust 运行时。 */
    private val agentRepo: AgentWorldbookRepository by lazy {
        AgentWorldbookRepository(appContext)
    }

    // ────────────────────────────── 读：ST JSON → 领域模型 ──────────────────────────────

    /**
     * 解码单行的全部条目（**含禁用条目**，供编辑回显与计数）。
     *
     * 排序对齐旧行为 `ORDER BY sortOrder ASC, priority DESC, createdAt ASC`。
     * 迁移产物没有 `_sortOrder`（此时 `sortOrder` 回落为 0）→ 退化为按 id 升序，
     * 与迁移写入时的 `insertion_order`（= `priority` 取反）次序一致。
     */
    private fun decodeEntries(row: WorldbookEntity): List<LorebookEntryEntity> =
        WorldbookJsonCodec.entries(row.json)
            .map { it.toEntity(lorebookId = row.id) }
            .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))

    private fun toDomain(row: WorldbookEntity, entity: LorebookEntryEntity): LorebookEntry {
        val atDepth = entity.injectionPosition == com.yunian.ai.database.model.InjectionPosition.AT_DEPTH
        return LorebookEntry(
            id = entity.id,
            lorebookId = row.id,
            keywords = WorldbookJsonCodec.parseKeywords(entity.keywordsJson),
            content = entity.content,
            // 枚举 ordinal 已核对一致（DB / domain 同为 BEFORE_SYSTEM_PROMPT..AT_DEPTH）
            injectionPosition = InjectionPosition.values()[entity.injectionPosition.ordinal],
            priority = entity.priority,
            // 非 AT_DEPTH 时 UI 不展示深度，置 null 避免把 Rust 回落值回写成显式值
            injectDepth = if (atDepth) entity.injectDepth else null,
            role = EntryRole.values()[entity.role.ordinal],
            caseSensitive = entity.isCaseSensitive(),
            useRegex = entity.isUseRegex(),
            scanDepth = entity.scanDepth,
            constantActive = entity.isConstantActive(),
            enabled = entity.isEnabled(),
            sortOrder = entity.sortOrder,
            // 迁移产物无 `_createdAt`/`_updatedAt` → 用行 `updatedAt` 兜底，
            // 避免全为 0 时编辑往返把时间戳抹平
            createdAt = entity.createdAt.takeIf { it > 0L } ?: row.updatedAt,
            updatedAt = entity.updatedAt.takeIf { it > 0L } ?: row.updatedAt,
        )
    }

    private fun toDomain(row: WorldbookEntity, meta: WorldbookJsonCodec.StBookMeta): Lorebook = Lorebook(
        id = row.id,
        name = row.name,
        description = meta.description,
        companionId = row.companionId,
        enabled = row.enabled,
        createdAt = meta.createdAt.takeIf { it > 0L } ?: row.updatedAt,
        updatedAt = row.updatedAt,
    )

    override suspend fun getLorebookWithEntries(lorebookId: Long): LorebookWithEntries? =
        withContext(Dispatchers.IO) {
            val row = dao.byId(lorebookId) ?: return@withContext null
            LorebookWithEntries(
                lorebook = toDomain(row, WorldbookJsonCodec.bookMeta(row.json)),
                // 返回全量条目（含禁用），否则编辑页无法回显/重新启用被禁用的条目
                entries = decodeEntries(row).map { toDomain(row, it) },
            )
        }

    override suspend fun getEntries(lorebookId: Long): List<LorebookEntry> =
        withContext(Dispatchers.IO) {
            val row = dao.byId(lorebookId) ?: return@withContext emptyList()
            decodeEntries(row).map { toDomain(row, it) }
        }

    override suspend fun getAllLorebooks(): List<Lorebook> = withContext(Dispatchers.IO) {
        dao.all().map { toDomain(it, WorldbookJsonCodec.bookMeta(it.json)) }
    }

    // ────────────────────────────── 写：领域模型 → ST JSON ──────────────────────────────

    /**
     * 把一组条目编码回整本 ST JSON。
     *
     * `insertion_order` 按 UI 排序位重编号为 1..N：Rust 侧 `sort_by_key(insertion_order)`
     * 是稳定排序，键冲突时结果依赖 map 迭代顺序（不确定）。唯一化后顺序才完全确定；
     * 用户可见的优先级由 `extensions._priority` 单独承载。
     */
    private fun encodeBook(
        rowId: Long,
        name: String,
        description: String,
        createdAt: Long,
        entries: List<LorebookEntryEntity>,
    ): String {
        val ordered = entries.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))
        val objs = ordered.mapIndexed { idx, e ->
            WorldbookJsonCodec.entryToJson(
                entry = e,
                bookId = rowId,
                bookName = name,
                insertionOrder = (idx + 1).toLong(),
            )
        }
        return WorldbookJsonCodec.assemble(
            name = name,
            description = description.takeIf { it.isNotBlank() },
            scanDepth = WorldbookJsonCodec.dominantScanDepth(ordered),
            tokenBudget = WorldbookJsonCodec.DEFAULT_TOKEN_BUDGET,
            entryObjects = objs,
            createdAt = createdAt,
        )
    }

    /** 全库最大条目 id + 1（`entries` map 键即 id，同键会互相覆盖）。 */
    private suspend fun allocateEntryId(): Long {
        var max = 0L
        for (row in dao.all()) {
            for (st in WorldbookJsonCodec.entries(row.json)) {
                val v = st.uid.toLongOrNull() ?: continue
                if (v > max) max = v
            }
        }
        return max + 1L
    }

    /** 写回单行并以该行作用域回灌运行时。 */
    private suspend fun persist(row: WorldbookEntity, entries: List<LorebookEntryEntity>) {
        val meta = WorldbookJsonCodec.bookMeta(row.json)
        val encoded = encodeBook(
            rowId = row.id,
            name = row.name,
            description = meta.description,
            createdAt = meta.createdAt,
            entries = entries,
        )
        dao.upsert(row.copy(json = encoded, updatedAt = System.currentTimeMillis()))
        sync(row.companionId ?: 0L)
    }

    /** 编辑后回灌 Rust 运行时（覆盖式写入，幂等；`companionId <= 0` = 全局作用域）。 */
    private suspend fun sync(companionId: Long) {
        runCatching { agentRepo.syncActiveToRuntime(companionId) }
            .onFailure { Log.w(TAG, "世界书运行时同步失败", it) }
    }

    /** 领域条目 → DB 条目，并为 `id <= 0` 的新条目分配全局唯一 id。 */
    private fun buildEntries(
        entries: List<LorebookEntry>,
        lorebookId: Long,
        nextId: Long,
    ): List<LorebookEntryEntity> {
        var cursor = nextId
        return entries.map { e ->
            val id = if (e.id > 0L) e.id else cursor++
            val atDepth = e.injectionPosition == InjectionPosition.AT_DEPTH
            LorebookEntryEntity(
                id = id,
                lorebookId = lorebookId,
                keywordsJson = WorldbookJsonCodec.encodeKeywords(e.keywords),
                content = e.content,
                injectionPosition =
                    com.yunian.ai.database.model.InjectionPosition.values()[e.injectionPosition.ordinal],
                priority = e.priority,
                injectDepth = if (atDepth) e.injectDepth else null,
                role = com.yunian.ai.database.model.EntryRole.values()[e.role.ordinal],
                caseSensitive = if (e.caseSensitive) 1 else 0,
                useRegex = if (e.useRegex) 1 else 0,
                sortOrder = e.sortOrder,
                scanDepth = e.scanDepth.coerceAtLeast(1),
                constantActive = if (e.constantActive) 1 else 0,
                enabled = if (e.enabled) 1 else 0,
                createdAt = e.createdAt,
                updatedAt = e.updatedAt,
            )
        }
    }

    override suspend fun createLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val name = lorebook.name.takeIf { it.isNotBlank() } ?: "未命名世界书"
            // 先占位插入取得行 id（条目 id 分配与 `_bookId` 编码都依赖它）
            val rowId = dao.upsert(
                WorldbookEntity(
                    name = name,
                    json = "{}",
                    enabled = lorebook.enabled,
                    companionId = lorebook.companionId,
                    updatedAt = now,
                ),
            )
            val row = dao.byId(rowId) ?: return@withContext 0L
            val encoded = encodeBook(
                rowId = rowId,
                name = name,
                description = lorebook.description,
                createdAt = lorebook.createdAt.takeIf { it > 0L } ?: now,
                entries = buildEntries(entries, rowId, nextId = allocateEntryId()),
            )
            dao.upsert(row.copy(json = encoded, updatedAt = now))
            sync(row.companionId ?: 0L)
            rowId
        }

    override suspend fun updateLorebook(lorebook: Lorebook, entries: List<LorebookEntry>): Boolean =
        withContext(Dispatchers.IO) {
            val row = dao.byId(lorebook.id) ?: return@withContext false
            val name = lorebook.name.takeIf { it.isNotBlank() } ?: row.name
            val meta = WorldbookJsonCodec.bookMeta(row.json)
            val encoded = encodeBook(
                rowId = lorebook.id,
                name = name,
                description = lorebook.description,
                createdAt = lorebook.createdAt.takeIf { it > 0L } ?: meta.createdAt,
                entries = buildEntries(entries, lorebook.id, nextId = allocateEntryId()),
            )
            dao.upsert(
                row.copy(
                    name = name,
                    json = encoded,
                    enabled = lorebook.enabled,
                    companionId = lorebook.companionId,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            sync(lorebook.companionId ?: 0L)
            true
        }

    override suspend fun deleteLorebook(lorebookId: Long): Boolean = withContext(Dispatchers.IO) {
        val row = dao.byId(lorebookId) ?: return@withContext false
        dao.delete(lorebookId)
        sync(row.companionId ?: 0L)
        true
    }

    /**
     * 启用/停用整本书。
     *
     * ★ 刻意**不做**「先清空其他启用项」的单激活清理：本地语义允许多本同时启用，
     * 运行时由 `core:agent` 的 `synthForCompanion` 按 (priority desc, id) 合并全部
     * 启用行，多启用是有效状态而非脏数据。
     */
    override suspend fun setLorebookEnabled(lorebookId: Long, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val row = dao.byId(lorebookId) ?: return@withContext false
            dao.upsert(row.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
            sync(row.companionId ?: 0L)
            true
        }

    override suspend fun upsertEntry(entry: LorebookEntry): Long = withContext(Dispatchers.IO) {
        val row = dao.byId(entry.lorebookId) ?: return@withContext 0L
        val current = decodeEntries(row).toMutableList()
        val id = if (entry.id > 0L) entry.id else allocateEntryId()
        val entity = buildEntries(
            listOf(entry.copy(id = id, lorebookId = row.id)),
            lorebookId = row.id,
            nextId = id,
        ).first()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) current[index] = entity else current += entity
        persist(row, current)
        id
    }

    override suspend fun deleteEntry(entryId: Long): Boolean = withContext(Dispatchers.IO) {
        val located = locateEntry(entryId) ?: return@withContext false
        val (row, entries) = located
        persist(row, entries.filterNot { it.id == entryId })
        true
    }

    override suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val located = locateEntry(entryId) ?: return@withContext false
            val (row, entries) = located
            val now = System.currentTimeMillis()
            persist(
                row,
                entries.map {
                    if (it.id == entryId) it.copy(enabled = if (enabled) 1 else 0, updatedAt = now) else it
                },
            )
            true
        }

    /** 跨书定位条目（UI 的删除/启停只传 `entryId`，不带书本 id）。 */
    private suspend fun locateEntry(entryId: Long): Pair<WorldbookEntity, List<LorebookEntryEntity>>? {
        for (row in dao.all()) {
            val entries = decodeEntries(row)
            if (entries.any { it.id == entryId }) return row to entries
        }
        return null
    }

    // ────────────────────────── 伴侣绑定（`companions.lorebookIdsJson`） ──────────────────────────

    override suspend fun getBoundLorebookIds(companionId: Long): List<Long> =
        withContext(Dispatchers.IO) { parseBoundIds(companionId) }

    /**
     * 保存角色绑定的全局世界书 ID 集合（空列表 = 未做绑定选择 → 所有全局书自动生效）。
     *
     * 与 `core:agent.WorldbookRepository.parseBoundIds` 读同一列的同一语义，
     * 两处解析规则必须保持一致。
     */
    override suspend fun setBoundLorebookIds(companionId: Long, ids: List<Long>): Boolean =
        withContext(Dispatchers.IO) {
            val companion = companionDao.getCompanionById(companionId) ?: return@withContext false
            val normalized = ids.filter { it > 0L }.distinct()
            val updated = companion.copy(
                lorebookIdsJson = json.encodeToString(normalized),
                updatedAt = System.currentTimeMillis(),
            )
            val ok = companionDao.updateCompanion(updated) > 0
            if (ok) sync(companionId)
            ok
        }

    private suspend fun parseBoundIds(companionId: Long): List<Long> = runCatching {
        val raw = companionDao.getCompanionById(companionId)?.lorebookIdsJson
            ?: return@runCatching emptyList()
        json.decodeFromString<List<Long>>(raw).filter { it > 0L }.distinct()
    }.getOrElse { emptyList() }

    private companion object {
        private const val TAG = "WorldbookRepository"
    }
}
