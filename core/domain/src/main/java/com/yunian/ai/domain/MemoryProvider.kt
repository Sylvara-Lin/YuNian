package com.yunian.ai.domain

interface MemoryProvider {

    fun initialize()

    suspend fun getMemoryContext(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int = 5
    ): String

    suspend fun recallMemory(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int = 5
    ): String = getMemoryContext(companionId, groupId, query, limit)

    suspend fun extractAndSaveFromConversation(
        userInput: String,
        aiResponse: String,
        companionId: Long,
        groupId: Long? = null
    )
}
