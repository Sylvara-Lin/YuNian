package com.yunian.ai.database.repository

import androidx.room.withTransaction
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.MessageCache
import com.yunian.ai.database.dao.ConversationSummaryDao
import com.yunian.ai.database.dao.MessageDao
import com.yunian.ai.database.model.ConversationSummary
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.GroupMessage
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class GroupMessageRepository(
    private val messageDao: MessageDao,
    private val summaryDao: ConversationSummaryDao,
    private val database: AppDatabase
) {

    fun warmCache(groupId: Long, messages: List<GroupMessage>) {
        MessageCache.putGroupMessages(groupId, messages)
    }

    fun getCachedRecent(groupId: Long): List<GroupMessage>? =
        MessageCache.getGroupMessages(groupId)

    fun observeCachedRecent(groupId: Long): StateFlow<List<GroupMessage>> =
        MessageCache.observeGroupMessages(groupId)

    fun observeRecentMetadata(groupId: Long, limit: Int): Flow<List<Message>> =
        messageDao.getRecentMessageMetadata(groupId, "group", limit)

    suspend fun getRecentMetadata(groupId: Long, limit: Int): List<Message> =
        mergeMetadata(
            messageDao.getRecentMessageMetadataSync(groupId, "group", limit),
            messageDao.getRecentArchivedMessageMetadata(groupId, "group", limit),
            limit
        )

    suspend fun getMetadataBefore(
        groupId: Long,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): List<Message> = mergeMetadata(
        messageDao.getMessageMetadataBeforeSync(groupId, "group", beforeTimestamp, beforeId, limit),
        messageDao.getArchivedMessageMetadataBefore(groupId, "group", beforeTimestamp, beforeId, limit),
        limit
    )

    suspend fun loadMessages(metadata: List<Message>): Map<Long, GroupMessage> {
        val groupId = metadata.firstOrNull()?.conversationId
        val cachedById = groupId?.let(MessageCache::getGroupMessagesById).orEmpty()
        val missing = ArrayList<Message>()
        metadata.forEach { item ->
            if (item.id !in cachedById) missing += item
        }
        if (missing.isEmpty()) {
            return buildMap(metadata.size) {
                metadata.forEach { item -> cachedById[item.id]?.let { put(item.id, it) } }
            }
        }
        val loadedById = fromMessages(loadStoredMessages(missing)).associateBy { it.id }
        return buildMap(metadata.size) {
            metadata.forEach { item ->
                (cachedById[item.id] ?: loadedById[item.id])?.let { put(item.id, it) }
            }
        }
    }

    suspend fun hydrateRecent(groupId: Long, limit: Int) {
        if (MessageCache.getGroupMessages(groupId) == null) {
            val recent = getRecentMessagesSync(groupId, limit)
            MessageCache.putGroupMessages(groupId, recent)
        }
    }

    fun getMessagesForGroup(groupId: Long, limit: Int = 50): Flow<List<GroupMessage>> =
        messageDao.getRecentMessageMetadata(groupId, "group", limit)
            .map { fromMessages(loadStoredMessages(getRecentMetadata(groupId, limit))).reversed() }
            .onEach { decrypted -> MessageCache.putGroupMessages(groupId, decrypted) }

    suspend fun getRecentMessagesSync(groupId: Long, limit: Int = 50): List<GroupMessage> =
        fromMessages(loadStoredMessages(messageDao.getRecentMessageMetadataSync(groupId, "group", limit)))
            .reversed()

    fun getMessagesBefore(groupId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int = 30): Flow<List<GroupMessage>> =
        messageDao.getMessageMetadataBefore(groupId, "group", beforeTimestamp, beforeId, limit)
            .map {
                fromMessages(loadStoredMessages(getMetadataBefore(groupId, beforeTimestamp, beforeId, limit)))
                    .reversed()
            }

    fun getMessagesAfter(groupId: Long, afterTimestamp: Long, afterId: Long, limit: Int = 30): Flow<List<GroupMessage>> =
        messageDao.getMessageMetadataAfter(groupId, "group", afterTimestamp, afterId, limit)
            .map { getMessagesAfterSync(groupId, afterTimestamp, afterId, limit) }

    fun getLastMessageForGroup(groupId: Long): Flow<GroupMessage?> =
        messageDao.getLastMessage(groupId, "group")
            .map { hot ->
                val archived = messageDao.getLastArchivedMessageMetadata(groupId, "group")
                val latestMetadata = mergeMetadata(
                    hot?.let { listOf(it.metadata) }.orEmpty(),
                    archived?.let(::listOf).orEmpty(),
                    limit = 1
                ).firstOrNull()
                latestMetadata?.let { loadStoredMessages(listOf(it)).firstOrNull() }?.let { fromMessage(it) }
            }

    internal suspend fun sendMessage(message: GroupMessage): Long {
        val encrypted = ChatMessageCrypto.encryptForStorage(message)
        val (metadata, body) = StoredMessage.fromGroupMessage(encrypted)
        val id = database.withTransaction {
            val insertedId = messageDao.insertStoredMessage(metadata, body)
            updateSummaryForGroup(message.groupId, message.copy(id = insertedId), updateCache = false)
            insertedId
        }
        MessageCache.appendGroupMessage(message.groupId, message.copy(id = id))
        summaryDao.getSummarySync(message.groupId, "group")?.let { putSummaryInCache(it) }
        return id
    }

    suspend fun deleteMessage(message: GroupMessage) {
        val summary = database.withTransaction {
            messageDao.getMessageById(message.id)?.let { messageDao.deleteMessage(it.metadata) }
            rebuildSummaryForGroup(message.groupId)
        }
        MessageCache.removeGroupMessage(message.groupId, message.id)
        summary?.let { putSummaryInCache(it) } ?: MessageCache.evictGroup(message.groupId)
    }

    suspend fun clearGroupHistory(groupId: Long) {
        database.withTransaction {
            messageDao.deleteAllMessagesForConversation(groupId, "group")
            summaryDao.deleteSummary(groupId, "group")
        }
        MessageCache.evictGroup(groupId)
    }

    suspend fun getMessagesBeforeSync(groupId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<GroupMessage> =
        fromMessages(loadStoredMessages(getMetadataBefore(groupId, beforeTimestamp, beforeId, limit)))
            .reversed()

    suspend fun getMessagesAfterSync(groupId: Long, afterTimestamp: Long, afterId: Long, limit: Int): List<GroupMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessageMetadataAfterSync(groupId, "group", afterTimestamp, afterId, limit),
                    messageDao.getArchivedMessageMetadataAfter(groupId, "group", afterTimestamp, afterId, limit),
                    limit,
                    descending = false
                )
            )
        )

    suspend fun searchMessages(groupId: Long, query: String, limit: Int = 50): List<GroupMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.searchMessages(groupId, "group", query, limit).map { it.metadata },
                    messageDao.searchArchivedMessageMetadata(groupId, "group", query, limit),
                    limit
                )
            )
        )

    suspend fun getMessagesByFileFormat(groupId: Long, fileFormat: FileFormat, limit: Int = 50): List<GroupMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessagesByFileFormat(groupId, "group", fileFormat, limit).map { it.metadata },
                    messageDao.getArchivedMessageMetadataByFileFormat(groupId, "group", fileFormat, limit),
                    limit
                )
            )
        )

    suspend fun getMessagesByFileFormatBefore(
        groupId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int = 50
    ): List<GroupMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessagesByFileFormatBefore(
                        groupId, "group", fileFormat, beforeTimestamp, beforeId, limit
                    ).map { it.metadata },
                    messageDao.getArchivedMessageMetadataByFileFormatBefore(
                        groupId, "group", fileFormat, beforeTimestamp, beforeId, limit
                    ),
                    limit
                )
            )
        )

    suspend fun getMessageCount(groupId: Long): Int =
        messageDao.getMessageCount(groupId, "group") +
            messageDao.getArchivedMessageCount(groupId, "group")

    suspend fun archiveOldMessages(groupId: Long, retainCount: Int): Int =
        messageDao.archiveOldMessages(groupId, "group", retainCount)

    suspend fun restoreArchivedMessages(groupId: Long): Int =
        messageDao.restoreArchivedMessages(groupId, "group")

    suspend fun markReadThroughLatest(groupId: Long) {
        summaryDao.markReadThroughLatest(groupId, "group")
    }

    internal suspend fun batchInsertMessages(messages: List<GroupMessage>): List<Long> {
        if (messages.isEmpty()) return emptyList()
        val encrypted = ChatMessageCrypto.encryptForStorageGroup(messages)
            .map { StoredMessage.fromGroupMessage(it) }
        val persisted = database.withTransaction {
            val ids = messageDao.insertStoredMessages(encrypted)
            messages.zip(ids).map { (message, id) -> message.copy(id = id) }.also { inserted ->
                inserted.groupBy { it.groupId }.forEach { (groupId, grouped) ->
                    val lastMessage = grouped.maxWith(compareBy<GroupMessage> { it.timestamp }.thenBy { it.id })
                    updateSummaryForGroup(
                        groupId,
                        lastMessage,
                        incomingMessages = grouped,
                        updateCache = false
                    )
                }
            }
        }
        persisted.groupBy { it.groupId }.forEach { (groupId, inserted) ->
            inserted.forEach { MessageCache.appendGroupMessage(groupId, it) }
            summaryDao.getSummarySync(groupId, "group")?.let { putSummaryInCache(it) }
        }
        return persisted.map { it.id }
    }

    private suspend fun loadStoredMessages(metadata: List<Message>): List<StoredMessage> {
        if (metadata.isEmpty()) return emptyList()
        return database.withTransaction {
            val messageIds = metadata.map { it.id }
            val bodiesById = (
                messageDao.getMessageBodies(messageIds) +
                    messageDao.getArchivedMessageBodies(messageIds)
                ).associateBy { it.messageId }
            metadata.map { message ->
                StoredMessage(message, requireNotNull(bodiesById[message.id]) { "Missing body for message ${message.id}" })
            }
        }
    }

    private fun mergeMetadata(
        hot: List<Message>,
        archived: List<Message>,
        limit: Int,
        descending: Boolean = true
    ): List<Message> =
        (hot + archived)
            .distinctBy { it.id }
            .sortedWith(
                if (descending) {
                    compareByDescending<Message> { it.timestamp }.thenByDescending { it.id }
                } else {
                    compareBy<Message> { it.timestamp }.thenBy { it.id }
                }
            )
            .take(limit)

    private suspend fun updateSummaryForGroup(
        groupId: Long,
        message: GroupMessage,
        incomingMessages: List<GroupMessage> = listOf(message),
        updateCache: Boolean = true
    ) {
        val preview = message.content.take(100)
        val isFromUser = message.companionId == -1L
        val existing = summaryDao.getSummarySync(groupId, "group")
        val unreadIncrement = incomingMessages.count {
            it.companionId != -1L && isAfterReadCursor(it.timestamp, it.id, existing)
        }
        val advancesLatest = existing == null ||
            message.timestamp > existing.lastMessageTimestamp ||
            (message.timestamp == existing.lastMessageTimestamp && message.id > (existing.lastMessageId ?: 0L))
        val summary = ConversationSummary(
            sessionId = groupId,
            sessionType = "group",
            lastMessageId = if (advancesLatest) message.id.takeIf { it > 0 } else existing.lastMessageId,
            lastMessagePreview = if (advancesLatest) preview else existing.lastMessagePreview,
            lastMessageTimestamp = if (advancesLatest) message.timestamp else existing.lastMessageTimestamp,
            lastMessageIsFromUser = if (advancesLatest) isFromUser else existing.lastMessageIsFromUser,
            readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
            readThroughMessageId = existing?.readThroughMessageId,
            unreadCount = (existing?.unreadCount ?: 0) + unreadIncrement,
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        )
        summaryDao.upsertSummary(summary)
        if (updateCache) putSummaryInCache(summary)
    }

    private fun isAfterReadCursor(timestamp: Long, id: Long, summary: ConversationSummary?): Boolean {
        val readTimestamp = summary?.readThroughMessageTimestamp ?: return true
        return timestamp > readTimestamp ||
            (timestamp == readTimestamp && id > (summary.readThroughMessageId ?: 0L))
    }

    private suspend fun rebuildSummaryForGroup(groupId: Long): ConversationSummary? {
        val existing = summaryDao.getSummarySync(groupId, "group")
        val latest = messageDao.getLastMessageSync(groupId, "group")
        if (latest == null) {
            summaryDao.deleteSummary(groupId, "group")
            return null
        }
        val message = fromMessage(latest)
        val isFromUser = message.companionId == -1L
        return ConversationSummary(
            sessionId = groupId,
            sessionType = "group",
            lastMessageId = message.id,
            lastMessagePreview = message.content.take(100),
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = isFromUser,
            readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
            readThroughMessageId = existing?.readThroughMessageId,
            unreadCount = messageDao.getUnreadMessageCount(
                groupId,
                "group",
                existing?.readThroughMessageTimestamp,
                existing?.readThroughMessageId
            ),
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        ).also { summaryDao.upsertSummary(it) }
    }

    private fun putSummaryInCache(summary: ConversationSummary) {
        MessageCache.putGroupSummary(summary.sessionId, MessageCache.SessionSummary(
            lastMessagePreview = summary.lastMessagePreview,
            lastMessageTimestamp = summary.lastMessageTimestamp,
            lastMessageIsFromUser = summary.lastMessageIsFromUser,
            unreadCount = summary.unreadCount
        ))
    }

    private suspend fun fromMessage(message: StoredMessage): GroupMessage =
        ChatMessageCrypto.decryptFromStorage(message.toGroupMessage())

    private suspend fun fromMessages(messages: List<StoredMessage>): List<GroupMessage> =
        ChatMessageCrypto.decryptFromStorageGroup(messages.map { it.toGroupMessage() })
}
