package com.yunian.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Fts4
@Entity(tableName = "message_search_index")
data class MessageSearchIndex(
    @ColumnInfo(name = "rowid")
    @PrimaryKey
    val messageId: Long,
    val tokens: String
)