package com.yunian.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "diary_entries",
    indices = [
        Index(value = ["companionId", "deviceId"]),
        Index(value = ["date"]),
        Index(value = ["deviceId"])
    ]
)
@Serializable
@SerialName("E7")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val companionId: Long,
    val title: String = "",
    val content: String,
    val mood: Int = 2,
    val date: Long = System.currentTimeMillis(),
    val weather: String = "",
    val tags: String = "",
    @ColumnInfo(defaultValue = "''")
    val deviceId: String = ""
)
