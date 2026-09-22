package com.yunian.ai.feature.chat.timeline

import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.TimelineEvent
import com.yunian.ai.domain.timeline.TimelineEventFactory
import com.yunian.ai.domain.timeline.TurnEventIndexer
import com.yunian.ai.domain.timeline.TurnId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PendingTurn private constructor(
    val turnId: TurnId,
    val conversation: ConversationRef,
    val anchorMessageId: Long?,
    val startedAtMs: Long,
    private val indexer: TurnEventIndexer,
) {
    private val lock = Any()
    private val reasoningBuffer = StringBuilder()
    private val reasoningCompleted = AtomicBoolean(false)
    private val completedReasoningEvent = AtomicReference<TimelineEvent?>(null)
    private val reasoningCommitTaken = AtomicBoolean(false)
    private var assistantSegmentCount = 0

    val isReasoningComplete: Boolean get() = reasoningCompleted.get()

    fun appendReasoningDelta(delta: String) {
        if (delta.isEmpty() || reasoningCompleted.get()) return
        synchronized(lock) {
            if (reasoningCompleted.get()) return
            reasoningBuffer.append(delta)
        }
    }

    fun replaceReasoningText(text: String) {
        if (reasoningCompleted.get()) return
        synchronized(lock) {
            if (reasoningCompleted.get()) return
            reasoningBuffer.setLength(0)
            reasoningBuffer.append(text)
        }
    }

    fun snapshotReasoningText(): String = synchronized(lock) { reasoningBuffer.toString() }

    fun streamingReasoningEvent(): TimelineEvent? {
        if (reasoningCompleted.get()) return null
        val text = snapshotReasoningText()
        if (text.isBlank()) return null

        return TimelineEventFactory.streamingReasoning(
            turnId = turnId,
            eventIndex = indexer.peek(turnId),
            text = text,
            timestamp = System.currentTimeMillis(),
            anchorMessageId = anchorMessageId,
        )
    }

    fun completeReasoning(
        finalText: String? = null,
        durationMs: Long?,
        timestamp: Long = System.currentTimeMillis(),
    ): TimelineEvent? {
        completedReasoningEvent.get()?.let { return it }
        val text = synchronized(lock) {
            if (finalText != null) {
                reasoningBuffer.setLength(0)
                reasoningBuffer.append(finalText)
            }
            reasoningBuffer.toString()
        }
        if (text.isBlank()) {
            reasoningCompleted.set(true)
            return null
        }
        val event = TimelineEventFactory.completeReasoning(
            turnId = turnId,
            eventIndex = indexer.next(turnId),
            text = text,
            durationMs = durationMs ?: EventCommitRules.durationMs(startedAtMs, timestamp) ?: 1L,
            timestamp = timestamp,
            anchorMessageId = anchorMessageId,
        )
        if (completedReasoningEvent.compareAndSet(null, event)) {
            reasoningCompleted.set(true)
            return event
        }
        return completedReasoningEvent.get()
    }

    fun takeReasoningEventForCommit(): TimelineEvent? {
        val event = completedReasoningEvent.get() ?: return null
        return if (reasoningCommitTaken.compareAndSet(false, true)) event else null
    }

    fun nextAssistantTextEvent(
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ): TimelineEvent {
        val segmentIndex = assistantSegmentCount++
        return TimelineEventFactory.completeAssistantText(
            turnId = turnId,
            eventIndex = indexer.next(turnId),
            text = text,
            segmentIndex = segmentIndex,
            timestamp = timestamp,
            anchorMessageId = anchorMessageId,
        )
    }

    fun releaseIndexer() {
        indexer.reset(turnId)
    }

    companion object {
        fun start(
            conversation: ConversationRef,
            anchorMessageId: Long? = null,
            startedAtMs: Long = System.currentTimeMillis(),
            turnId: TurnId = TurnId(UUID.randomUUID().toString()),
            indexer: TurnEventIndexer = TurnEventIndexer(),
        ): PendingTurn = PendingTurn(
            turnId = turnId,
            conversation = conversation,
            anchorMessageId = anchorMessageId,
            startedAtMs = startedAtMs,
            indexer = indexer,
        )
    }
}
