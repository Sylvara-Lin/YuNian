package com.yunian.ai.domain.timeline

data class TimelineEvent(
    val eventId: EventId? = null,
    val turnId: TurnId,
    val kind: TimelineEventKind,
    val status: TimelineEventStatus,
    val eventIndex: Int,
    val timestamp: Long,
    val visibility: TimelineVisibility = TimelineVisibility.USER,
    val payload: TimelinePayload,

    val anchorMessageId: Long? = null,
) {
    init {
        require(eventIndex >= 0) { "eventIndex must be >= 0" }
        require(payload.kind == kind) {
            "payload.kind (${payload.kind}) must match event.kind ($kind)"
        }
    }
}
