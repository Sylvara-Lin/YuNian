package com.yunian.ai.feature.chat.timeline

object EventCommitRules {

    fun shouldPersistReasoning(text: String?): Boolean =
        !text.isNullOrBlank()

    fun shouldProjectReasoningLive(showReasoningSetting: Boolean, text: String?): Boolean =
        showReasoningSetting && !text.isNullOrBlank()

    fun durationMs(startedAtMs: Long?, completedAtMs: Long = System.currentTimeMillis()): Long? {
        if (startedAtMs == null || startedAtMs <= 0L) return null
        val delta = completedAtMs - startedAtMs
        return if (delta <= 0L) 1L else delta
    }

    fun shouldAttachTurnMetadata(turn: PendingTurn?): Boolean = turn != null
}
