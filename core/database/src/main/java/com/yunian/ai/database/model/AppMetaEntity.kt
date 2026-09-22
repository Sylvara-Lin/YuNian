package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
