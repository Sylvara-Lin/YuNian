package com.yunian.ai.network.stream

import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonStreamingAssistantStreamAdapterTest {

    @Test
    fun fromCompleted_emitsReasoningThenTextInOrder() = runBlocking {
        val turnId = TurnId("turn-1")
        val events = NonStreamingAssistantStreamAdapter.fromCompleted(
            turnId = turnId,
            reasoning = "  think hard  ",
            content = "hello",
            startedAtMs = 1_000L,
            completedAtMs = 1_350L,
        ).toList()

        assertTrue(events.first() is AssistantStreamEvent.TurnStarted)
        val reasoning = events.filterIsInstance<AssistantStreamEvent.ReasoningCompleted>().single()
        assertEquals("think hard", reasoning.fullText)
        assertEquals(350L, reasoning.durationMs)
        val text = events.filterIsInstance<AssistantStreamEvent.TextCompleted>().single()
        assertEquals("hello", text.fullText)
        assertTrue(events.last() is AssistantStreamEvent.TurnCompleted)
    }

    @Test
    fun fromCompleted_skipsReasoningWhenBlank() = runBlocking {
        val events = NonStreamingAssistantStreamAdapter.fromCompleted(
            turnId = TurnId("t2"),
            reasoning = "   ",
            content = "only text",
            startedAtMs = 0L,
            completedAtMs = 10L,
        ).toList()

        assertTrue(events.none { it is AssistantStreamEvent.ReasoningCompleted })
        assertTrue(events.none { it is AssistantStreamEvent.ReasoningDelta })
        assertEquals(
            "only text",
            events.filterIsInstance<AssistantStreamEvent.TextCompleted>().single().fullText,
        )
    }

    @Test
    fun fromCompletedWithReasoningChunks_emitsDeltasThenCompleted() = runBlocking {
        val events = NonStreamingAssistantStreamAdapter.fromCompletedWithReasoningChunks(
            turnId = TurnId("t3"),
            reasoning = "abcdefghij",
            content = "ok",
            startedAtMs = 0L,
            completedAtMs = 5L,
            chunkSize = 4,
        ).toList()

        val deltas = events.filterIsInstance<AssistantStreamEvent.ReasoningDelta>()
        assertEquals(listOf("abcd", "efgh", "ij"), deltas.map { it.delta })
        assertEquals(
            "abcdefghij",
            events.filterIsInstance<AssistantStreamEvent.ReasoningCompleted>().single().fullText,
        )
    }
}
