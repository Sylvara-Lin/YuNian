package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.DiaryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE companionId = :companionId AND deviceId = :deviceId ORDER BY date DESC")
    fun getDiariesForCompanion(companionId: Long, deviceId: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE companionId = :companionId AND deviceId = :deviceId AND date BETWEEN :startTime AND :endTime ORDER BY date DESC")
    fun getDiariesByDateRange(companionId: Long, deviceId: String, startTime: Long, endTime: Long): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE deviceId = :deviceId ORDER BY date DESC")
    fun getAllDiaries(deviceId: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE deviceId = :deviceId ORDER BY date DESC")
    suspend fun getAllDiariesSync(deviceId: String): List<DiaryEntry>

    @Query("SELECT * FROM diary_entries WHERE companionId = :companionId AND deviceId = :deviceId ORDER BY date DESC")
    suspend fun getDiariesForCompanionSync(companionId: Long, deviceId: String): List<DiaryEntry>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun getDiaryById(id: Long): DiaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiary(diary: DiaryEntry): Long

    @Delete
    suspend fun deleteDiary(diary: DiaryEntry): Int

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun deleteDiaryById(id: Long): Int

    @Query("DELETE FROM diary_entries WHERE companionId = :companionId AND deviceId = :deviceId")
    suspend fun deleteDiariesForCompanion(companionId: Long, deviceId: String): Int

    @Query("SELECT COUNT(*) FROM diary_entries WHERE companionId = :companionId AND deviceId = :deviceId")
    suspend fun getDiaryCount(companionId: Long, deviceId: String): Int
}
