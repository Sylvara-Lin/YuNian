package com.yunian.ai.database.timeline

import androidx.room.withTransaction
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.MessageCache
import com.yunian.ai.database.dao.MessageDao
import com.yunian.ai.database.model.MessageBody
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.StoredMessage
import com.yunian.ai.database.repository.ChatMessageCrypto
import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.TimelineEvent
import com.yunian.ai.domain.timeline.TimelineEventKind
import com.yunian.ai.domain.timeline.TimelineEventStatus
import com.yunian.ai.domain.timeline.TimelineStore
import com.yunian.ai.domain.timeline.TurnId

class RoomTimelineStore(
    private val messageDao: MessageDao,
    private val database: AppDatabase,
) : TimelineStore {

    override suspend fun appendComplete(scope: ConversationRef, event: TimelineEvent): Long {
        require(event.status != TimelineEventStatus.STREAMING) {
            "STREAMING timeline events must not be persisted"
        }
        require(event.status == TimelineEventStatus.COMPLETE ||
            event.status == TimelineEventStatus.FAILED ||
            event.status == TimelineEventStatus.CANCELLED
        ) {
            "Only terminal statuses may be appended"
        }

        val plaintext = TimelineMessageMapper.serializePayload(event.payload)
        val shell = TimelineMessageMapper.toChatMessageShell(scope, event, plaintext)
        val encrypted = ChatMessageCrypto.encryptForStorage(shell)
        val metadata = TimelineMessageMapper.toMetadata(scope, event, messageId = 0L)

        val searchContent = if (event.kind == TimelineEventKind.REASONING) {
            ""
        } else {
            encrypted.searchContent
        }
        val body = MessageBody(
            messageId = 0L,
            content = encrypted.content,
            searchContent = searchContent,
            linkString = encrypted.linkString,
        )

        val id = database.withTransaction {
            messageDao.insertStoredMessage(metadata, body)
        }

        if (scope.conversationType == "chat" && id > 0L) {
            MessageCache.appendChatMessage(scope.conversationId, shell.copy(id = id))
        }
        return id
    }

    override suspend fun loadTurn(turnId: TurnId): List<TimelineEvent> {
        val hot = messageDao.getMessagesByTurnId(turnId.value)
        val coldMeta = messageDao.getArchivedMessageMetadataByTurnId(turnId.value)
        val cold = attachArchivedBodies(coldMeta)
        return decryptAndMap(hot + cold)
            .sortedWith(compareBy<TimelineEvent> { it.eventIndex }.thenBy { it.eventId?.value ?: 0L })
    }

    override suspend fun loadConversationEvents(
        conversationId: Long,
        conversationType: String,
        limit: Int,
        kinds: Set<TimelineEventKind>?,
    ): List<TimelineEvent> {
        require(limit > 0) { "limit must be > 0" }
        val types = (kinds ?: TimelineEventKind.entries.toSet())
            .map { TimelineMessageMapper.toMessageType(it) }
            .distinct()
        if (types.isEmpty()) return emptyList()

        val hot = messageDao.getRecentMessagesByTypes(
            conversationId, conversationType, types, limit
        )
        val coldMeta = messageDao.getRecentArchivedMessageMetadataByTypes(
            conversationId, conversationType, types, limit
        )
        val cold = attachArchivedBodies(coldMeta)

        val merged = (hot + cold)
            .sortedWith(
                compareByDescending<StoredMessage> { it.metadata.timestamp }
                    .thenByDescending { it.metadata.id }
            )
            .take(limit)

        return decryptAndMap(merged)
            .sortedWith(
                compareBy<TimelineEvent> { it.timestamp }
                    .thenBy { it.eventIndex }
                    .thenBy { it.eventId?.value ?: 0L }
            )
    }

    private suspend fun attachArchivedBodies(metadata: List<com.yunian.ai.database.model.Message>): List<StoredMessage> {
        if (metadata.isEmpty()) return emptyList()
        val bodies = messageDao.getArchivedMessageBodies(metadata.map { it.id })
            .associateBy { it.messageId }
        return metadata.mapNotNull { meta ->
            val body = bodies[meta.id] ?: return@mapNotNull null
            StoredMessage(meta, body)
        }
    }

    private suspend fun decryptAndMap(rows: List<StoredMessage>): List<TimelineEvent> {
        if (rows.isEmpty()) return emptyList()
        val shells = rows.map { it.toChatMessage() }
        val decrypted = ChatMessageCrypto.decryptFromStorage(shells)
        return rows.zip(decrypted).mapNotNull { (stored, plain) ->

            if (stored.metadata.turnId.isNullOrBlank()) return@mapNotNull null

            if (stored.metadata.type != MessageType.REASONING &&
                stored.metadata.type != MessageType.TEXT
            ) {
                return@mapNotNull null
            }
            TimelineMessageMapper.fromStored(stored, plain.content)
        }
    }
}
