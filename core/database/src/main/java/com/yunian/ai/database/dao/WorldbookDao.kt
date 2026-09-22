package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.WorldbookEntity

/** 世界书 DAO。 */
@Dao
interface WorldbookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: WorldbookEntity): Long

    @Query("SELECT * FROM worldbooks ORDER BY updatedAt DESC")
    suspend fun all(): List<WorldbookEntity>

    @Query("SELECT * FROM worldbooks WHERE id = :id")
    suspend fun byId(id: Long): WorldbookEntity?

    @Query("SELECT * FROM worldbooks WHERE enabled = 1 LIMIT 1")
    suspend fun active(): WorldbookEntity?

    /** 伴侣级：取该伴侣的激活世界书；无则全局。 */
    @Query("SELECT * FROM worldbooks WHERE enabled = 1 AND companionId = :companionId LIMIT 1")
    suspend fun activeForCompanion(companionId: Long): WorldbookEntity?

    @Query("UPDATE worldbooks SET enabled = 0")
    suspend fun clearEnabled()

    @Query("DELETE FROM worldbooks WHERE id = :id")
    suspend fun delete(id: Long)
}
