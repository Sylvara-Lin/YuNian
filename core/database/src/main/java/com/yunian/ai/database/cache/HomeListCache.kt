package com.yunian.ai.database.cache

import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatGroup
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.ConversationSummary
import java.util.concurrent.atomic.AtomicBoolean

object HomeListCache {
    @Volatile
    private var companions: List<CompanionEntity> = emptyList()

    @Volatile
    private var groups: List<ChatGroup> = emptyList()

    @Volatile
    private var chatSummaries: List<ConversationSummary> = emptyList()

    private val warmed = AtomicBoolean(false)

    fun isWarmed(): Boolean = warmed.get()

    fun snapshotCompanions(): List<CompanionEntity> = companions

    fun snapshotGroups(): List<ChatGroup> = groups

    fun snapshotChatSummaries(): List<ConversationSummary> = chatSummaries

    fun putCompanions(list: List<CompanionEntity>) {
        companions = list
        if (list.isNotEmpty()) {
            warmed.set(true)
        }
    }

    fun putGroups(list: List<ChatGroup>) {
        groups = list
    }

    fun putChatSummaries(list: List<ConversationSummary>) {
        chatSummaries = list
    }

    suspend fun warm(database: AppDatabase) {
        companions = database.companionDao().getAllCompanionsSync()
        groups = database.chatGroupDao().getAllGroupsSync()
        chatSummaries = database.conversationSummaryDao().getSummariesByTypeSync("chat")
        warmed.set(true)
    }

    fun clear() {
        companions = emptyList()
        groups = emptyList()
        chatSummaries = emptyList()
        warmed.set(false)
    }
}
