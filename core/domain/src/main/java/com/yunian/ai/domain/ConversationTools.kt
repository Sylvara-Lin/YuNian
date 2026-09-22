package com.yunian.ai.domain

data class ConversationSummary(
    val sessionId: Long,
    val sessionType: String,
    val title: String,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0,
    val companionId: Long? = null,
    val groupId: Long? = null
)

data class ConversationSearchResult(
    val sessionId: Long,
    val sessionType: String,
    val title: String,
    val matchedContent: String,
    val timestamp: Long,
    val role: String
)

data class RecentChatsArgs(
    val limit: Int = 20,
    val includeGroups: Boolean = true
)

data class ConversationSearchArgs(
    val query: String,
    val limit: Int = 10,
    val sessionType: String? = null,
    val companionId: Long? = null,
    val groupId: Long? = null
)
