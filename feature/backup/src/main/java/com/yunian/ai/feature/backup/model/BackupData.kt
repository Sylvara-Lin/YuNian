package com.yunian.ai.feature.backup.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: Int = 1,
    val exportedAt: Long,
    val appVersion: String,
    val companions: List<CompanionSnapshot>,
    val chatMessages: List<ChatMessageSnapshot>,
    val chatGroups: List<ChatGroupSnapshot>,
    val groupMessages: List<GroupMessageSnapshot>,
    val memoryEntries: List<MemoryEntrySnapshot>,
    val tempMemories: List<TempMemorySnapshot>,
    val tokenUsages: List<TokenUsageSnapshot>,
    val unifiedMemories: List<UnifiedMemorySnapshot> = emptyList(),
    val diaries: List<DiarySnapshot> = emptyList()
)

@Serializable
data class CompanionSnapshot(
    val id: Long,
    val name: String,
    val avatarUrl: String? = null,
    val age: Int? = null,
    val personality: String,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val tags: String? = null,
    val rawPrompt: String? = null,
    val systemPrompt: String? = null,
    val intimacy: Int = 0,
    // API 隔离：绑定的 api_configs.id；旧备份无此字段时默认 null（跟随全局）。
    // 注意：API 配置本身不随备份迁移，恢复后若 ID 不存在会自动回退全局配置。
    val apiConfigId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ChatMessageSnapshot(
    val id: Long,
    val companionId: Long,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long,
    val type: String = "TEXT",
    val searchContent: String = "",
    val fileFormat: String = "TEXT",
    val linkString: String = "",

    val turnId: String? = null,
    val eventIndex: Int? = null,
    val durationMs: Long? = null,
    val anchorMessageId: Long? = null
)

@Serializable
data class ChatGroupSnapshot(
    val id: Long,
    val name: String,
    val avatarUrl: String? = null,
    val companionIds: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class GroupMessageSnapshot(
    val id: Long,
    val groupId: Long,
    val companionId: Long,
    val content: String,
    val timestamp: Long,
    val searchContent: String = "",
    val fileFormat: String = "TEXT",
    val linkString: String = ""
)

@Serializable
data class MemoryEntrySnapshot(
    val id: Long,
    val companionId: Long,
    val content: String,
    val category: String = "FACT",
    val importance: Float = 0.5f,
    val context: String = "",
    val accessCount: Int = 1,
    val timestamp: Long,
    val lastAccessed: Long,
    val deviceId: String = ""
)

@Serializable
data class TempMemorySnapshot(
    val id: Long,
    val companionId: Long,
    val userInput: String,
    val botResponse: String,
    val timestamp: Long,
    val deviceId: String = ""
)

@Serializable
data class TokenUsageSnapshot(
    val id: Long,
    val companionId: Long,
    val date: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val requestCount: Int = 0,
    val timestamp: Long,
    val deviceId: String = ""
)

@Serializable
data class UnifiedMemorySnapshot(
    val id: Long,
    val memoryType: String,
    val scope: String,
    val source: String = "CHAT",
    val content: String,
    val summary: String = "",
    val confidence: Float = 1.0f,
    val importance: Float = 0.5f,
    val sourceId: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val observedAt: Long = 0L,
    val expiresAt: Long? = null,
    val accessCount: Int = 1,
    val tags: String = "",
    val deviceId: String = ""
)

@Serializable
data class DiarySnapshot(
    val id: Long,
    val companionId: Long,
    val title: String = "",
    val content: String,
    val mood: Int = 2,
    val date: Long,
    val weather: String = "",
    val tags: String = "",
    val deviceId: String = ""
)
