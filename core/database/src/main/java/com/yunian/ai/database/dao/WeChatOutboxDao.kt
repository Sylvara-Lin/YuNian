package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.WeChatOutboxEntity

@Dao
interface WeChatOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<WeChatOutboxEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WeChatOutboxEntity)

    @Query(
        """
        SELECT * FROM wechat_outbox
        WHERE status IN ('PENDING', 'FAILED')
          AND nextAttemptAtMs <= :nowMs
        ORDER BY createdAtMs ASC, segmentIndex ASC
        LIMIT :limit
        """,
    )
    suspend fun listReady(nowMs: Long, limit: Int): List<WeChatOutboxEntity>

        @Query(
                """
                SELECT * FROM wechat_outbox
                WHERE rootId = :rootId
                    AND status IN ('PENDING', 'FAILED')
                ORDER BY segmentIndex ASC
                """,
        )
        suspend fun listOpenByRootId(rootId: String): List<WeChatOutboxEntity>

    @Query(
        """
        UPDATE wechat_outbox
        SET status = :status,
            retryCount = :retryCount,
            nextAttemptAtMs = :nextAttemptAtMs,
            lastError = :lastError,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """,
    )
    suspend fun updateAttempt(
        id: String,
        status: String,
        retryCount: Int,
        nextAttemptAtMs: Long,
        lastError: String?,
        updatedAtMs: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE wechat_outbox
        SET status = :status,
            lastError = :lastError,
            updatedAtMs = :updatedAtMs
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(
        id: String,
        status: String,
        lastError: String? = null,
        updatedAtMs: Long = System.currentTimeMillis(),
    )

    @Query("SELECT COUNT(*) FROM wechat_outbox WHERE status IN ('PENDING', 'FAILED', 'SENDING')")
    suspend fun countOpen(): Int

    @Query("SELECT COUNT(*) FROM wechat_outbox WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query(
        """
        SELECT * FROM wechat_outbox
        WHERE status = 'FAILED'
        ORDER BY updatedAtMs DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentFailed(limit: Int): List<WeChatOutboxEntity>

    @Query("DELETE FROM wechat_outbox WHERE status = 'SENT' AND updatedAtMs < :cutoffMs")
    suspend fun deleteSentBefore(cutoffMs: Long): Int

    @Query("DELETE FROM wechat_outbox WHERE status = 'FAILED' AND retryCount >= :maxRetry AND updatedAtMs < :cutoffMs")
    suspend fun deleteDeadBefore(maxRetry: Int, cutoffMs: Long): Int

    @Query("SELECT DISTINCT mediaLocalPath FROM wechat_outbox WHERE mediaLocalPath IS NOT NULL")
    suspend fun listMediaLocalPaths(): List<String>
}
