package com.yunian.ai.agent.sticker

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.uniffi.StickerPreferenceStore
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.StickerUsageLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust `StickerPreferenceStore` 回调实现（Room 读写，纯 IO 无决策）。
 *
 * 契约（与 `agent-native/src/sticker_preference.rs` serde 字段对齐，snake_case）：
 * - `listEntries` → JSON 数组 `[StickerEntryMeta, ...]`
 *   `{id, tags:[..], user_usage_count, model_usage_count, last_used_ms, created_ms}`
 * - `recordUsage` → 插入 `sticker_usage_log` 行 + 累加 `sticker_entries` 对应计数列，返回是否成功
 * - `usageHistory` → JSON 数组 `[StickerUsagePoint, ...]`
 *   `{sticker_id, source, timestamp_ms, context_tags:[..]}`，**时间降序（最新在前）**——
 *   Rust `check_drift` 以 `user[..w]` 为 recent 窗口（uniffi 接口文档注释"升序"为过时表述）
 *
 * 来源判断：source == "user" → 累加 `userUsageCount`（偏好先验只聚合此列，D4）；
 * "model" → 累加 `modelUsageCount`（只记数不污染偏好）。
 */
class StickerPreferenceStoreImpl(context: Context) : StickerPreferenceStore {

    private val appContext: Context = context.applicationContext
    private val entryDao = AppDatabase.getDatabase(appContext).stickerEntryDao()
    private val logDao = AppDatabase.getDatabase(appContext).stickerUsageLogDao()

    /** 全部条目元数据 JSON（供引擎 rebuild 全量载入） */
    override fun listEntries(): String = runBlockingOnIo {
        val arr = JSONArray()
        entryDao.getAll().forEach { row ->
            arr.put(
                JSONObject().apply {
                    put("id", row.id)
                    put("tags", splitCsv(row.tags))
                    put("user_usage_count", row.userUsageCount)
                    put("model_usage_count", row.modelUsageCount)
                    put("last_used_ms", row.lastUsedAt ?: 0L)
                    put("created_ms", row.createdAt ?: 0L)
                }
            )
        }
        arr.toString()
    }

    /** 记录一次使用：log 行 + 计数列；失败返回 false（Rust 侧忽略错误） */
    override fun recordUsage(
        stickerId: ULong,
        source: String,
        timestampMs: ULong,
        contextTagsJson: String,
    ): Boolean {
        return try {
            runBlockingOnIo {
                logDao.insert(
                    StickerUsageLogEntity(
                        stickerId = stickerId.toLong(),
                        source = source,
                        timestamp = timestampMs.toLong(),
                        contextTags = contextTagsJson,
                    )
                )
                when (source) {
                    "user" -> entryDao.bumpUserUsage(stickerId.toLong(), 1, timestampMs.toLong())
                    "model" -> entryDao.bumpModelUsage(stickerId.toLong(), 1, timestampMs.toLong())
                    else -> Unit
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "recordUsage failed", e)
            false
        }
    }

    /** 历史使用记录 JSON（时间降序；limit 上限条数） */
    override fun usageHistory(limit: ULong): String = runBlockingOnIo {
        val arr = JSONArray()
        logDao.getRecentLogs(limit.toInt().coerceAtLeast(1)).forEach { row ->
            arr.put(
                JSONObject().apply {
                    put("sticker_id", row.stickerId)
                    put("source", row.source)
                    put("timestamp_ms", row.timestamp)
                    put("context_tags", parseContextTags(row.contextTags))
                }
            )
        }
        arr.toString()
    }

    /** "a,b,c" → JSONArray["a","b","c"] */
    private fun splitCsv(csv: String): JSONArray = JSONArray().apply {
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
    }

    /**
     * context_tags 解析：Rust 回传的是 JSON 数组字符串（如 `["开心","下班"]`），
     * 以 `[` 开头直接解析；否则按逗号分隔兜底（历史/手工数据）。
     */
    private fun parseContextTags(raw: String): JSONArray {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("[")) {
            try {
                JSONArray(trimmed)
            } catch (e: Exception) {
                splitCsv(trimmed)
            }
        } else {
            splitCsv(trimmed)
        }
    }

    /** 回调线程（Rust 侧）非主线程，IO 用 Dispatchers.IO 兜底 */
    private inline fun <T> runBlockingOnIo(crossinline block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "StickerPreferenceStoreImpl"
    }
}
