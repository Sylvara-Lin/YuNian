package com.yunian.ai.domain.timeline

enum class TimelineEventKind {

    REASONING,

    ASSISTANT_TEXT,

    TOOL_CALL,

    TOOL_RESULT,

    SYSTEM_EVENT,
}

enum class TimelineEventStatus {
    STREAMING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

enum class TimelineVisibility {

    USER,

    DEBUG,

    INTERNAL,
}
