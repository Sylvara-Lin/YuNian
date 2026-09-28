package com.yunian.ai.agent.worldbook

import android.content.Context
import android.util.Log
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.repository.AppMetaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 本地世界树 → SillyTavern World Info JSON 一次性迁移（plan doc §5.3）。
 *
 * ## 迁移语义
 * `lorebooks` + `lorebook_entries` **一对一**转成 `worldbooks` 行：
 * - 保留**书本边界**（不把多本书合并成一条记录），便于回滚与人工核对；
 * - 每条 entry 写 `extensions._bookId` / `_bookName`，运行时合并后仍可回溯来源；
 * - `enabled` **显式写出**（Rust 缺省为 `true`，省略会让停用条目意外生效）；
 * - `priority` 取反成 `insertion_order`（本地降序 = ST 升序）。

 * 源表 `lorebooks` / `lorebook_entries` **保留不删**（§5.4 冻结归档 = 回滚数据源）。
 *
 * ## 激活位语义
 * `enabled` **逐本沿用源值**（不收敛为单激活）：运行时
 * `WorldbookRepository.synthForCompanion` 会把该伴侣作用域下**全部启用行**按
 * `(priority desc, id)` 合并（Q4），因此多本同时启用是有效状态而非脏数据。
 *
 * ## 幂等
 * 以 `app_meta` 键 [FLAG_MIGRATED] 为一次性开关；已迁移过则直接返回
 * [Result.Skipped]，不触碰任何数据。**未迁移成功不写标记**，下次启动重试。
 */
class WorldbookMigrator(private val context: Context) {

    private val db = AppDatabase.getDatabase(context.applicationContext)
    private val lorebookDao = db.lorebookDao()
    private val worldbookDao = db.worldbookDao()
    private val metaStore = AppMetaStore(db.appMetaDao())

    /** 迁移结果。 */
    sealed interface Result {
        /** 首次迁移完成。 */
        data class Migrated(val books: Int, val entries: Int) : Result

        /** 已迁移过（标记存在），本次跳过。 */
        object Skipped : Result

        /** 无源数据可迁移（全新安装），仅写标记。 */
        object Empty : Result

        /** 失败：源数据保留、未写标记，下次启动重试。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 执行迁移。**必须**在 `AppDatabase` 完成 `MIGRATION_44_45` 之后调用
     * （即 `AppDatabase.getDatabase()` 返回的实例已可用）。
     */
    suspend fun run(): Result = withContext(Dispatchers.IO) {
        if (metaStore.contains(FLAG_MIGRATED)) return@withContext Result.Skipped

        val books = runCatching { lorebookDao.getAllLorebooks() }
            .getOrElse { return@withContext Result.Failed("读取 lorebooks 失败: ${it.message}") }

        // 冲突检测：同一伴侣作用域下多本启用 → 提示（本地允许多本，master 单选）
        warnOnMultiActive(books)

        if (books.isEmpty()) {
            metaStore.putString(FLAG_MIGRATED, STAMP)
            return@withContext Result.Empty
        }

        // 逐本转换；任何一本出错都整体回滚（不写标记）
        val payloads = ArrayList<Pair<com.yunian.ai.database.model.LorebookEntity, List<com.yunian.ai.database.model.LorebookEntryEntity>>>()
        var srcEntryTotal = 0
        for (book in books) {
            val entries = runCatching { lorebookDao.getEntriesByLorebookId(book.id) }
                .getOrElse { return@withContext Result.Failed("读取 lorebook ${book.id} 条目失败: ${it.message}") }
            payloads += book to entries
            srcEntryTotal += entries.size
        }

        var dstEntryTotal = 0
        val written = ArrayList<Pair<Long, String>>()
        for ((book, entries) in payloads) {
            val json = WorldbookJsonCodec.bookToJson(book, entries)
            // 迁移自检（§5.11）：JSON 内条目数必须等于源条目数
            val inJson = WorldbookJsonCodec.entryCount(json)
            if (inJson != entries.size) {
                return@withContext Result.Failed(
                    "书本 ${book.id}「${book.name}」条目数不符: 源 ${entries.size} / JSON $inJson",
                )
            }
            dstEntryTotal += inJson
            written += book.id to json
        }

        if (dstEntryTotal != srcEntryTotal) {
            return@withContext Result.Failed("总条目数不符: 源 $srcEntryTotal / JSON $dstEntryTotal")
        }

        // 落库：worldbooks 为纯增量表，安全
        for ((srcId, json) in written) {
            val book = books.first { it.id == srcId }
            worldbookDao.upsert(
                com.yunian.ai.database.model.WorldbookEntity(
                    name = book.name.takeIf { it.isNotBlank() } ?: "世界书 $srcId",
                    json = json,
                    // ★ 直接沿用源启用位（不做单激活收敛）：运行时由
                    // `WorldbookRepository.synthForCompanion` 按 (priority desc, id)
                    // **合并全部启用行**（Q4），本地多本同时启用是有效状态。
                    // 若在此收敛为单本，用户已启用的其余世界书会静默失效。
                    enabled = book.isEnabled(),
                    companionId = book.companionId,
                    updatedAt = book.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                ),
            )
        }

        metaStore.putString(FLAG_MIGRATED, STAMP)
        Log.i(TAG, "世界书迁移完成: ${written.size} 本 / $dstEntryTotal 条（源 $srcEntryTotal 条）")
        Result.Migrated(books = written.size, entries = dstEntryTotal)
    }

    /**
     * 本地允许多本同时启用。运行时为**多本合并**（Q4），故多启用不是异常；
     * 仅在同作用域内出现**同名**书本时告警（运行时会按 `content` 去重，可能盖掉其中一本）。
     */
    private fun warnOnMultiActive(books: List<com.yunian.ai.database.model.LorebookEntity>) {
        val dupNames = books.filter { it.isEnabled() }
            .groupBy { it.companionId to it.name }
            .filterValues { it.size > 1 }
        if (dupNames.isNotEmpty()) {
            val detail = dupNames.entries.joinToString("; ") { (key, list) ->
                "companionId=${key.first ?: "全局"}「${key.second}」→ ${list.size} 本"
            }
            Log.w(TAG, "检测到同作用域同名启用世界书（合并时按 content 去重，可能互相覆盖）: $detail")
        }
    }

    companion object {
        private const val TAG = "WorldbookMigrator"

        /** `app_meta` 一次性标记键。 */
        const val FLAG_MIGRATED = "worldbook_migrated_st_v1"

        /** 标记值：记录迁移格式与时间，便于后续排查。 */
        private val STAMP = JSONObject().apply {
            put("format", "sillytavern_world_info")
            put("container", "map")
            put("codec", 1)
        }.toString()
    }
}
