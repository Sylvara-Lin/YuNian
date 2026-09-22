package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(
            value = ["conversationType", "conversationId", "timestamp", "id"],
            name = "idx_messages_conv",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]
        ),
        Index(
            value = ["turnId", "eventIndex", "id"],
            name = "idx_messages_turn",
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC]
        )
    ]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val conversationId: Long,

    val conversationType: String,

    val isFromUser: Boolean = true,

    val senderId: Long = 0,

    val timestamp: Long = System.currentTimeMillis(),

    val type: MessageType = MessageType.TEXT,

    val fileFormat: FileFormat = FileFormat.TEXT,

    val turnId: String? = null,

    val eventIndex: Int? = null,

    val durationMs: Long? = null,

    val anchorMessageId: Long? = null,
)

val Message.companionId: Long
    get() = if (conversationType == "chat") conversationId else senderId

val Message.groupId: Long
    get() = if (conversationType == "group") conversationId else 0

val Message.isFromAssistant: Boolean get() = !isFromUser
val Message.isSystem: Boolean get() = false
val Message.role: String get() = if (isFromUser) "user" else "assistant"
