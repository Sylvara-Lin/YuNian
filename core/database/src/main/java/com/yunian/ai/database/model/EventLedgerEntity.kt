package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yunian.ai.domain.timeline.LedgerEvent

/**
 * 事件溯源账本条目（Room event_ledger，不可变追加）。
 *
 * 索引：
 * - UNIQUE(streamId, sequence)：流内版本唯一，防并发重复；
 * - UNIQUE(idempotencyKey)：幂等键唯一（NULL 不参与唯一约束）。
 */
@Entity(
    tableName = "event_ledger",
    indices = [
        Index(value = ["streamId", "sequence"], unique = true),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["timestamp"]),
    ],
)
data class EventLedgerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val streamId: String,
    val sequence: Long,
    val type: String,
    val timestamp: Long,
    val payloadJson: String,
    val metadataJson: String = "{}",
    val idempotencyKey: String? = null,
    val prevHash: String = "",
    val hash: String = "",
) {
    fun toDomain(): LedgerEvent = LedgerEvent(
        eventId = id,
        streamId = streamId,
        sequence = sequence,
        type = type,
        timestamp = timestamp,
        payloadJson = payloadJson,
        metadataJson = metadataJson,
        idempotencyKey = idempotencyKey,
        prevHash = prevHash,
        hash = hash,
    )
}

/** 流快照（Room event_ledger_snapshot）。 */
@Entity(tableName = "event_ledger_snapshot")
data class EventLedgerSnapshotEntity(
    @PrimaryKey
    val streamId: String,
    val version: Long,
    val stateJson: String,
    val updatedAt: Long,
)
