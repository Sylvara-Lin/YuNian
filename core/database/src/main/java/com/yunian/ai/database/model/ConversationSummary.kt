package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "conversation_summary",
    primaryKeys = ["sessionId", "sessionType"],
    indices = [
        Index(value = ["sessionType", "lastMessageTimestamp"], name = "idx_summary_type_time")
    ]
)
data class ConversationSummary(
    val sessionId: Long,
    val sessionType: String,
    val lastMessageId: Long? = null,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val lastMessageIsFromUser: Boolean,
    val readThroughMessageTimestamp: Long? = null,
    val readThroughMessageId: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false
)
