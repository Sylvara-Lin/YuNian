package com.yunian.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "lorebooks",
    indices = [
        Index(value = ["companionId"]),
        Index(value = ["enabled"])
    ]
)
@Serializable
data class LorebookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    @ColumnInfo(defaultValue = "")
    val description: String = "",

    val companionId: Long? = null,

    @ColumnInfo(defaultValue = "1")
    val enabled: Int = 1,

    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    fun isEnabled(): Boolean = enabled == 1
}
