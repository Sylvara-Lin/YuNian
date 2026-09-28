package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yunian.ai.database.model.DelegationRecordEntity

/** 委派记录 DAO。 */
@Dao
interface DelegationDao {
    @Insert
    suspend fun insert(record: DelegationRecordEntity): Long

    @Query("SELECT * FROM delegation_records WHERE id = :id")
    suspend fun byId(id: Long): DelegationRecordEntity?

    @Query("SELECT * FROM delegation_records WHERE status = :status ORDER BY createdAtMs ASC")
    suspend fun byStatus(status: String): List<DelegationRecordEntity>

    @Query("SELECT * FROM delegation_records ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DelegationRecordEntity>

    @Query("UPDATE delegation_records SET status = :status, result = :result, error = :error, dispatchId = :dispatchId, completedAtMs = :completedAtMs WHERE id = :id")
    suspend fun update(
        id: Long, status: String, result: String, error: String, dispatchId: String?, completedAtMs: Long?,
    ): Int
}
