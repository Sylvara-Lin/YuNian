package com.yunian.ai.feature.chat.timeline

import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTurnStreamApplierTest {

    @Test
    fun apply_reasoningDeltasThenComplete_fillsPendingTurn() = runBlocking {
        val turn = PendingTurn.start(
            conversation = ConversationRef(1L, "chat"),
            startedAtMs = 1000L,
            turnId = TurnId("applier-1"),
        )
        val applier = PendingTurnStreamApplier(
            throttle = StreamDeltaThrottle(intervalMs = 0L, lengthThreshold = 1),
        )
        val snapshots = mutableListOf<String>()
        val result = applier.apply(
            events = flowOf(
                AssistantStreamEvent.TurnStarted(turn.turnId),
                AssistantStreamEvent.ReasoningDelta(turn.turnId, "ab"),
                AssistantStreamEvent.ReasoningDelta(turn.turnId, "cd"),
                AssistantStreamEvent.ReasoningCompleted(turn.turnId, "abcd", durationMs = 42L),
                AssistantStreamEvent.TextCompleted(turn.turnId, "reply"),
                AssistantStreamEvent.TurnCompleted(turn.turnId),
            ),
            turn = turn,
            projectLive = true,
            onReasoningSnapshot = { snapshots.add(it) },
            nowMs = { 2000L },
        )

        assertEquals("abcd", result.reasoningText)
        assertEquals(42L, result.reasoningDurationMs)
        assertEquals("reply", result.assistantText)
        assertTrue(result.completed)
        assertTrue(turn.isReasoningComplete)
        assertTrue(snapshots.isNotEmpty())
        assertEquals("abcd", snapshots.last())
    }

    @Test
    fun apply_ignoresOtherTurnEvents() = runBlocking {
        val turn = PendingTurn.start(
            conversation = ConversationRef(1L, "chat"),
            turnId = TurnId("mine"),
        )
        val other = TurnId("other")
        val result = PendingTurnStreamApplier().apply(
            events = flowOf(
                AssistantStreamEvent.ReasoningDelta(other, "xxx"),
                AssistantStreamEvent.TextCompleted(other, "nope"),
                AssistantStreamEvent.TextCompleted(turn.turnId, "yes"),
                AssistantStreamEvent.TurnCompleted(turn.turnId),
            ),
            turn = turn,
            projectLive = false,
        )
        assertEquals("yes", result.assistantText)
        assertEquals(null, result.reasoningText)
    }
}
