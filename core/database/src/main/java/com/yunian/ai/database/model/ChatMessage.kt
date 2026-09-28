package com.yunian.ai.database.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("E0")
data class ChatMessage(
    val id: Long = 0,
    val companionId: Long,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val searchContent: String = "",
    val fileFormat: FileFormat = FileFormat.TEXT,
    val linkString: String = "",
    val turnId: String? = null,
    val eventIndex: Int? = null,
    val durationMs: Long? = null,
    val anchorMessageId: Long? = null,
) {
    val role: String get() = if (isFromUser) "user" else "assistant"
    val isFromAssistant: Boolean get() = !isFromUser
    val isSystem: Boolean get() = false
}
