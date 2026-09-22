package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "archived_messages",
    indices = [
        Index(
            value = ["conversationType", "conversationId", "timestamp", "id"],
            name = "idx_archived_messages_conv",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]
        ),
        Index(
            value = ["turnId", "eventIndex", "id"],
            name = "idx_archived_messages_turn",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC]
        )
    ]
)
data class ArchivedMessage(
    @PrimaryKey val id: Long,
    val conversationId: Long,
    val conversationType: String,
    val isFromUser: Boolean,
    val senderId: Long,
    val timestamp: Long,
    val type: MessageType,
    val fileFormat: FileFormat,
    val turnId: String? = null,
    val eventIndex: Int? = null,
    val durationMs: Long? = null,
    val anchorMessageId: Long? = null,
)