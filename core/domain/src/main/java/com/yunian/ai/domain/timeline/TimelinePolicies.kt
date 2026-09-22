package com.yunian.ai.domain.timeline

data class ReasoningDisplayPrefs(
    val showReasoning: Boolean = false,
    val autoCollapse: Boolean = true,

    val sendReasoningToModel: Boolean = false,
)

fun interface ModelContextInclusionPolicy {
    fun include(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean
}

fun ModelContextInclusionPolicy.include(event: TimelineEvent): Boolean =
    include(event, ReasoningDisplayPrefs())

object DefaultModelContextPolicy : ModelContextInclusionPolicy {
    override fun include(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean {
        if (event.status != TimelineEventStatus.COMPLETE) return false
        return when (event.kind) {
            TimelineEventKind.ASSISTANT_TEXT -> true
            TimelineEventKind.REASONING -> prefs.sendReasoningToModel
            TimelineEventKind.TOOL_CALL, TimelineEventKind.TOOL_RESULT -> true
            TimelineEventKind.SYSTEM_EVENT -> false
        }
    }
}

fun interface UserVisibilityPolicy {
    fun visible(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean
}

object DefaultUserVisibilityPolicy : UserVisibilityPolicy {
    override fun visible(event: TimelineEvent, prefs: ReasoningDisplayPrefs): Boolean {
        if (event.visibility == TimelineVisibility.INTERNAL) return false
        if (event.visibility == TimelineVisibility.DEBUG) return false
        return when (event.kind) {
            TimelineEventKind.REASONING -> prefs.showReasoning &&
                (event.status == TimelineEventStatus.STREAMING ||
                    event.status == TimelineEventStatus.COMPLETE)
            TimelineEventKind.ASSISTANT_TEXT ->
                event.status == TimelineEventStatus.STREAMING ||
                    event.status == TimelineEventStatus.COMPLETE
            TimelineEventKind.TOOL_CALL, TimelineEventKind.TOOL_RESULT ->
                event.status == TimelineEventStatus.COMPLETE
            TimelineEventKind.SYSTEM_EVENT ->
                event.status == TimelineEventStatus.COMPLETE
        }
    }
}

object ReasoningDurationFormatter {
    fun secondsForDisplay(durationMs: Long?, hasText: Boolean): Int {
        if (!hasText) return 0
        val ms = durationMs ?: return 1
        if (ms <= 0L) return 1
        return ((ms + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    fun collapsedLabel(durationMs: Long?, hasText: Boolean): String {
        val n = secondsForDisplay(durationMs, hasText)
        return if (n <= 0) "已思考" else "已思考${n}秒"
    }

    const val STREAMING_LABEL = "思考中…"
}
