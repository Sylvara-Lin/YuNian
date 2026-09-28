package com.yunian.ai.feature.chat.timeline

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.TimelineEvent
import com.yunian.ai.domain.timeline.TimelineEventKind
import com.yunian.ai.domain.timeline.TimelineStore

class TurnCommitCoordinator(
    private val timelineStore: TimelineStore,
    private val messageWriter: MessageWriteCoordinator,
) {

    suspend fun commitReasoning(scope: ConversationRef, event: TimelineEvent): Long {
        require(event.kind == TimelineEventKind.REASONING) {
            "commitReasoning expects REASONING, got ${event.kind}"
        }
        return timelineStore.appendComplete(scope, event).also { id ->
            SecureLog.d(TAG, "REASONING committed id=$id turn=${event.turnId} idx=${event.eventIndex}")
        }
    }

    suspend fun commitAssistantText(
        companionId: Long,
        text: String,
        turn: PendingTurn,
        timestamp: Long = System.currentTimeMillis(),
        audioPath: String? = null,
        durationMs: Long? = null,
        /**
         * 透传给 [MessageWriteCoordinator.enqueueChat]，在**该消息确实写入数据库之后**由
         * 持久化侧的不可取消消费协程回调；与调用方协程是否被取消无关。
         */
        onPersisted: (() -> Unit)? = null,
    ): Long {
        val event = turn.nextAssistantTextEvent(text = text, timestamp = timestamp)
        val hasVoiceBar = !audioPath.isNullOrBlank()
        val message = ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = false,
            timestamp = event.timestamp,
            type = if (hasVoiceBar) MessageType.VOICE else MessageType.TEXT,
            fileFormat = if (hasVoiceBar) FileFormat.AUDIO else FileFormat.TEXT,
            linkString = audioPath.orEmpty(),
            turnId = event.turnId.value,
            eventIndex = event.eventIndex,
            durationMs = durationMs,
            anchorMessageId = event.anchorMessageId,
        )
        return messageWriter.enqueueChat(message, onPersisted).also { id ->
            SecureLog.d(
                TAG,
                "ASSISTANT_${if (hasVoiceBar) "VOICE_BAR" else "TEXT"} committed id=$id turn=${event.turnId} idx=${event.eventIndex}"
            )
        }
    }

    suspend fun commitPlainAssistantText(
        companionId: Long,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        val message = ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = false,
            timestamp = timestamp,
            type = MessageType.TEXT,
        )
        return messageWriter.enqueueChat(message)
    }

    companion object {
        private const val TAG = "TurnCommitCoordinator"
    }
}
