package com.yunian.ai.feature.memory.engine

import android.content.Context
import android.util.Log
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.DiaryEntry
import com.yunian.ai.database.model.MemoryScope
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.DiaryProvider
import com.yunian.ai.database.repository.EmbeddingProvider
import com.yunian.ai.database.repository.SummaryProvider
import com.yunian.ai.database.repository.UnifiedMemoryRepository
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class UnifiedMemoryProvider(
    context: Context,
    private val deviceId: String,
    embeddingProvider: EmbeddingProvider? = null,
    summaryProvider: SummaryProvider? = null
) : MemoryProvider {

    companion object {
        private const val TAG = "UnifiedMemoryProvider"
            private const val DIARY_TAG = "ai_generated,conversation_summary"
        private const val CORE_RECOGNITION_INTERVAL_MS = 3 * 60_000L
    }

    private val lastCoreRecognition = ConcurrentHashMap<String, Long>()

    private val memoryScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    private val repository: UnifiedMemoryRepository
        private val database = AppDatabase.getDatabase(context.applicationContext)
        private val diaryDao = database.diaryDao()
        private val companionRepository = CompanionRepository(database.companionDao())

    init {
        repository = UnifiedMemoryRepository(database.unifiedMemoryDao(), deviceId, embeddingProvider, summaryProvider)
    }

    override fun initialize() {

        Log.d(TAG, "UnifiedMemoryProvider initialized (deviceId=${deviceId.take(8)})")
    }

    override suspend fun getMemoryContext(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int
    ): String {
        return runCatching {
            val parts = mutableListOf<String>()

            val globalContext = repository.buildMemoryContext(
                scope = MemoryScope.GLOBAL,
                sourceId = 0L,
                userQuery = query,
                limit = limit / 2
            )
            if (globalContext.isNotBlank()) parts.add(globalContext)

            val scopeContext = when {
                companionId != null -> repository.buildMemoryContext(
                    scope = MemoryScope.COMPANION,
                    sourceId = companionId,
                    userQuery = query,
                    limit = limit
                )
                groupId != null -> repository.buildMemoryContext(
                    scope = MemoryScope.GROUP,
                    sourceId = groupId,
                    userQuery = query,
                    limit = limit
                )
                else -> ""
            }
            if (scopeContext.isNotBlank()) parts.add(scopeContext)

            if (parts.isEmpty()) "" else parts.joinToString("\n")
        }.onFailure { Log.e(TAG, "获取记忆上下文失败", it) }
            .getOrElse { "" }
    }

    override suspend fun extractAndSaveFromConversation(
        userInput: String,
        aiResponse: String,
        companionId: Long,
        groupId: Long?
    ) {
        val scope = if (groupId != null) MemoryScope.GROUP else MemoryScope.COMPANION
        val sourceId = groupId ?: companionId

        // 解析 AI 所扮演角色的名字，用于让记忆以角色第一人称视角书写。
        // 群聊（groupId != null）没有单一角色身份，selfName 置 null 走中性降级路径。
        // 本函数每条消息都会触发，故角色名在此入口只查询一次并全程复用。
        val selfName = if (groupId != null) {
            null
        } else {
            runCatching { companionRepository.getCompanionById(companionId)?.name?.trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }

        runCatching {
            repository.extractAndSaveMemories(
                scope = scope,
                sourceId = sourceId,
                userInput = userInput,
                aiResponse = aiResponse,
                selfName = selfName
            )
        }.onFailure { Log.e(TAG, "提取记忆失败", it) }

        if (scope != MemoryScope.GLOBAL) {
            runCatching {
                repository.extractAndSaveMemories(
                    scope = MemoryScope.GLOBAL,
                    sourceId = 0L,
                    userInput = userInput,
                    aiResponse = aiResponse,
                    // 全局记忆跨角色共享，没有单一角色身份，强制中性降级。
                    selfName = null
                )
            }.onFailure { Log.e(TAG, "提取全局记忆失败", it) }
        }

        memoryScope.launch {
            runCatching { repository.postProcessMemories(scope, sourceId, selfName) }
                .onFailure { Log.e(TAG, "记忆后处理失败", it) }

            recognizeCoreMemoriesThrottled(userInput, aiResponse, scope, sourceId, selfName)
            if (scope != MemoryScope.GLOBAL) {
                runCatching { repository.postProcessMemories(MemoryScope.GLOBAL, 0L, selfName = null) }
                    .onFailure { Log.e(TAG, "全局记忆后处理失败", it) }
                recognizeCoreMemoriesThrottled(userInput, aiResponse, MemoryScope.GLOBAL, 0L, selfName = null)
            }
            if (groupId == null) {
                runCatching {
                    generateConversationDiary(
                        companionId = companionId,
                        userInput = userInput,
                        aiResponse = aiResponse
                    )
                }.onFailure { Log.e(TAG, "日记生成失败", it) }
            }
        }
    }

    private suspend fun recognizeCoreMemoriesThrottled(
        userInput: String,
        aiResponse: String,
        scope: MemoryScope,
        sourceId: Long,
        selfName: String? = null
    ) {
        val key = "$scope:$sourceId"
        val now = System.currentTimeMillis()
        val last = lastCoreRecognition[key] ?: 0L
        if (now - last < CORE_RECOGNITION_INTERVAL_MS) return
        lastCoreRecognition[key] = now

        val aiLabel = selfName?.trim()?.takeIf { it.isNotEmpty() } ?: "AI"
        runCatching {
            repository.recognizeCoreMemories(
                conversationText = "用户: $userInput\n$aiLabel: $aiResponse",
                scope = scope,
                sourceId = sourceId,
                selfName = selfName
            )
        }.onFailure { Log.e(TAG, "核心记忆识别失败", it) }
    }

    private suspend fun generateConversationDiary(
        companionId: Long,
        userInput: String,
        aiResponse: String
    ) {
        val diaryProvider = ServiceRegistry.get(DiaryProvider::class.java) ?: return
        val companion = companionRepository.getCompanionById(companionId) ?: return

        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = calendar.timeInMillis
        val existingToday = diaryDao.getDiariesForCompanionSync(companionId, deviceId)
            .firstOrNull { it.date in todayStart until (todayStart + 86_400_000L) && it.tags.contains(DIARY_TAG) }
        if (existingToday != null) return

        val conversationSummary = buildConversationSummary(companion.name, userInput, aiResponse)
        val memoryContext = repository.buildMemoryContext(
            scope = MemoryScope.COMPANION,
            sourceId = companionId,
            userQuery = userInput,
            limit = 8
        )
        val diaryContent = diaryProvider.generateDiary(companion, conversationSummary, memoryContext)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        diaryDao.insertDiary(
            DiaryEntry(
                companionId = companionId,
                title = buildDiaryTitle(),
                content = diaryContent,
                mood = 2,
                date = System.currentTimeMillis(),
                tags = DIARY_TAG,
                deviceId = deviceId
            )
        )
    }

    private fun buildConversationSummary(
        companionName: String,
        userInput: String,
        aiResponse: String
    ): String {
        return buildString {
            append("用户说：").append(userInput.trim()).append('\n')
            append(companionName).append("回应：").append(aiResponse.trim()).append('\n')
            append("请提炼这次互动里的情绪变化、被触动的点、未说出口的期待与亲密感。")
        }
    }

    private fun buildDiaryTitle(): String {
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return "聊天后的心情 ${formatter.format(Date())}"
    }
}
