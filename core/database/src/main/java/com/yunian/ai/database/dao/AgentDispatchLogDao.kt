package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.AgentDispatchLogEntity

/**
 * Agent 调度日志 DAO（只写 + 近况查询 + 按调度/会话查询 + 清理）。
 */
@Dao
interface AgentDispatchLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AgentDispatchLogEntity): Long

    /** 最近 N 条（分页，按时间倒序） */
    @Query("SELECT * FROM agent_dispatch_log ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun recent(limit: Int, offset: Int): List<AgentDispatchLogEntity>

    /** 按调度 id 查询（一次完整 Agent 回合） */
    @Query("SELECT * FROM agent_dispatch_log WHERE dispatchId = :dispatchId ORDER BY timestamp ASC")
    suspend fun byDispatchId(dispatchId: String): List<AgentDispatchLogEntity>

    /** 按会话查询（同一 conversationId 的连续回合） */
    @Query("SELECT * FROM agent_dispatch_log WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun bySession(sessionId: String): List<AgentDispatchLogEntity>

    /** 按单聊陪伴者查询（时间倒序） */
    @Query("SELECT * FROM agent_dispatch_log WHERE companionId = :companionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun byCompanion(companionId: Long, limit: Int): List<AgentDispatchLogEntity>

    /** 按群聊查询（时间倒序） */
    @Query("SELECT * FROM agent_dispatch_log WHERE groupId = :groupId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun byGroup(groupId: Long, limit: Int): List<AgentDispatchLogEntity>

    /** 删除早于 threshold 的记录，返回删除条数（保留窗口清理） */
    @Query("DELETE FROM agent_dispatch_log WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int

    @Query("SELECT COUNT(*) FROM agent_dispatch_log")
    suspend fun count(): Int
}
