package com.yunian.ai.feature.chat.timeline

import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

class PendingTurnStreamApplier(
    private val throttle: StreamDeltaThrottle = StreamDeltaThrottle(),
) {
    data class Result(
        val assistantText: String,
        val reasoningText: String?,
        val reasoningDurationMs: Long?,
        val failedMessage: String?,
        val completed: Boolean,
    )

    suspend fun apply(
        events: Flow<AssistantStreamEvent>,
        turn: PendingTurn,
        projectLive: Boolean,
        onReasoningSnapshot: (String) -> Unit = {},
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): Result {
        var assistantText = ""
        var reasoningDurationMs: Long? = null
        var failedMessage: String? = null
        var completed = false
        val expectedTurn: TurnId = turn.turnId

        events.collect { event ->
            if (event.turnId != expectedTurn) return@collect
            when (event) {
                is AssistantStreamEvent.TurnStarted -> Unit

                is AssistantStreamEvent.ReasoningDelta -> {
                    if (event.delta.isEmpty() || turn.isReasoningComplete) return@collect
                    turn.appendReasoningDelta(event.delta)
                    val now = nowMs()
                    if (projectLive && throttle.shouldEmit(now, event.delta.length, force = false)) {
                        onReasoningSnapshot(turn.snapshotReasoningText())
                        throttle.markEmitted(now)
                    }
                }

                is AssistantStreamEvent.ReasoningCompleted -> {
                    reasoningDurationMs = event.durationMs
                    turn.completeReasoning(
                        finalText = event.fullText,
                        durationMs = event.durationMs,
                        timestamp = nowMs(),
                    )
                    if (projectLive && event.fullText.isNotBlank()) {
                        onReasoningSnapshot(event.fullText)
                        throttle.markEmitted(nowMs())
                    }
                }

                is AssistantStreamEvent.TextDelta -> {
                    if (event.delta.isEmpty()) return@collect
                    assistantText += event.delta
                }

                is AssistantStreamEvent.TextCompleted -> {
                    assistantText = event.fullText
                }

                is AssistantStreamEvent.TurnFailed -> {
                    failedMessage = event.message
                }

                is AssistantStreamEvent.TurnCompleted -> {
                    completed = true

                    if (projectLive) {
                        val snap = turn.snapshotReasoningText()
                        if (snap.isNotBlank()) {
                            onReasoningSnapshot(snap)
                            throttle.markEmitted(nowMs())
                        }
                    }
                }
            }
        }

        val reasoning = turn.snapshotReasoningText().trim().ifBlank { null }
        return Result(
            assistantText = assistantText,
            reasoningText = reasoning,
            reasoningDurationMs = reasoningDurationMs,
            failedMessage = failedMessage,
            completed = completed,
        )
    }
}
