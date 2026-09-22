package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yunian.ai.domain.delegation.DelegationRecord
import com.yunian.ai.domain.delegation.DelegationStatus

/** 委派记录（Room delegation_records）。 */
@Entity(
    tableName = "delegation_records",
    indices = [
        Index(value = ["status"]),
        Index(value = ["companionId"]),
    ],
)
data class DelegationRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val role: String = "",
    val prompt: String = "",
    val status: String = DelegationStatus.PENDING.name,
    val result: String = "",
    val error: String = "",
    val companionId: Long? = null,
    val dispatchId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
) {
    fun toDomain(): DelegationRecord = DelegationRecord(
        id = id,
        role = role,
        prompt = prompt,
        status = runCatching { DelegationStatus.valueOf(status) }.getOrDefault(DelegationStatus.PENDING),
        result = result,
        error = error,
        companionId = companionId,
        dispatchId = dispatchId,
        createdAtMs = createdAtMs,
        completedAtMs = completedAtMs,
    )
}
