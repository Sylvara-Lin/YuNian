package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.domain.timeline.ReasoningDurationFormatter

object ReasoningUiProjector {

    fun project(message: ChatMessage): ChatListItem.ReasoningMessage? {
        if (message.type != MessageType.REASONING) return null
        return ChatListItem.ReasoningMessage(
            message = message,
            durationMs = message.durationMs,
            isStreaming = message.id < 0L,
        )
    }

    fun collapsedLabel(message: ChatMessage): String =
        collapsedLabel(durationMs = message.durationMs, text = message.content)

    fun collapsedLabel(durationMs: Long?, text: String): String =
        ReasoningDurationFormatter.collapsedLabel(
            durationMs = durationMs,
            hasText = text.isNotBlank() || durationMs != null,
        )

    fun streamingLabel(): String = ReasoningDurationFormatter.STREAMING_LABEL
}
