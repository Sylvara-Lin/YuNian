package com.yunian.ai.network.stream

import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object NonStreamingAssistantStreamAdapter {

    fun fromCompleted(
        turnId: TurnId,
        reasoning: String?,
        content: String,
        startedAtMs: Long,
        completedAtMs: Long = System.currentTimeMillis(),
        anchorMessageId: Long? = null,
    ): Flow<AssistantStreamEvent> = flow {
        emit(AssistantStreamEvent.TurnStarted(turnId = turnId, anchorMessageId = anchorMessageId))

        val reasoningText = reasoning?.trim().orEmpty()
        if (reasoningText.isNotEmpty()) {
            val durationMs = (completedAtMs - startedAtMs).coerceAtLeast(1L)
            emit(
                AssistantStreamEvent.ReasoningCompleted(
                    turnId = turnId,
                    fullText = reasoningText,
                    durationMs = durationMs,
                )
            )
        }

        val text = content
        emit(
            AssistantStreamEvent.TextCompleted(
                turnId = turnId,
                fullText = text,
                segmentIndex = 0,
            )
        )
        emit(AssistantStreamEvent.TurnCompleted(turnId = turnId))
    }

    fun fromCompletedWithReasoningChunks(
        turnId: TurnId,
        reasoning: String?,
        content: String,
        startedAtMs: Long,
        completedAtMs: Long = System.currentTimeMillis(),
        chunkSize: Int = 24,
        anchorMessageId: Long? = null,
    ): Flow<AssistantStreamEvent> = flow {
        emit(AssistantStreamEvent.TurnStarted(turnId = turnId, anchorMessageId = anchorMessageId))

        val reasoningText = reasoning?.trim().orEmpty()
        if (reasoningText.isNotEmpty()) {
            var offset = 0
            while (offset < reasoningText.length) {
                val end = (offset + chunkSize).coerceAtMost(reasoningText.length)
                emit(
                    AssistantStreamEvent.ReasoningDelta(
                        turnId = turnId,
                        delta = reasoningText.substring(offset, end),
                    )
                )
                offset = end
            }
            val durationMs = (completedAtMs - startedAtMs).coerceAtLeast(1L)
            emit(
                AssistantStreamEvent.ReasoningCompleted(
                    turnId = turnId,
                    fullText = reasoningText,
                    durationMs = durationMs,
                )
            )
        }

        emit(
            AssistantStreamEvent.TextCompleted(
                turnId = turnId,
                fullText = content,
                segmentIndex = 0,
            )
        )
        emit(AssistantStreamEvent.TurnCompleted(turnId = turnId))
    }
}
