package com.yunian.ai.feature.backup

import android.content.Context
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.GroupMessage
import com.yunian.ai.database.repository.ChatMessageCrypto
import com.yunian.ai.database.repository.MemoryCrypto
import com.yunian.ai.feature.backup.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupExportService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    suspend fun export(companionIds: Set<Long>? = null): BackupData = withContext(Dispatchers.IO) {
        val allCompanions = db.companionDao().getAllCompanionsSync()
        val selectedCompanions = if (companionIds.isNullOrEmpty()) allCompanions
        else allCompanions.filter { it.id in companionIds }

        val companions = selectedCompanions.map { it.toSnapshot() }
        val chatMessages = mutableListOf<ChatMessageSnapshot>()
        val chatGroups = db.chatGroupDao().getAllGroupsSync().map { it.toSnapshot() }
        val groupMessages = mutableListOf<GroupMessageSnapshot>()
        val memoryEntries = db.memoryDao().getAllMemoriesSync(deviceId).map { it.toDecryptedSnapshot() }
        val tempMemories = db.memoryDao().getAllTempMemoriesSync(deviceId).map { it.toSnapshot() }
        val tokenUsages = db.tokenUsageDao().getAllUsageSync(deviceId).map { it.toSnapshot() }
        val unifiedMemories = db.unifiedMemoryDao().getAllActiveSync(deviceId)
            .filter { it.isDeleted == 0 }
            .map { it.toSnapshot() }
        val diaries = db.diaryDao().getAllDiariesSync(deviceId).map { it.toSnapshot() }

        for (c in companions) {
            val raw = db.messageDao().getAllMessagesSync(c.id, "chat")
            chatMessages.addAll(
                ChatMessageCrypto.decryptFromStorage(raw.map { it.toChatMessage() })
                    .map { it.toSnapshot() }
            )
        }

        for (g in chatGroups) {
            val raw = db.messageDao().getAllMessagesSync(g.id, "group")
            groupMessages.addAll(
                ChatMessageCrypto.decryptFromStorageGroup(raw.map { it.toGroupMessage() })
                    .map { it.toSnapshot() }
            )
        }

        BackupData(
            exportedAt = System.currentTimeMillis(),
            appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "unknown",
            companions = companions,
            chatMessages = chatMessages,
            chatGroups = chatGroups,
            groupMessages = groupMessages,
            memoryEntries = memoryEntries,
            tempMemories = tempMemories,
            tokenUsages = tokenUsages,
            unifiedMemories = unifiedMemories,
            diaries = diaries
        )
    }

    suspend fun getCompanionStats(): List<CompanionExportStat> = withContext(Dispatchers.IO) {
        db.companionDao().getAllCompanionsSync().map { companion ->
            val raw = db.messageDao().getAllMessagesSync(companion.id, "chat")
            val messages = ChatMessageCrypto.decryptFromStorage(raw.map { it.toChatMessage() })
            val totalSizeBytes = messages.sumOf { m ->
                estimateMessageSizeBytes(m)
            }
            CompanionExportStat(
                companionId = companion.id,
                name = companion.name,
                avatarUrl = companion.avatarUrl,
                messageCount = messages.size,
                totalSizeBytes = totalSizeBytes,
                lastTimestamp = messages.maxOfOrNull { it.timestamp } ?: 0L
            )
        }.sortedByDescending { it.lastTimestamp }
    }

    private fun estimateMessageSizeBytes(message: ChatMessage): Long {
        var size = message.content.toByteArray(Charsets.UTF_8).size.toLong()
        val mediaPath = message.linkString.ifBlank { message.content }
        if (mediaPath.isNotBlank()) {
            runCatching {
                val f = java.io.File(mediaPath)
                if (f.exists() && f.isFile) size += f.length()
            }
        }
        return size
    }
}

data class CompanionExportStat(
    val companionId: Long,
    val name: String,
    val avatarUrl: String?,
    val messageCount: Int,
    val totalSizeBytes: Long,
    val lastTimestamp: Long
)

private fun com.yunian.ai.database.model.CompanionEntity.toSnapshot() = CompanionSnapshot(
    id = id, name = name, avatarUrl = avatarUrl, age = age,
    personality = personality, backstory = backstory, speakingStyle = speakingStyle,
    tags = tags, rawPrompt = rawPrompt, systemPrompt = systemPrompt,
    intimacy = intimacy, apiConfigId = apiConfigId, createdAt = createdAt, updatedAt = updatedAt
)

private fun ChatMessage.toSnapshot() = ChatMessageSnapshot(
    id = id, companionId = companionId, content = content, isFromUser = isFromUser,
    timestamp = timestamp, type = type.name, searchContent = searchContent,
    fileFormat = fileFormat.name, linkString = linkString,
    turnId = turnId, eventIndex = eventIndex, durationMs = durationMs,
    anchorMessageId = anchorMessageId
)

private fun com.yunian.ai.database.model.ChatGroup.toSnapshot() = ChatGroupSnapshot(
    id = id, name = name, avatarUrl = avatarUrl, companionIds = companionIds,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun GroupMessage.toSnapshot() = GroupMessageSnapshot(
    id = id, groupId = groupId, companionId = companionId, content = content,
    timestamp = timestamp, searchContent = searchContent,
    fileFormat = fileFormat.name, linkString = linkString
)

private fun com.yunian.ai.database.model.MemoryEntry.toDecryptedSnapshot(): MemoryEntrySnapshot {
    val decryptedContext = try {
        MemoryCrypto.decrypt(context)
    } catch (_: Exception) {
        context
    }
    return MemoryEntrySnapshot(
        id = id, companionId = companionId, content = content, category = category.name,
        importance = importance, context = decryptedContext, accessCount = accessCount,
        timestamp = timestamp, lastAccessed = lastAccessed, deviceId = deviceId
    )
}

private fun com.yunian.ai.database.model.TempMemory.toSnapshot() = TempMemorySnapshot(
    id = id, companionId = companionId, userInput = userInput, botResponse = botResponse,
    timestamp = timestamp, deviceId = deviceId
)

private fun com.yunian.ai.database.model.TokenUsage.toSnapshot() = TokenUsageSnapshot(
    id = id, companionId = companionId, date = date, inputTokens = inputTokens,
    outputTokens = outputTokens, totalTokens = totalTokens, requestCount = requestCount,
    timestamp = timestamp, deviceId = deviceId
)

private fun com.yunian.ai.database.model.MemoryRecord.toSnapshot() = UnifiedMemorySnapshot(
    id = id, memoryType = memoryType.name, scope = scope.name, source = source.name,
    content = content, summary = summary, confidence = confidence, importance = importance,
    sourceId = sourceId, createdAt = createdAt, updatedAt = updatedAt, observedAt = observedAt,
    expiresAt = expiresAt, accessCount = accessCount, tags = tags, deviceId = deviceId
)

private fun com.yunian.ai.database.model.DiaryEntry.toSnapshot() = DiarySnapshot(
    id = id, companionId = companionId, title = title, content = content, mood = mood,
    date = date, weather = weather, tags = tags, deviceId = deviceId
)
