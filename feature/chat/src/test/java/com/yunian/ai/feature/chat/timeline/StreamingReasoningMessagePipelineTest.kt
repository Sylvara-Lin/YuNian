package com.yunian.ai.feature.chat.timeline

import com.yunian.ai.domain.timeline.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingReasoningMessagePipelineTest {

    @Test
    fun streamingMessageId_isStableAndNegative() {
        val turn = TurnId("turn-abc-123")
        val a = StreamingReasoningMessagePipeline.streamingMessageId(turn)
        val b = StreamingReasoningMessagePipeline.streamingMessageId(turn)
        assertEquals(a, b)
        assertTrue(StreamingReasoningMessagePipeline.isStreamingMessageId(a))
        assertTrue(a < 0L)
    }

    @Test
    fun streamingMessageId_differsByTurn() {
        val a = StreamingReasoningMessagePipeline.streamingMessageId(TurnId("a"))
        val b = StreamingReasoningMessagePipeline.streamingMessageId(TurnId("b"))
        assertNotEquals(a, b)
    }

    @Test
    fun roomIds_areNotStreaming() {
        assertFalse(StreamingReasoningMessagePipeline.isStreamingMessageId(0L))
        assertFalse(StreamingReasoningMessagePipeline.isStreamingMessageId(1L))
        assertFalse(StreamingReasoningMessagePipeline.isStreamingMessageId(42L))
    }
}
