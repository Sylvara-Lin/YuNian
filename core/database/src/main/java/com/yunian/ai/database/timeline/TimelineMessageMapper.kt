package com.yunian.ai.database.timeline

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageBody
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.StoredMessage
import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.EventId
import com.yunian.ai.domain.timeline.ReasoningPayload
import com.yunian.ai.domain.timeline.TimelineEvent
import com.yunian.ai.domain.timeline.TimelineEventKind
import com.yunian.ai.domain.timeline.TimelineEventStatus
import com.yunian.ai.domain.timeline.TimelinePayload
import com.yunian.ai.domain.timeline.TimelinePayloadCodecRegistry
import com.yunian.ai.domain.timeline.TimelineVisibility
import com.yunian.ai.domain.timeline.TurnId

object TimelineMessageMapper {

    fun toMessageType(kind: TimelineEventKind): MessageType = when (kind) {
        TimelineEventKind.REASONING -> MessageType.REASONING
        TimelineEventKind.ASSISTANT_TEXT -> MessageType.TEXT

        TimelineEventKind.TOOL_CALL,
        TimelineEventKind.TOOL_RESULT,
        TimelineEventKind.SYSTEM_EVENT -> MessageType.TEXT
    }

    fun toTimelineKind(type: MessageType, payloadHint: TimelinePayload? = null): TimelineEventKind? {
        return when (type) {
            MessageType.REASONING -> TimelineEventKind.REASONING
            MessageType.TEXT -> payloadHint?.kind ?: TimelineEventKind.ASSISTANT_TEXT
            else -> null
        }
    }

    fun serializePayload(payload: TimelinePayload): String {
        ensureBuiltins()
        return TimelinePayloadCodecRegistry.serialize(payload)
    }

    fun deserializePayload(kind: TimelineEventKind, raw: String, durationMs: Long?): TimelinePayload {
        ensureBuiltins()
        val base = TimelinePayloadCodecRegistry.deserialize(kind, raw)
        return when (base) {
            is ReasoningPayload -> base.copy(durationMs = durationMs ?: base.durationMs)
            else -> base
        }
    }

    fun toMetadata(
        scope: ConversationRef,
        event: TimelineEvent,
        messageId: Long = 0L,
    ): Message {
        require(event.status != TimelineEventStatus.STREAMING) {
            "STREAMING events must not be persisted"
        }
        return Message(
            id = messageId,
            conversationId = scope.conversationId,
            conversationType = scope.conversationType,
            isFromUser = false,
            senderId = scope.senderId,
            timestamp = event.timestamp,
            type = toMessageType(event.kind),
            fileFormat = FileFormat.TEXT,
            turnId = event.turnId.value,
            eventIndex = event.eventIndex,
            durationMs = (event.payload as? ReasoningPayload)?.durationMs,
            anchorMessageId = event.anchorMessageId,
        )
    }

    fun toBody(messageId: Long, event: TimelineEvent): MessageBody {
        val content = serializePayload(event.payload)

        val search = if (event.kind == TimelineEventKind.REASONING) "" else content
        return MessageBody(
            messageId = messageId,
            content = content,
            searchContent = search,
            linkString = "",
        )
    }

    fun toChatMessageShell(
        scope: ConversationRef,
        event: TimelineEvent,
        plaintextContent: String,
    ): ChatMessage = ChatMessage(
        id = event.eventId?.value ?: 0L,
        companionId = scope.conversationId,
        content = plaintextContent,
        isFromUser = false,
        timestamp = event.timestamp,
        type = toMessageType(event.kind),
        searchContent = if (event.kind == TimelineEventKind.REASONING) "" else plaintextContent,
        fileFormat = FileFormat.TEXT,
        linkString = "",
        turnId = event.turnId.value,
        eventIndex = event.eventIndex,
        durationMs = (event.payload as? ReasoningPayload)?.durationMs,
        anchorMessageId = event.anchorMessageId,
    )

    fun fromStored(stored: StoredMessage, decryptedContent: String): TimelineEvent? {
        val meta = stored.metadata
        val turn = meta.turnId?.takeIf { it.isNotBlank() } ?: return null
        val kind = when (meta.type) {
            MessageType.REASONING -> TimelineEventKind.REASONING
            MessageType.TEXT -> TimelineEventKind.ASSISTANT_TEXT
            else -> return null
        }

        if (kind == TimelineEventKind.ASSISTANT_TEXT && meta.eventIndex == null) {

        }
        val payload = deserializePayload(kind, decryptedContent, meta.durationMs)
        return TimelineEvent(
            eventId = if (meta.id > 0L) EventId(meta.id) else null,
            turnId = TurnId(turn),
            kind = kind,
            status = TimelineEventStatus.COMPLETE,
            eventIndex = meta.eventIndex ?: 0,
            timestamp = meta.timestamp,
            visibility = TimelineVisibility.USER,
            payload = payload,
            anchorMessageId = meta.anchorMessageId,
        )
    }

    private fun ensureBuiltins() {
        if (TimelinePayloadCodecRegistry.get(TimelineEventKind.REASONING) == null) {
            TimelinePayloadCodecRegistry.registerBuiltins()
        }
    }
}
