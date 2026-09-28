package com.yunian.ai.database.timeline

import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageBody
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.StoredMessage
import com.yunian.ai.domain.timeline.AssistantTextPayload
import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.ReasoningPayload
import com.yunian.ai.domain.timeline.TimelineEvent
import com.yunian.ai.domain.timeline.TimelineEventFactory
import com.yunian.ai.domain.timeline.TimelineEventKind
import com.yunian.ai.domain.timeline.TimelineEventStatus
import com.yunian.ai.domain.timeline.TimelinePayloadCodecRegistry
import com.yunian.ai.domain.timeline.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineMessageMapperTest {

    @Before
    fun setUp() {
        TimelinePayloadCodecRegistry.clear()
        TimelinePayloadCodecRegistry.registerBuiltins()
    }

    @Test
    fun completeReasoning_roundTrip_preservesDuration() {
        val turn = TurnId("turn-1")
        val event = TimelineEventFactory.completeReasoning(
            turnId = turn,
            eventIndex = 0,
            text = "先分析用户意图",
            durationMs = 3200L,
            timestamp = 1000L,
            anchorMessageId = 42L,
        )
        val scope = ConversationRef(7L, "chat")
        val meta = TimelineMessageMapper.toMetadata(scope, event)
        val body = TimelineMessageMapper.toBody(0L, event)

        assertEquals(MessageType.REASONING, meta.type)
        assertEquals("turn-1", meta.turnId)
        assertEquals(0, meta.eventIndex)
        assertEquals(3200L, meta.durationMs)
        assertEquals(42L, meta.anchorMessageId)
        assertEquals("先分析用户意图", body.content)
        assertEquals("", body.searchContent)

        val restored = TimelineMessageMapper.fromStored(
            StoredMessage(meta.copy(id = 9L), body.copy(messageId = 9L)),
            decryptedContent = body.content,
        )
        requireNotNull(restored)
        assertEquals(TimelineEventKind.REASONING, restored.kind)
        assertEquals(TimelineEventStatus.COMPLETE, restored.status)
        val payload = restored.payload as ReasoningPayload
        assertEquals("先分析用户意图", payload.text)
        assertEquals(3200L, payload.durationMs)
        assertEquals(9L, restored.eventId?.value)
    }

    @Test
    fun assistantText_mapsToTextType() {
        val event = TimelineEventFactory.completeAssistantText(
            turnId = TurnId("t2"),
            eventIndex = 1,
            text = "你好",
        )
        val meta = TimelineMessageMapper.toMetadata(ConversationRef(1L, "chat"), event)
        assertEquals(MessageType.TEXT, meta.type)
        assertEquals(1, meta.eventIndex)
        val payload = TimelineMessageMapper.deserializePayload(
            TimelineEventKind.ASSISTANT_TEXT,
            TimelineMessageMapper.serializePayload(event.payload),
            durationMs = null,
        ) as AssistantTextPayload
        assertEquals("你好", payload.text)
    }

    @Test
    fun streaming_rejected_by_toMetadata() {
        val streaming = TimelineEvent(
            turnId = TurnId("t3"),
            kind = TimelineEventKind.REASONING,
            status = TimelineEventStatus.STREAMING,
            eventIndex = 0,
            timestamp = 1L,
            payload = ReasoningPayload("partial"),
        )
        try {
            TimelineMessageMapper.toMetadata(ConversationRef(1L, "chat"), streaming)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("STREAMING"))
        }
    }

    @Test
    fun withoutTurnId_notProjected() {
        val stored = StoredMessage(
            metadata = Message(
                id = 1L,
                conversationId = 1L,
                conversationType = "chat",
                isFromUser = false,
                type = MessageType.TEXT,
                turnId = null,
            ),
            body = MessageBody(1L, "hi"),
        )
        assertNull(TimelineMessageMapper.fromStored(stored, "hi"))
    }
}
