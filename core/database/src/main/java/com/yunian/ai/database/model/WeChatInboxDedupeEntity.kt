package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wechat_inbox_dedupe",
    indices = [
        Index(value = ["processedAtMs"]),
        Index(value = ["fromUserId"]),
    ],
)
data class WeChatInboxDedupeEntity(
    @PrimaryKey
    val dedupeKey: String,
    val messageId: Long? = null,
    val fromUserId: String,
    val processedAtMs: Long = System.currentTimeMillis(),
)
