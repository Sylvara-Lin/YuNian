package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.WeChatInboxDedupeEntity

@Dao
interface WeChatInboxDedupeDao {

    @Query("SELECT dedupeKey FROM wechat_inbox_dedupe WHERE dedupeKey = :key LIMIT 1")
    suspend fun findKey(key: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: WeChatInboxDedupeEntity): Long

    @Query("DELETE FROM wechat_inbox_dedupe WHERE processedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM wechat_inbox_dedupe")
    suspend fun count(): Int
}
