package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yunian.ai.database.model.StickerUsageLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * 表情包使用日志 DAO。
 *
 * 供 Rust 引擎做偏好学习 / 衰减 / 漂移检测；Kotlin 侧负责聚合清理（90 天 / 5000 条）。
 */
@Dao
interface StickerUsageLogDao {

    @Insert
    suspend fun insert(log: StickerUsageLogEntity): Long

    /** 某表情包的最近使用记录（供引擎窗口分析） */
    @Query("SELECT * FROM sticker_usage_log WHERE stickerId = :stickerId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentForSticker(stickerId: Long, limit: Int): List<StickerUsageLogEntity>

    /** 最近 N 条 USER 记录（漂移检测 recent 窗口） */
    @Query("SELECT * FROM sticker_usage_log WHERE source = 'user' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentUserLogs(limit: Int): List<StickerUsageLogEntity>

    /** 某时间点之前的所有 USER 记录（漂移检测 history 窗口） */
    @Query("SELECT * FROM sticker_usage_log WHERE source = 'user' AND timestamp <= :beforeMs ORDER BY timestamp ASC")
    suspend fun getUserLogsBefore(beforeMs: Long): List<StickerUsageLogEntity>

    /** 全量最近 N 条（漂移窗口分析；时间降序，最新在前——Rust `user[..w]` = recent 窗口语义） */
    @Query("SELECT * FROM sticker_usage_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int): List<StickerUsageLogEntity>

    /** 某时间点之前的所有记录（聚合清理用） */
    @Query("SELECT * FROM sticker_usage_log WHERE timestamp <= :beforeMs ORDER BY timestamp ASC")
    suspend fun getLogsBefore(beforeMs: Long): List<StickerUsageLogEntity>

    /** 按时间范围删除（清理用） */
    @Query("DELETE FROM sticker_usage_log WHERE timestamp <= :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    /** 统计总数 */
    @Query("SELECT COUNT(*) FROM sticker_usage_log")
    suspend fun count(): Int

    /** 观察最近记录（调试用） */
    @Query("SELECT * FROM sticker_usage_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<StickerUsageLogEntity>>
}
