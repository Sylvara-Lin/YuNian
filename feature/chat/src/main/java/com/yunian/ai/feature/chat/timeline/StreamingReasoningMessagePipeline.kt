package com.yunian.ai.feature.chat.timeline

import com.yunian.ai.database.cache.MessageCache
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.domain.timeline.TurnId

object StreamingReasoningMessagePipeline {

    fun isStreamingMessageId(messageId: Long): Boolean = messageId < 0L

    fun streamingMessageId(turnId: TurnId): Long {
        val h = turnId.value.hashCode().toLong() and 0x7fff_ffffL
        return -(1_000_000_000L + h)
    }

    fun streamingMessageId(turnId: String): Long =
        streamingMessageId(TurnId(turnId))

    fun upsertStreaming(
        companionId: Long,
        turnId: TurnId,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        eventIndex: Int? = null,
        anchorMessageId: Long? = null,
    ): ChatMessage? {
        val content = text.trim()
        if (content.isEmpty()) return null
        val id = streamingMessageId(turnId)

        val existing = MessageCache.getChatMessages(companionId)?.firstOrNull { it.id == id }
        val stableTs = existing?.timestamp ?: timestamp
        val message = ChatMessage(
            id = id,
            companionId = companionId,
            content = content,
            isFromUser = false,
            timestamp = stableTs,
            type = MessageType.REASONING,
            searchContent = "",
            fileFormat = FileFormat.TEXT,
            linkString = "",
            turnId = turnId.value,
            eventIndex = eventIndex,
            durationMs = null,
            anchorMessageId = anchorMessageId,
        )

        MessageCache.appendChatMessage(companionId, message)

        val cached = MessageCache.getChatMessages(companionId)
        if (cached == null || cached.none { it.id == id }) {
            val seed = (cached.orEmpty() + message).distinctBy { it.id }
            MessageCache.putChatMessages(companionId, seed)
        }
        return message
    }

    fun removeStreaming(companionId: Long, turnId: TurnId) {
        MessageCache.removeChatMessage(companionId, streamingMessageId(turnId))
    }

    fun removeStreaming(companionId: Long, turnId: String) {
        removeStreaming(companionId, TurnId(turnId))
    }

    fun clearAllStreaming(companionId: Long) {
        val cached = MessageCache.getChatMessages(companionId) ?: return
        cached.filter { isStreamingMessageId(it.id) && it.type == MessageType.REASONING }
            .forEach { MessageCache.removeChatMessage(companionId, it.id) }
    }
}
