package com.yunian.ai.feature.memory

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.DiaryEntry
import com.yunian.ai.database.model.MemoryCategory
import com.yunian.ai.database.model.MemoryRecord
import com.yunian.ai.database.model.MemoryScope
import com.yunian.ai.database.model.MemorySource
import com.yunian.ai.database.model.MemoryType
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ChatMessageCrypto
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.DiaryProvider
import com.yunian.ai.database.repository.UnifiedMemoryRepository
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryRepository: UnifiedMemoryRepository
    private val companionRepository: CompanionRepository
    private val diaryDao = AppDatabase.getDatabase(application).diaryDao()
    private val messageDao = AppDatabase.getDatabase(application).messageDao()
    private val deviceId = DeviceIdProvider.getDeviceId(application)
    private val stableMemoryFlows = mutableMapOf<Long, Flow<List<MemoryRecord>>>()
    private val workingMemoryFlows = mutableMapOf<Long, Flow<List<MemoryRecord>>>()
    private val diaryFlows = mutableMapOf<Long, Flow<List<DiaryEntry>>>()

    private val _isGeneratingDiary = MutableStateFlow(false)
    val isGeneratingDiary: StateFlow<Boolean> = _isGeneratingDiary.asStateFlow()

    val companions: Flow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        memoryRepository = UnifiedMemoryRepository(database.unifiedMemoryDao(), deviceId)
        companionRepository = CompanionRepository(database.companionDao())
        companions = companionRepository.getAllCompanions()
    }

    fun getMemoriesForCompanion(companionId: Long): Flow<List<MemoryRecord>> {
        return stableMemoryFlows.getOrPut(companionId) {
            memoryRepository.getStableMemories(MemoryScope.COMPANION, companionId)
        }
    }

    fun getTempMemoriesForCompanion(companionId: Long): Flow<List<MemoryRecord>> {
        return workingMemoryFlows.getOrPut(companionId) {
            memoryRepository.getWorkingMemories(MemoryScope.COMPANION, companionId, limit = 20)
        }
    }

    fun deleteMemory(memory: MemoryRecord) {
        viewModelScope.launch {
            memoryRepository.softDelete(memory.id)
        }
    }

    fun updateMemory(memory: MemoryRecord) {
        viewModelScope.launch {
            memoryRepository.updateMemory(memory)
        }
    }

    fun addManualMemory(
        companionId: Long,
        content: String,
        category: MemoryCategory = MemoryCategory.FACT,
        importance: Float = 0.7f,
        context: String = ""
    ) {
        viewModelScope.launch {
            memoryRepository.addMemory(
                content = content,
                type = category.toMemoryType(),
                scope = MemoryScope.COMPANION,
                sourceId = companionId,
                source = MemorySource.MANUAL,
                importance = importance,
                confidence = 1.0f,
                summary = context,
                tags = category.name.lowercase()
            )
        }
    }

    fun deleteMemoriesForCompanion(companionId: Long) {
        viewModelScope.launch {
            memoryRepository.softDeleteByScopeAndSource(
                scope = MemoryScope.COMPANION,
                sourceId = companionId,
                source = MemorySource.MANUAL
            )
        }
    }

    private fun MemoryCategory.toMemoryType(): MemoryType = when (this) {
        MemoryCategory.FACT -> MemoryType.SEMANTIC
        MemoryCategory.EMOTION -> MemoryType.EPISODIC
        MemoryCategory.PREFERENCE -> MemoryType.PREFERENCE
        MemoryCategory.EVENT -> MemoryType.EPISODIC
        MemoryCategory.HABIT -> MemoryType.PROCEDURAL
        MemoryCategory.RELATIONSHIP -> MemoryType.RELATIONSHIP
    }

    fun getDiariesForCompanion(companionId: Long): Flow<List<DiaryEntry>> {
        return diaryFlows.getOrPut(companionId) {
            diaryDao.getDiariesForCompanion(companionId, deviceId)
        }
    }

    fun addDiary(
        companionId: Long,
        title: String,
        content: String,
        mood: Int = 2,
        weather: String = "",
        tags: String = "",
        date: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            val diary = DiaryEntry(
                companionId = companionId,
                title = title.trim(),
                content = content.trim(),
                mood = mood,
                weather = weather.trim(),
                tags = tags.trim(),
                date = date,
                deviceId = deviceId
            )
            diaryDao.insertDiary(diary)
        }
    }

    fun updateDiary(diary: DiaryEntry) {
        viewModelScope.launch {
            diaryDao.insertDiary(diary)
        }
    }

    fun deleteDiary(diary: DiaryEntry) {
        viewModelScope.launch {
            diaryDao.deleteDiary(diary)
        }
    }

    fun deleteDiariesForCompanion(companionId: Long) {
        viewModelScope.launch {
            diaryDao.deleteDiariesForCompanion(companionId, deviceId)
        }
    }

    fun generateDiary(companionId: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isGeneratingDiary.value = true
            try {
                val diaryProvider = ServiceRegistry.getOrThrow(DiaryProvider::class.java)
                val companion = companionRepository.getCompanionById(companionId)
                if (companion == null) {
                    Log.w(TAG, "generateDiary: companion not found, id=$companionId")
                    onResult(null)
                    return@launch
                }

                val recentMessages = withContext(Dispatchers.IO) {
                    ChatMessageCrypto.decryptFromStorage(
                        messageDao.getRecentMessagesSync(companionId, "chat", 50)
                            .map { it.toChatMessage() }
                    )
                        // 工具调用卡片是过程可视化消息，不进入日记素材，避免 JSON 污染日记。
                        .filterNot { it.type == MessageType.TOOL_ACTIVITY }
                        .reversed()
                }
                if (recentMessages.isEmpty()) {
                    Log.w(TAG, "generateDiary: no chat messages for companion=$companionId")
                    onResult(null)
                    return@launch
                }

                val conversationText = formatConversation(recentMessages, companion)

                val memoryContext = buildMemoryContext(companion)

                val diaryText = withContext(Dispatchers.IO) {
                    diaryProvider.generateDiary(companion, conversationText, memoryContext)
                }
                onResult(diaryText)
            } catch (e: Exception) {
                Log.e(TAG, "generateDiary failed", e)
                onResult(null)
            } finally {
                _isGeneratingDiary.value = false
            }
        }
    }

    private fun formatConversation(messages: List<ChatMessage>, companion: CompanionEntity): String {
        val sb = StringBuilder()
        for (msg in messages) {
            val speaker = if (msg.isFromUser) "用户" else companion.name
            sb.append(speaker).append(": ").append(msg.content).append("\n")
        }
        return sb.toString().trim()
    }

    private suspend fun buildMemoryContext(companion: CompanionEntity): String {
        val sb = StringBuilder()
        sb.append("角色名: ").append(companion.name).append("\n")
        if (companion.personality.isNotBlank()) {
            sb.append("性格: ").append(companion.personality).append("\n")
        }
        if (!companion.backstory.isNullOrBlank()) {
            sb.append("背景: ").append(companion.backstory).append("\n")
        }
        if (!companion.speakingStyle.isNullOrBlank()) {
            sb.append("说话风格: ").append(companion.speakingStyle).append("\n")
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "MemoryViewModel"
    }
}
