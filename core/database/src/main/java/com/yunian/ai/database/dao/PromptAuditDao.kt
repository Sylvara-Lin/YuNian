package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.PromptAuditEntity

/**
 * 提示词编排审计 DAO（只写 + 近况查询 + 清理）。
 */
@Dao
interface PromptAuditDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(audit: PromptAuditEntity): Long

    /** 最近 N 条（分页，按时间倒序） */
    @Query("SELECT * FROM prompt_audit ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun recent(limit: Int, offset: Int): List<PromptAuditEntity>

    /** 按会话查询（同一 conversationId 的连续回合） */
    @Query("SELECT * FROM prompt_audit WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun bySession(sessionId: String): List<PromptAuditEntity>

    /** 删除早于 threshold 的记录，返回删除条数（保留窗口清理） */
    @Query("DELETE FROM prompt_audit WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int

    @Query("SELECT COUNT(*) FROM prompt_audit")
    suspend fun count(): Int
}
