package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wechat_outbox",
    indices = [
        Index(value = ["status", "nextAttemptAtMs"]),
        Index(value = ["wechatUserId", "status"]),
        Index(value = ["rootId"]),
        Index(value = ["companionId"]),
    ],
)
data class WeChatOutboxEntity(
    @PrimaryKey
    val id: String,
    val rootId: String,
    val companionId: Long,
    val wechatUserId: String,

    val kind: Int,
    val text: String? = null,
    val mediaLocalPath: String? = null,
    val mediaFileName: String? = null,
    val mediaDescription: String? = null,
    val segmentIndex: Int,
    val segmentCount: Int,
    val sourceMessageId: Long? = null,

    val status: String,
    val retryCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)
