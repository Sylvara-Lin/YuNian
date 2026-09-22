package com.yunian.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "unified_memories",
    indices = [
        Index(value = ["deviceId", "scope", "sourceId"]),
        Index(value = ["deviceId", "memoryType"]),
        Index(value = ["deviceId", "observedAt"]),
        Index(value = ["deviceId", "importance"]),
        Index(value = ["isDeleted"]),
        Index(value = ["expiresAt"])
    ]
)
data class MemoryRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val memoryType: MemoryType = MemoryType.SEMANTIC,
    val scope: MemoryScope = MemoryScope.COMPANION,
    val source: MemorySource = MemorySource.CHAT,

    val content: String,
    @ColumnInfo(defaultValue = "")
    val summary: String = "",

    @ColumnInfo(defaultValue = "1.0")
    val confidence: Float = 1.0f,
    @ColumnInfo(defaultValue = "0.5")
    val importance: Float = 0.5f,

    @ColumnInfo(defaultValue = "0")
    val sourceId: Long = 0L,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val observedAt: Long = System.currentTimeMillis(),

    val expiresAt: Long? = null,
    val validFrom: Long? = null,
    val validTo: Long? = null,

    @ColumnInfo(defaultValue = "")
    val temporalAnchor: String = "",

    @ColumnInfo(defaultValue = "1")
    val accessCount: Int = 1,

    val tags: String = "",
    @ColumnInfo(defaultValue = "")
    val fuzzyHints: String = "",
    @ColumnInfo(defaultValue = "")
    val mergedFrom: String = "",

    @ColumnInfo(defaultValue = "0")
    val isDeleted: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val version: Int = 1,

    @ColumnInfo(defaultValue = "''")
    val deviceId: String = "",

    @ColumnInfo
    val embedding: ByteArray? = null,

    @ColumnInfo(defaultValue = "''")
    val embeddingModel: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
