package com.yunian.ai.domain.timeline

object TimelineEventFactory {

    fun streamingReasoning(
        turnId: TurnId,
        eventIndex: Int,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        anchorMessageId: Long? = null,
    ): TimelineEvent = TimelineEvent(
        turnId = turnId,
        kind = TimelineEventKind.REASONING,
        status = TimelineEventStatus.STREAMING,
        eventIndex = eventIndex,
        timestamp = timestamp,
        payload = ReasoningPayload(text = text, durationMs = null),
        anchorMessageId = anchorMessageId,
    )

    fun completeReasoning(
        turnId: TurnId,
        eventIndex: Int,
        text: String,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
        anchorMessageId: Long? = null,
        eventId: EventId? = null,
    ): TimelineEvent = TimelineEvent(
        eventId = eventId,
        turnId = turnId,
        kind = TimelineEventKind.REASONING,
        status = TimelineEventStatus.COMPLETE,
        eventIndex = eventIndex,
        timestamp = timestamp,
        payload = ReasoningPayload(text = text, durationMs = durationMs),
        anchorMessageId = anchorMessageId,
    )

    fun completeAssistantText(
        turnId: TurnId,
        eventIndex: Int,
        text: String,
        segmentIndex: Int = 0,
        timestamp: Long = System.currentTimeMillis(),
        anchorMessageId: Long? = null,
        eventId: EventId? = null,
    ): TimelineEvent = TimelineEvent(
        eventId = eventId,
        turnId = turnId,
        kind = TimelineEventKind.ASSISTANT_TEXT,
        status = TimelineEventStatus.COMPLETE,
        eventIndex = eventIndex,
        timestamp = timestamp,
        payload = AssistantTextPayload(text = text, segmentIndex = segmentIndex),
        anchorMessageId = anchorMessageId,
    )
}
