package com.yunian.ai.domain.stream

import com.yunian.ai.domain.timeline.TurnId

sealed interface AssistantStreamEvent {
    val turnId: TurnId

    data class TurnStarted(
        override val turnId: TurnId,
        val anchorMessageId: Long? = null,
    ) : AssistantStreamEvent

    data class ReasoningDelta(
        override val turnId: TurnId,
        val delta: String,
    ) : AssistantStreamEvent

    data class ReasoningCompleted(
        override val turnId: TurnId,
        val fullText: String,
        val durationMs: Long,
    ) : AssistantStreamEvent {
        init {
            require(durationMs >= 0L) { "durationMs must be >= 0" }
        }
    }

    data class TextDelta(
        override val turnId: TurnId,
        val delta: String,
    ) : AssistantStreamEvent

    data class TextCompleted(
        override val turnId: TurnId,
        val fullText: String,
        val segmentIndex: Int = 0,
    ) : AssistantStreamEvent {
        init {
            require(segmentIndex >= 0) { "segmentIndex must be >= 0" }
        }
    }

    data class TurnFailed(
        override val turnId: TurnId,
        val message: String,
    ) : AssistantStreamEvent

    data class TurnCompleted(
        override val turnId: TurnId,
    ) : AssistantStreamEvent
}
