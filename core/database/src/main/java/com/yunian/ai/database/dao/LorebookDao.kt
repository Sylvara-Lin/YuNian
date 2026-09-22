package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yunian.ai.database.model.LorebookEntity
import com.yunian.ai.database.model.LorebookEntryEntity
import com.yunian.ai.database.model.LorebookWithEntries
import kotlinx.coroutines.flow.Flow

@Dao
interface LorebookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLorebook(lorebook: LorebookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLorebooks(lorebooks: List<LorebookEntity>): List<Long>

    @Update
    suspend fun updateLorebook(lorebook: LorebookEntity): Int

    @Delete
    suspend fun deleteLorebook(lorebook: LorebookEntity): Int

    @Query("DELETE FROM lorebooks WHERE id = :id")
    suspend fun deleteLorebookById(id: Long): Int

    @Query("SELECT * FROM lorebooks WHERE id = :id")
    suspend fun getLorebookById(id: Long): LorebookEntity?

    @Query("SELECT * FROM lorebooks WHERE id = :id")
    fun getLorebookByIdFlow(id: Long): Flow<LorebookEntity?>

    @Query("SELECT * FROM lorebooks WHERE companionId = :companionId ORDER BY createdAt DESC")
    suspend fun getLorebooksByCompanionId(companionId: Long): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE companionId = :companionId ORDER BY createdAt DESC")
    fun getLorebooksByCompanionIdFlow(companionId: Long): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE companionId IS NULL ORDER BY createdAt DESC")
    suspend fun getGlobalLorebooks(): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE companionId IS NULL ORDER BY createdAt DESC")
    fun getGlobalLorebooksFlow(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    suspend fun getEnabledLorebooksForCompanion(companionId: Long): List<LorebookEntity>

    /** 按显式 ID 集合查询启用的世界书（角色绑定勾选场景） */
    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND id IN (:ids) ORDER BY createdAt DESC")
    suspend fun getEnabledLorebooksByIds(ids: List<Long>): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    fun getEnabledLorebooksForCompanionFlow(companionId: Long): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks ORDER BY createdAt DESC")
    suspend fun getAllLorebooks(): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks ORDER BY createdAt DESC")
    fun getAllLorebooksFlow(): Flow<List<LorebookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: LorebookEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<LorebookEntryEntity>): List<Long>

    @Update
    suspend fun updateEntry(entry: LorebookEntryEntity): Int

    @Delete
    suspend fun deleteEntry(entry: LorebookEntryEntity): Int

    @Query("DELETE FROM lorebook_entries WHERE id = :id")
    suspend fun deleteEntryById(id: Long): Int

    @Query("DELETE FROM lorebook_entries WHERE lorebookId = :lorebookId")
    suspend fun deleteEntriesByLorebookId(lorebookId: Long): Int

    @Query("SELECT * FROM lorebook_entries WHERE id = :id")
    suspend fun getEntryById(id: Long): LorebookEntryEntity?

    @Query("SELECT * FROM lorebook_entries WHERE lorebookId = :lorebookId ORDER BY sortOrder ASC, priority DESC, createdAt ASC")
    suspend fun getEntriesByLorebookId(lorebookId: Long): List<LorebookEntryEntity>

    @Query("SELECT * FROM lorebook_entries WHERE lorebookId = :lorebookId ORDER BY sortOrder ASC, priority DESC, createdAt ASC")
    fun getEntriesByLorebookIdFlow(lorebookId: Long): Flow<List<LorebookEntryEntity>>

    @Query("SELECT * FROM lorebook_entries WHERE enabled = 1 AND lorebookId = :lorebookId ORDER BY sortOrder ASC, priority DESC, createdAt ASC")
    suspend fun getEnabledEntriesByLorebookId(lorebookId: Long): List<LorebookEntryEntity>

    @Query("SELECT * FROM lorebook_entries WHERE enabled = 1 AND lorebookId = :lorebookId ORDER BY sortOrder ASC, priority DESC, createdAt ASC")
    fun getEnabledEntriesByLorebookIdFlow(lorebookId: Long): Flow<List<LorebookEntryEntity>>

    @Transaction
    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    suspend fun getEnabledLorebooksWithEntries(companionId: Long): List<LorebookWithEntries>

    @Transaction
    @Query("SELECT * FROM lorebooks WHERE enabled = 1 AND (companionId = :companionId OR companionId IS NULL) ORDER BY createdAt DESC")
    fun getEnabledLorebooksWithEntriesFlow(companionId: Long): Flow<List<LorebookWithEntries>>

    @Query("SELECT COUNT(*) FROM lorebooks WHERE companionId = :companionId")
    suspend fun countLorebooksByCompanionId(companionId: Long): Int

    @Query("SELECT COUNT(*) FROM lorebook_entries WHERE lorebookId = :lorebookId")
    suspend fun countEntriesByLorebookId(lorebookId: Long): Int

    @Query("SELECT COUNT(*) FROM lorebook_entries WHERE lorebookId = :lorebookId AND enabled = 1")
    suspend fun countEnabledEntriesByLorebookId(lorebookId: Long): Int
}
