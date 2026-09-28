package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.EventLedgerEntity
import com.yunian.ai.database.model.EventLedgerSnapshotEntity

/** 事件溯源账本 DAO（只追加 + 流读取 + 快照 + 近况）。 */
@Dao
interface EventLedgerDao {
    /** 追加（冲突交给实现侧处理）。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: EventLedgerEntity): Long

    @Query("SELECT * FROM event_ledger WHERE streamId = :streamId ORDER BY sequence ASC")
    suspend fun stream(streamId: String): List<EventLedgerEntity>

    @Query("SELECT * FROM event_ledger WHERE streamId = :streamId AND sequence > :afterSequence ORDER BY sequence ASC")
    suspend fun streamAfter(streamId: String, afterSequence: Long): List<EventLedgerEntity>

    @Query("SELECT MAX(sequence) FROM event_ledger WHERE streamId = :streamId")
    suspend fun lastSequence(streamId: String): Long?

    @Query("SELECT hash FROM event_ledger WHERE streamId = :streamId AND sequence = :sequence")
    suspend fun hashAt(streamId: String, sequence: Long): String?

    @Query("SELECT * FROM event_ledger WHERE streamId = :streamId AND idempotencyKey = :key LIMIT 1")
    suspend fun byIdempotency(streamId: String, key: String): EventLedgerEntity?

    @Query("SELECT * FROM event_ledger ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventLedgerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: EventLedgerSnapshotEntity)

    @Query("SELECT * FROM event_ledger_snapshot WHERE streamId = :streamId")
    suspend fun snapshot(streamId: String): EventLedgerSnapshotEntity?
}
