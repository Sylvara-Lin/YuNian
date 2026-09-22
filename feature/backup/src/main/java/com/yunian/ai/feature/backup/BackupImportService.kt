package com.yunian.ai.feature.backup

import android.content.Context
import androidx.room.withTransaction
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.MessageCache
import com.yunian.ai.database.model.*
import com.yunian.ai.database.repository.ChatMessageCrypto
import com.yunian.ai.database.repository.MemoryCrypto
import com.yunian.ai.feature.backup.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupImportService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    private data class ChatKey(val timestamp: Long, val isFromUser: Boolean, val content: String)

    private data class GroupKey(val timestamp: Long, val senderId: Long, val content: String)

    suspend fun import(data: BackupData): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {

            db.withTransaction {
                merge(data)
            }
            MessageCache.clearAll()
        }
    }

    private suspend fun merge(data: BackupData) {
        val now = System.currentTimeMillis()

        val existingCompanions = db.companionDao().getAllCompanionsSync()
        val existingByName = existingCompanions.associateBy { it.name }

        val companionIdMap = mutableMapOf<Long, Long>()

        data.companions.forEach { s ->
            val existing = existingByName[s.name]
            if (existing != null) {
                companionIdMap[s.id] = existing.id
            } else {
                val newId = db.companionDao().insertCompanion(
                    CompanionEntity(
                        id = 0, name = s.name, avatarUrl = s.avatarUrl, age = s.age,
                        personality = s.personality, backstory = s.backstory,
                        speakingStyle = s.speakingStyle, tags = s.tags,
                        rawPrompt = s.rawPrompt, systemPrompt = s.systemPrompt,
                        intimacy = s.intimacy,
                        // api_configs 不随备份迁移，跨设备 id 语义不可信（可能碰撞到本机其他配置），一律置 null 回退全局
                        apiConfigId = null,
                        createdAt = s.createdAt,
                        updatedAt = s.updatedAt
                    )
                )
                companionIdMap[s.id] = newId
            }
        }

        val existingGroups = db.chatGroupDao().getAllGroupsSync()
        val existingGroupsByName = existingGroups.associateBy { it.name }

        val groupIdMap = mutableMapOf<Long, Long>()

        data.chatGroups.forEach { s ->
            val existing = existingGroupsByName[s.name]
            val backupMembers = s.companionIds.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .map { companionIdMap[it] ?: it }
            if (existing != null) {
                groupIdMap[s.id] = existing.id

                val merged = (existing.getCompanionIdList().toMutableSet() + backupMembers).joinToString(",")
                if (merged != existing.companionIds) {
                    db.chatGroupDao().updateGroup(existing.copy(companionIds = merged))
                }
            } else {
                val newId = db.chatGroupDao().insertGroup(
                    ChatGroup(
                        id = 0, name = s.name, avatarUrl = s.avatarUrl,
                        companionIds = backupMembers.distinct().joinToString(","),
                        createdAt = s.createdAt, updatedAt = s.updatedAt
                    )
                )
                groupIdMap[s.id] = newId
            }
        }

        val messageIdMap = mutableMapOf<Long, Long>()

        data.chatMessages.groupBy { it.companionId }.forEach { (snapshotCompanionId, messages) ->
            val localCompanionId = companionIdMap[snapshotCompanionId] ?: return@forEach

            val localStored = db.messageDao().getAllMessagesSync(localCompanionId, "chat")
            val localMessages = if (localStored.isEmpty()) emptyList()
            else ChatMessageCrypto.decryptFromStorage(localStored.map { it.toChatMessage() })
            val existingByKey = localMessages.associateBy {
                ChatKey(it.timestamp, it.isFromUser, it.content)
            }
            val insertedKeys = existingByKey.keys.toMutableSet()

            messages.sortedBy { it.timestamp }.forEach { s ->
                val key = ChatKey(s.timestamp, s.isFromUser, s.content)
                if (key !in insertedKeys) {
                    val chatMessage = ChatMessage(
                        id = 0, companionId = localCompanionId, content = s.content,
                        isFromUser = s.isFromUser, timestamp = s.timestamp,
                        type = safeEnum<MessageType>(s.type),
                        searchContent = s.searchContent.ifEmpty { s.content },
                        fileFormat = safeEnum<FileFormat>(s.fileFormat),
                        linkString = s.linkString,
                        turnId = s.turnId, eventIndex = s.eventIndex,
                        durationMs = s.durationMs, anchorMessageId = null
                    )
                    val encrypted = ChatMessageCrypto.encryptForStorage(listOf(chatMessage)).first()
                    val (metadata, body) = StoredMessage.fromChatMessage(encrypted)
                    val newId = db.messageDao().insertStoredMessage(metadata, body)
                    messageIdMap[s.id] = newId
                    insertedKeys.add(key)
                } else {

                    existingByKey[key]?.let { messageIdMap[s.id] = it.id }
                }
            }
        }

        data.chatMessages.forEach { s ->
            val newId = messageIdMap[s.id] ?: return@forEach
            val anchorNewId = s.anchorMessageId?.let { messageIdMap[it] }
            if (anchorNewId != null) {
                db.messageDao().updateAnchorMessageId(newId, anchorNewId)
            }
        }

        data.groupMessages.groupBy { it.groupId }.forEach { (snapshotGroupId, messages) ->
            val localGroupId = groupIdMap[snapshotGroupId] ?: return@forEach
            val localStored = db.messageDao().getAllMessagesSync(localGroupId, "group")
            val localMessages = if (localStored.isEmpty()) emptyList()
            else ChatMessageCrypto.decryptFromStorageGroup(localStored.map { it.toGroupMessage() })
            val existingByKey = localMessages.associateBy {
                GroupKey(it.timestamp, it.companionId, it.content)
            }
            val insertedKeys = existingByKey.keys.toMutableSet()

            messages.sortedBy { it.timestamp }.forEach { s ->
                val senderLocalId = if (s.companionId == -1L) -1L
                else companionIdMap[s.companionId] ?: s.companionId
                val key = GroupKey(s.timestamp, senderLocalId, s.content)
                if (key !in insertedKeys) {
                    val groupMessage = GroupMessage(
                        id = 0, groupId = localGroupId, companionId = senderLocalId,
                        content = s.content, timestamp = s.timestamp,
                        searchContent = s.searchContent.ifEmpty { s.content },
                        fileFormat = safeEnum<FileFormat>(s.fileFormat),
                        linkString = s.linkString
                    )
                    val encrypted = ChatMessageCrypto.encryptForStorageGroup(listOf(groupMessage)).first()
                    val (metadata, body) = StoredMessage.fromGroupMessage(encrypted)
                    db.messageDao().insertStoredMessage(metadata, body)
                    insertedKeys.add(key)
                }
            }
        }

        val affectedChatSessions = data.chatMessages.mapNotNull { companionIdMap[it.companionId] }.distinct()
        val affectedGroupSessions = data.groupMessages.mapNotNull { groupIdMap[it.groupId] }.distinct()
        affectedChatSessions.forEach { rebuildChatSummary(it) }
        affectedGroupSessions.forEach { rebuildGroupSummary(it) }

        data.memoryEntries.forEach { s ->
            val localCompanionId = companionIdMap[s.companionId] ?: s.companionId
            val encryptedContext = if (s.context.isNotBlank()) {
                try { MemoryCrypto.encrypt(s.context) } catch (_: Exception) { s.context }
            } else ""
            db.memoryDao().insertMemory(
                MemoryEntry(
                    id = 0, companionId = localCompanionId, content = s.content,
                    category = safeEnum<MemoryCategory>(s.category),
                    importance = s.importance, context = encryptedContext,
                    accessCount = s.accessCount, timestamp = s.timestamp,
                    lastAccessed = s.lastAccessed, deviceId = deviceId
                )
            )
        }

        data.tempMemories.forEach { s ->
            db.memoryDao().insertTempMemory(
                TempMemory(
                    id = 0, companionId = companionIdMap[s.companionId] ?: s.companionId,
                    userInput = s.userInput, botResponse = s.botResponse,
                    timestamp = s.timestamp, deviceId = deviceId
                )
            )
        }

        data.tokenUsages.forEach { s ->
            db.tokenUsageDao().insert(
                TokenUsage(
                    id = 0, companionId = companionIdMap[s.companionId] ?: s.companionId,
                    date = s.date, inputTokens = s.inputTokens, outputTokens = s.outputTokens,
                    totalTokens = s.totalTokens, requestCount = s.requestCount,
                    timestamp = s.timestamp, deviceId = deviceId
                )
            )
        }

        data.unifiedMemories.forEach { s ->
            val localSourceId = when (safeEnum<MemoryScope>(s.scope)) {
                MemoryScope.COMPANION -> companionIdMap[s.sourceId] ?: s.sourceId
                MemoryScope.GROUP -> groupIdMap[s.sourceId] ?: s.sourceId
                else -> s.sourceId
            }
            db.unifiedMemoryDao().insert(
                MemoryRecord(
                    id = 0,
                    memoryType = safeEnum<MemoryType>(s.memoryType),
                    scope = safeEnum<MemoryScope>(s.scope),
                    source = safeEnum<MemorySource>(s.source),
                    content = s.content,
                    summary = s.summary,
                    confidence = s.confidence,
                    importance = s.importance,
                    sourceId = localSourceId,
                    createdAt = s.createdAt.ifZero(now),
                    updatedAt = s.updatedAt.ifZero(now),
                    observedAt = s.observedAt.ifZero(now),
                    expiresAt = s.expiresAt,
                    accessCount = s.accessCount,
                    tags = s.tags,
                    deviceId = deviceId
                )
            )
        }

        data.diaries.forEach { s ->
            db.diaryDao().insertDiary(
                DiaryEntry(
                    id = 0,
                    companionId = companionIdMap[s.companionId] ?: s.companionId,
                    title = s.title,
                    content = s.content,
                    mood = s.mood,
                    date = s.date,
                    weather = s.weather,
                    tags = s.tags,
                    deviceId = deviceId
                )
            )
        }
    }

    private fun Long.ifZero(fallback: Long): Long = if (this == 0L) fallback else this

    private suspend fun rebuildChatSummary(companionId: Long) {
        val last = db.messageDao().getLastMessageSync(companionId, "chat") ?: return
        val decrypted = ChatMessageCrypto.decryptFromStorage(last.toChatMessage())
        val existing = db.conversationSummaryDao().getSummarySync(companionId, "chat")
        db.conversationSummaryDao().upsertSummary(
            ConversationSummary(
                sessionId = companionId,
                sessionType = "chat",
                lastMessageId = last.metadata.id,
                lastMessagePreview = decrypted.content.take(100),
                lastMessageTimestamp = decrypted.timestamp,
                lastMessageIsFromUser = decrypted.isFromUser,
                readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
                readThroughMessageId = existing?.readThroughMessageId,
                unreadCount = existing?.unreadCount ?: 0,
                isPinned = existing?.isPinned ?: false,
                isMuted = existing?.isMuted ?: false
            )
        )
    }

    private suspend fun rebuildGroupSummary(groupId: Long) {
        val last = db.messageDao().getLastMessageSync(groupId, "group") ?: return
        val decrypted = ChatMessageCrypto.decryptFromStorage(last.toGroupMessage())
        val existing = db.conversationSummaryDao().getSummarySync(groupId, "group")
        db.conversationSummaryDao().upsertSummary(
            ConversationSummary(
                sessionId = groupId,
                sessionType = "group",
                lastMessageId = last.metadata.id,
                lastMessagePreview = decrypted.content.take(100),
                lastMessageTimestamp = decrypted.timestamp,
                lastMessageIsFromUser = decrypted.companionId == -1L,
                readThroughMessageTimestamp = existing?.readThroughMessageTimestamp,
                readThroughMessageId = existing?.readThroughMessageId,
                unreadCount = existing?.unreadCount ?: 0,
                isPinned = existing?.isPinned ?: false,
                isMuted = existing?.isMuted ?: false
            )
        )
    }

    private inline fun <reified T : Enum<T>> safeEnum(name: String): T {
        return try { enumValueOf<T>(name) }
        catch (_: Exception) { enumValues<T>().first() }
    }
}
