package com.yunian.ai.domain.timeline

data class ConversationRef(
    val conversationId: Long,
    val conversationType: String,

    val senderId: Long = 0L,
) {
    init {
        require(conversationType == "chat" || conversationType == "group") {
            "conversationType must be chat|group"
        }
    }
}

interface TimelineStore {

    suspend fun appendComplete(scope: ConversationRef, event: TimelineEvent): Long

    suspend fun loadTurn(turnId: TurnId): List<TimelineEvent>

    suspend fun loadConversationEvents(
        conversationId: Long,
        conversationType: String,
        limit: Int,
        kinds: Set<TimelineEventKind>? = null,
    ): List<TimelineEvent>
}
