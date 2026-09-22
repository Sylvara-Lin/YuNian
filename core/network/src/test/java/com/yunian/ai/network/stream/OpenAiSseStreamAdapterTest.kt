package com.yunian.ai.network.stream

import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSseStreamAdapterTest {

    @Test
    fun fromSseLines_emitsDeltasThenCompleted() = runBlocking {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"reasoning_content":"ab"}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"cd"}}]}""",
            """data: {"choices":[{"delta":{"content":"he"}}]}""",
            """data: {"choices":[{"delta":{"content":"llo"}}]}""",
            "data: [DONE]",
        )
        val events = OpenAiSseStreamAdapter.fromSseLines(
            turnId = TurnId("s1"),
            lines = lines,
            startedAtMs = 1_000L,
            completedAtMs = { 1_500L },
        ).toList()

        assertTrue(events.first() is AssistantStreamEvent.TurnStarted)
        assertEquals(
            listOf("ab", "cd"),
            events.filterIsInstance<AssistantStreamEvent.ReasoningDelta>().map { it.delta },
        )
        assertEquals(
            listOf("he", "llo"),
            events.filterIsInstance<AssistantStreamEvent.TextDelta>().map { it.delta },
        )
        val reasoning = events.filterIsInstance<AssistantStreamEvent.ReasoningCompleted>().single()
        assertEquals("abcd", reasoning.fullText)
        assertEquals(500L, reasoning.durationMs)
        assertEquals(
            "hello",
            events.filterIsInstance<AssistantStreamEvent.TextCompleted>().single().fullText,
        )
        assertTrue(events.last() is AssistantStreamEvent.TurnCompleted)
    }

    @Test
    fun fromSseLines_extractsThinkTagsAtEnd() = runBlocking {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"<think>secret</think>hi"}}]}""",
            "data: [DONE]",
        )
        val events = OpenAiSseStreamAdapter.fromSseLines(
            turnId = TurnId("s2"),
            lines = lines,
            startedAtMs = 0L,
            completedAtMs = { 10L },
        ).toList()

        assertEquals(
            "secret",
            events.filterIsInstance<AssistantStreamEvent.ReasoningCompleted>().single().fullText,
        )
        assertEquals(
            "hi",
            events.filterIsInstance<AssistantStreamEvent.TextCompleted>().single().fullText,
        )
    }

    @Test
    fun fromSseLines_unclosedThinkBecomesReasoningOnlyFailureOrEmptyBody() = runBlocking {
        val lines = sequenceOf(
            """data: {"choices":[{"delta":{"content":"<think>only reasoning no close"}}]}""",
            "data: [DONE]",
        )
        val events = OpenAiSseStreamAdapter.fromSseLines(
            turnId = TurnId("s2u"),
            lines = lines,
            startedAtMs = 0L,
            completedAtMs = { 10L },
        ).toList()

        val reasoning = events.filterIsInstance<AssistantStreamEvent.ReasoningCompleted>().singleOrNull()
        assertTrue(reasoning == null || reasoning.fullText.contains("only reasoning"))

        val failed = events.filterIsInstance<AssistantStreamEvent.TurnFailed>().single()
        assertTrue(failed.message.contains("思考过程") || failed.message.contains("实际回复"))
        assertTrue(events.none { it is AssistantStreamEvent.TextCompleted })
    }

    @Test
    fun fromSseLines_errorEmitsTurnFailed() = runBlocking {
        val lines = sequenceOf(
            """data: {"error":{"message":"bad key"}}""",
        )
        val events = OpenAiSseStreamAdapter.fromSseLines(
            turnId = TurnId("s3"),
            lines = lines,
            startedAtMs = 0L,
        ).toList()

        val failed = events.filterIsInstance<AssistantStreamEvent.TurnFailed>().single()
        assertEquals("bad key", failed.message)
        assertTrue(events.none { it is AssistantStreamEvent.TurnCompleted })
    }
}
