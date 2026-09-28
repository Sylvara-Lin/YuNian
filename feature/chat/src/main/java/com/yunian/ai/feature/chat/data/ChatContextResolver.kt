package com.yunian.ai.feature.chat.data

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.filterDecrypted

class ChatContextResolver(
    private val chatRepository: ChatRepository
) {

    companion object {

        const val MAX_AI_CONTEXT_FETCH = ChatConstants.MAX_AI_CONTEXT_FETCH

        const val MAX_UI_MESSAGES = ChatConstants.MAX_UI_MESSAGES

        private const val CONTEXT_CACHE_SIZE = ChatConstants.CONTEXT_CACHE_SIZE
    }

    private val contextCache = object : LinkedHashMap<Long, Pair<Long, List<ChatMessage>>>(
        CONTEXT_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<Long, List<ChatMessage>>>?): Boolean {
            return size > CONTEXT_CACHE_SIZE
        }
    }

    private val cacheLock = Any()

    suspend fun getHistoryForAi(companionId: Long): List<ChatMessage> {
        val limit = MAX_AI_CONTEXT_FETCH
        val lastMessage = chatRepository.getRecentMessagesSync(companionId, 1).firstOrNull()
        val lastMessageId = lastMessage?.id ?: 0L

        synchronized(cacheLock) {
            val cached = contextCache[companionId]
            if (cached != null && cached.first == lastMessageId) {
                SecureLog.d("ChatContextResolver", "AI context cache hit for companion=$companionId")
                return cached.second
            }
        }

        SecureLog.d("ChatContextResolver", "Fetching AI context for companion=$companionId, limit=$limit")
        val history = chatRepository.getRecentMessagesSync(companionId, limit)
            .filterDecrypted()
            .excludeNonDialogue()
        synchronized(cacheLock) {
            contextCache[companionId] = lastMessageId to history
        }
        return history
    }

    suspend fun getShortHistoryForAi(companionId: Long, shortLimit: Int): List<ChatMessage> {
        val effectiveLimit = shortLimit.coerceAtMost(MAX_AI_CONTEXT_FETCH)
        return chatRepository.getRecentMessagesSync(companionId, effectiveLimit)
            .filterDecrypted()
            .excludeNonDialogue()
    }

    /** 剔除过程性消息：推理过程（REASONING）与工具调用卡片（TOOL_ACTIVITY）都不进入 AI 上下文。 */
    private fun List<ChatMessage>.excludeNonDialogue(): List<ChatMessage> =
        filter { it.type != MessageType.REASONING && it.type != MessageType.TOOL_ACTIVITY }

    fun clearCache(companionId: Long) {
        synchronized(cacheLock) {
            contextCache.remove(companionId)
        }
    }

    fun clearAllCache() {
        synchronized(cacheLock) {
            contextCache.clear()
        }
    }

    fun capUiMessages(allMessages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
        if (allMessages.size <= MAX_UI_MESSAGES) return allMessages to false
        val dropped = allMessages.size - MAX_UI_MESSAGES
        SecureLog.w("ChatContextResolver", "UI messages capped: dropped $dropped oldest messages")
        return allMessages.takeLast(MAX_UI_MESSAGES) to true
    }
}
