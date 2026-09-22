package com.yunian.ai.database.repository

import androidx.room.withTransaction
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.MessageCache
import com.yunian.ai.database.dao.ConversationSummaryDao
import com.yunian.ai.database.dao.MessageDao
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.ConversationSummary
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ChatRepository(
    private val messageDao: MessageDao,
    private val summaryDao: ConversationSummaryDao,
    private val database: AppDatabase
) {

    fun warmCache(companionId: Long, messages: List<ChatMessage>) {
        MessageCache.putChatMessages(companionId, messages)
    }

    fun getCachedRecent(companionId: Long): List<ChatMessage>? =
        MessageCache.getChatMessages(companionId)

    fun observeCachedRecent(companionId: Long): StateFlow<List<ChatMessage>> =
        MessageCache.observeChatMessages(companionId)

    fun observeRecentMetadata(companionId: Long, limit: Int): Flow<List<Message>> =
        messageDao.getRecentMessageMetadata(companionId, "chat", limit)

    suspend fun getRecentMetadata(companionId: Long, limit: Int): List<Message> =
        mergeMetadata(
            messageDao.getRecentMessageMetadataSync(companionId, "chat", limit),
            messageDao.getRecentArchivedMessageMetadata(companionId, "chat", limit),
            limit
        )

    suspend fun getMetadataBefore(
        companionId: Long,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): List<Message> = mergeMetadata(
        messageDao.getMessageMetadataBeforeSync(companionId, "chat", beforeTimestamp, beforeId, limit),
        messageDao.getArchivedMessageMetadataBefore(companionId, "chat", beforeTimestamp, beforeId, limit),
        limit
    )

    suspend fun loadMessages(metadata: List<Message>): Map<Long, ChatMessage> {
        val companionId = metadata.firstOrNull()?.conversationId
        val cachedById = companionId?.let(MessageCache::getChatMessagesById).orEmpty()
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

    suspend fun hydrateRecent(companionId: Long, limit: Int) {
        val cached = MessageCache.getChatMessages(companionId)

        val needsHydrate = cached == null ||
            cached.isEmpty() ||
            cached.all { it.id < 0L }
        if (needsHydrate) {
            getRecentMessagesSync(companionId, limit)
        }
    }

    fun getMessagesForCompanion(companionId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        messageDao.getRecentMessageMetadata(companionId, "chat", limit)
            .map { fromMessages(loadStoredMessages(getRecentMetadata(companionId, limit))).reversed() }
            .onEach { decrypted -> MessageCache.putChatMessages(companionId, decrypted) }

    fun getMessagesBefore(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        messageDao.getMessageMetadataBefore(companionId, "chat", beforeTimestamp, beforeId, limit)
            .map {
                fromMessages(loadStoredMessages(getMetadataBefore(companionId, beforeTimestamp, beforeId, limit)))
                    .reversed()
            }

    fun getMessagesAfter(companionId: Long, afterTimestamp: Long, afterId: Long, limit: Int = 200): Flow<List<ChatMessage>> =
        messageDao.getMessageMetadataAfter(companionId, "chat", afterTimestamp, afterId, limit)
            .map { getMessagesAfterSync(companionId, afterTimestamp, afterId, limit) }

    fun getLastMessageForCompanion(companionId: Long): Flow<ChatMessage?> =
        messageDao.getLastMessage(companionId, "chat")
            .map { hot ->
                val archived = messageDao.getLastArchivedMessageMetadata(companionId, "chat")
                val latestMetadata = mergeMetadata(
                    hot?.let { listOf(it.metadata) }.orEmpty(),
                    archived?.let(::listOf).orEmpty(),
                    limit = 1
                ).firstOrNull()
                latestMetadata?.let { loadStoredMessages(listOf(it)).firstOrNull() }?.let { fromMessage(it) }
            }

    /** 写入一条消息（加密存储 + 缓存 + 摘要联动）。跨模块消费方：微信 Bridge 镜像 AI 表情包到 App 聊天。 */
    suspend fun sendMessage(message: ChatMessage): Long {
        val encrypted = ChatMessageCrypto.encryptForStorage(message)
        val (metadata, body) = StoredMessage.fromChatMessage(encrypted)
        val id = database.withTransaction {
            val insertedId = messageDao.insertStoredMessage(metadata, body)
            updateSummaryForChat(message.companionId, message.copy(id = insertedId), updateCache = false)
            insertedId
        }
        MessageCache.appendChatMessage(message.companionId, message.copy(id = id))
        summaryDao.getSummarySync(message.companionId, "chat")?.let { putSummaryInCache(it) }
        return id
    }

    suspend fun deleteMessage(message: ChatMessage) {
        val summary = database.withTransaction {
            messageDao.getMessageById(message.id)?.let { messageDao.deleteMessage(it.metadata) }
                ?: messageDao.deleteArchivedMessage(message.id)
            rebuildSummaryForChat(message.companionId)
        }
        MessageCache.removeChatMessage(message.companionId, message.id)
        summary?.let { putSummaryInCache(it) } ?: MessageCache.evictChat(message.companionId)
    }

    /**
     * 删除某一轮（turnId）已写入的工具调用卡片。用于：
     * - 幂等写入：同一 turnId 只保留一条 TOOL_ACTIVITY；
     * - 「再生成」时清理被替换那一轮的旧卡片（卡片 turnId 与被再生成的助手消息一致）。
     * 卡片不参与会话摘要，删除后无需重建摘要。
     */
    suspend fun deleteToolActivitiesForTurn(companionId: Long, turnId: String) {
        if (turnId.isBlank()) return
        val existing = messageDao.getMessagesByTurnAndType(
            companionId,
            "chat",
            turnId,
            MessageType.TOOL_ACTIVITY.name,
        )
        if (existing.isEmpty()) return
        database.withTransaction { existing.forEach { messageDao.deleteMessage(it.metadata) } }
        existing.forEach { MessageCache.removeChatMessage(companionId, it.metadata.id) }
    }

    suspend fun clearChatHistory(companionId: Long) {
        database.withTransaction {
            messageDao.deleteAllMessagesForConversation(companionId, "chat")
            summaryDao.deleteSummary(companionId, "chat")
        }
        MessageCache.evictChat(companionId)
    }

    suspend fun markReadThroughLatest(companionId: Long) {
        summaryDao.markReadThroughLatest(companionId, "chat")
        summaryDao.getSummarySync(companionId, "chat")?.let { putSummaryInCache(it) }
    }

    suspend fun getAiMessageCount(companionId: Long): Int =
        messageDao.getAiMessageCount(companionId, "chat") +
            messageDao.getArchivedAiMessageCount(companionId, "chat")

    suspend fun getMessageCount(companionId: Long): Int =
        messageDao.getMessageCount(companionId, "chat") +
            messageDao.getArchivedMessageCount(companionId, "chat")

    /**
     * 取最近消息（正序）。返回结果会**过滤掉 TOOL_ACTIVITY（工具调用卡片）**：
     * 该类型仅用于聊天消息流渲染，不参与任何 AI 上下文 / 日记 / 主动开场等基于历史文本的推理。
     * 过滤发生在写入 [MessageCache] 之后，故 UI 冷启动缓存仍保留完整记录。
     */
    suspend fun getRecentMessagesSync(companionId: Long, limit: Int): List<ChatMessage> {
        val all = fromMessages(loadStoredMessages(getRecentMetadata(companionId, limit)))
            .reversed()
        MessageCache.putChatMessages(companionId, all)
        return all.filter { it.type != MessageType.TOOL_ACTIVITY }
    }

    suspend fun getMessagesBeforeSync(companionId: Long, beforeTimestamp: Long, beforeId: Long, limit: Int): List<ChatMessage> =
        fromMessages(loadStoredMessages(getMetadataBefore(companionId, beforeTimestamp, beforeId, limit)))

    suspend fun getMessagesAfterSync(companionId: Long, afterTimestamp: Long, afterId: Long, limit: Int): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessageMetadataAfterSync(companionId, "chat", afterTimestamp, afterId, limit),
                    messageDao.getArchivedMessageMetadataAfter(companionId, "chat", afterTimestamp, afterId, limit),
                    limit,
                    descending = false
                )
            )
        )

    /**
     * 只读范围查询（按消息 id）：取单聊会话中 id > [afterIdExclusive] 的消息（id 正序）。
     * 口径与 [com.yunian.ai.feature.chat.data.ChatContextResolver] 对齐：
     * 解密 + filterDecrypted（剔除解密失败占位）+ 剔除 REASONING / TOOL_ACTIVITY 过程性消息。
     * 仅供滚动摘要增量合并的 gap fetcher 使用，不写缓存。
     */
    suspend fun getMessageRangeSync(companionId: Long, afterIdExclusive: Long, limit: Int): List<ChatMessage> =
        fromMessages(messageDao.getMessagesInRangeSync(companionId, "chat", afterIdExclusive, limit))
            .filterDecrypted()
            .filter { it.type != MessageType.REASONING && it.type != MessageType.TOOL_ACTIVITY }

    suspend fun getMessageById(messageId: Long): ChatMessage? =
        messageDao.getMessageById(messageId)?.let { fromMessage(it) }
            ?: messageDao.getArchivedMessageMetadataById(messageId)?.let { metadata ->
                loadStoredMessages(listOf(metadata)).firstOrNull()?.let { fromMessage(it) }
            }

    suspend fun getMessagesForCompanionSync(companionId: Long): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                (messageDao.getAllMessagesSync(companionId, "chat").map { it.metadata } +
                    messageDao.getAllArchivedMessageMetadata(companionId, "chat"))
                    .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
            )
        )

    suspend fun archiveOldMessages(companionId: Long, retainCount: Int): Int =
        messageDao.archiveOldMessages(companionId, "chat", retainCount)

    suspend fun restoreArchivedMessages(companionId: Long): Int =
        messageDao.restoreArchivedMessages(companionId, "chat")

    suspend fun searchMessages(companionId: Long, query: String, limit: Int = 50): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.searchMessages(companionId, "chat", query, limit).map { it.metadata },
                    messageDao.searchArchivedMessageMetadata(companionId, "chat", query, limit),
                    limit
                )
            )
        )

    suspend fun getMessagesByFileFormat(companionId: Long, fileFormat: FileFormat, limit: Int = 50): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessagesByFileFormat(companionId, "chat", fileFormat, limit).map { it.metadata },
                    messageDao.getArchivedMessageMetadataByFileFormat(companionId, "chat", fileFormat, limit),
                    limit
                )
            )
        )

    suspend fun getMessagesByFileFormatBefore(
        companionId: Long,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int = 50
    ): List<ChatMessage> =
        fromMessages(
            loadStoredMessages(
                mergeMetadata(
                    messageDao.getMessagesByFileFormatBefore(
                        companionId, "chat", fileFormat, beforeTimestamp, beforeId, limit
                    ).map { it.metadata },
                    messageDao.getArchivedMessageMetadataByFileFormatBefore(
                        companionId, "chat", fileFormat, beforeTimestamp, beforeId, limit
                    ),
                    limit
                )
            )
        )

    suspend fun updateMessageContent(messageId: Long, content: String) {
        val updated = database.withTransaction {
            val encryptedContent = ChatMessageCrypto.encrypt(content)
            if (messageDao.updateMessageContent(messageId, encryptedContent, content) == 0) {
                messageDao.updateArchivedMessageContent(messageId, encryptedContent, content)
            }
            getStoredMessageById(messageId)?.also {
                val summary = summaryDao.getSummarySync(it.metadata.conversationId, "chat")
                if (summary?.lastMessageId == messageId) {
                    summaryDao.upsertSummary(summary.copy(lastMessagePreview = content.take(100)))
                }
            }
        }
        if (updated != null) {
            val decrypted = fromMessage(updated)
            MessageCache.updateChatMessage(decrypted.companionId, messageId) { decrypted }
            summaryDao.getSummarySync(decrypted.companionId, "chat")?.let { putSummaryInCache(it) }
        }
    }

    private suspend fun getStoredMessageById(messageId: Long): StoredMessage? =
        messageDao.getMessageById(messageId)
            ?: messageDao.getArchivedMessageMetadataById(messageId)?.let { metadata ->
                loadStoredMessages(listOf(metadata)).firstOrNull()
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

    internal suspend fun batchInsertMessages(messages: List<ChatMessage>): List<Long> {
        if (messages.isEmpty()) return emptyList()
        val encrypted = ChatMessageCrypto.encryptForStorage(messages)
            .map { StoredMessage.fromChatMessage(it) }
        val persisted = database.withTransaction {
            val ids = messageDao.insertStoredMessages(encrypted)
            messages.zip(ids).map { (message, id) -> message.copy(id = id) }.also { inserted ->
                inserted.groupBy { it.companionId }.forEach { (companionId, grouped) ->
                    val lastMessage = grouped.maxWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    updateSummaryForChat(
                        companionId,
                        lastMessage,
                        incomingMessages = grouped,
                        updateCache = false
                    )
                }
            }
        }
        persisted.groupBy { it.companionId }.forEach { (companionId, inserted) ->
            inserted.forEach { MessageCache.appendChatMessage(companionId, it) }
            summaryDao.getSummarySync(companionId, "chat")?.let { putSummaryInCache(it) }
        }
        return persisted.map { it.id }
    }

    private suspend fun updateSummaryForChat(
        companionId: Long,
        message: ChatMessage,
        incomingMessages: List<ChatMessage> = listOf(message),
        updateCache: Boolean = true
    ) {
        // 工具调用卡片是过程可视化消息：不参与会话列表「最后一条预览 / 最新时间 / 未读」，
        // 否则会话列表预览会显示一段 JSON。
        if (message.type == MessageType.TOOL_ACTIVITY) return
        val preview = message.content.take(100)
        val existing = summaryDao.getSummarySync(companionId, "chat")
        val unreadIncrement = incomingMessages.count {
            // 过程性消息不计未读：TOOL_ACTIVITY（工具卡片）与 REASONING（推理过程）。
            // 说明：REASONING 实际经 RoomTimelineStore.appendComplete 落库、不经过本方法，
            // 这里显式排除是为了与 getUnreadMessageCount（全量重建路径）保持完全一致的口径。
            it.type != MessageType.TOOL_ACTIVITY &&
                it.type != MessageType.REASONING &&
                !it.isFromUser && isAfterReadCursor(it.timestamp, it.id, existing)
        }
        val advancesLatest = existing == null ||
            message.timestamp > existing.lastMessageTimestamp ||
            (message.timestamp == existing.lastMessageTimestamp && message.id > (existing.lastMessageId ?: 0L))
        val summary = ConversationSummary(
            sessionId = companionId,
            sessionType = "chat",
            lastMessageId = if (advancesLatest) message.id.takeIf { it > 0 } else existing.lastMessageId,
            lastMessagePreview = if (advancesLatest) preview else existing.lastMessagePreview,
            lastMessageTimestamp = if (advancesLatest) message.timestamp else existing.lastMessageTimestamp,
            lastMessageIsFromUser = if (advancesLatest) message.isFromUser else existing.lastMessageIsFromUser,
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

    private suspend fun rebuildSummaryForChat(companionId: Long): ConversationSummary? {
        val existing = summaryDao.getSummarySync(companionId, "chat")
        val latest = messageDao.getLastMessageSync(companionId, "chat")
        if (latest == null) {
            summaryDao.deleteSummary(companionId, "chat")
            return null
        }
        val message = fromMessage(latest)
        // 最新一条是工具调用卡片时，会话摘要维持原状（卡片不参与预览/未读统计）
        if (message.type == MessageType.TOOL_ACTIVITY) return existing
        return ConversationSummary(
            sessionId = companionId,
            sessionType = "chat",
            lastMessageId = message.id,
            lastMessagePreview = message.content.take(100),
            lastMessageTimestamp = message.timestamp,
            lastMessageIsFromUser = message.isFromUser,
            readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
            readThroughMessageId = existing?.readThroughMessageId,
            unreadCount = messageDao.getUnreadMessageCount(
                companionId,
                "chat",
                existing?.readThroughMessageTimestamp,
                existing?.readThroughMessageId
            ),
            isPinned = existing?.isPinned ?: false,
            isMuted = existing?.isMuted ?: false
        ).also { summaryDao.upsertSummary(it) }
    }

    private fun putSummaryInCache(summary: ConversationSummary) {
        MessageCache.putChatSummary(summary.sessionId, MessageCache.SessionSummary(
            lastMessagePreview = summary.lastMessagePreview,
            lastMessageTimestamp = summary.lastMessageTimestamp,
            lastMessageIsFromUser = summary.lastMessageIsFromUser,
            unreadCount = summary.unreadCount
        ))
    }

    private suspend fun fromMessage(message: StoredMessage): ChatMessage =
        ChatMessageCrypto.decryptFromStorage(message.toChatMessage())

    private suspend fun fromMessages(messages: List<StoredMessage>): List<ChatMessage> =
        ChatMessageCrypto.decryptFromStorage(messages.map { it.toChatMessage() })
}
