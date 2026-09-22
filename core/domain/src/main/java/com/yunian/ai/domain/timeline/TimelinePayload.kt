package com.yunian.ai.domain.timeline

interface TimelinePayload {
    val kind: TimelineEventKind
}

data class ReasoningPayload(
    val text: String,
    val durationMs: Long? = null,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.REASONING
}

data class AssistantTextPayload(
    val text: String,
    val segmentIndex: Int = 0,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.ASSISTANT_TEXT
}

data class ToolCallPayload(
    val callId: String,
    val name: String,
    val argumentsJson: String,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.TOOL_CALL
}

data class ToolResultPayload(
    val callId: String,
    val name: String,
    val resultJson: String,
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.TOOL_RESULT
}

data class SystemEventPayload(
    val code: String,
    val dataJson: String = "",
) : TimelinePayload {
    override val kind: TimelineEventKind = TimelineEventKind.SYSTEM_EVENT
}
