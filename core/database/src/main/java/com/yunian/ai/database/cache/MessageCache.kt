package com.yunian.ai.database.cache

import android.util.LruCache
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.GroupMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.Collections

object MessageCache {

    private const val MESSAGES_PER_SESSION = 500

    private const val MAX_SESSIONS = 8

    private val chatCache = LruCache<Long, SessionCache<ChatMessage>>(MAX_SESSIONS)

    private val groupCache = LruCache<Long, SessionCache<GroupMessage>>(MAX_SESSIONS)

    private val chatSummaryCache = LruCache<Long, SessionSummary>(MAX_SESSIONS * 2)
    private val groupSummaryCache = LruCache<Long, SessionSummary>(MAX_SESSIONS * 2)

    fun getChatMessages(companionId: Long): List<ChatMessage>? =
        chatCache.get(companionId)?.snapshot()

    fun getChatMessagesById(companionId: Long): Map<Long, ChatMessage>? =
        chatCache.get(companionId)?.snapshotById()

    fun observeChatMessages(companionId: Long): StateFlow<List<ChatMessage>> =
        chatCache.get(companionId)?.state ?: SessionCache<ChatMessage>({ it.id }).also {
            chatCache.put(companionId, it)
        }.state

    fun putChatMessages(companionId: Long, messages: List<ChatMessage>) {
        val existingStreaming = chatCache.get(companionId)
            ?.snapshot()
            .orEmpty()
            .filter { it.id < 0L }
        val merged = if (existingStreaming.isEmpty()) {
            messages
        } else {
            val incomingIds = messages.mapTo(HashSet(messages.size)) { it.id }
            val keepStreaming = existingStreaming.filter { it.id !in incomingIds }
            if (keepStreaming.isEmpty()) {
                messages
            } else {
                (messages + keepStreaming)
                    .distinctBy { it.id }
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
            }
        }
        chatCache.get(companionId)?.replace(merged)
            ?: chatCache.put(companionId, SessionCache({ it.id }, merged))
    }

    fun appendChatMessage(companionId: Long, message: ChatMessage) {
        chatCache.get(companionId)?.let { session ->
            session.append(message)
        }
    }

    fun updateChatMessage(companionId: Long, messageId: Long, transformer: (ChatMessage) -> ChatMessage) {
        chatCache.get(companionId)?.let { session ->
            session.update { if (it.id == messageId) transformer(it) else it }
        }
    }

    fun removeChatMessage(companionId: Long, messageId: Long) {
        chatCache.get(companionId)?.let { session ->
            session.remove { it.id == messageId }
        }
    }

    fun evictChat(companionId: Long) {
        chatCache.remove(companionId)
        chatSummaryCache.remove(companionId)
    }

    fun getGroupMessages(groupId: Long): List<GroupMessage>? {
        return groupCache.get(groupId)?.snapshot()
    }

    fun getGroupMessagesById(groupId: Long): Map<Long, GroupMessage>? =
        groupCache.get(groupId)?.snapshotById()

    fun observeGroupMessages(groupId: Long): StateFlow<List<GroupMessage>> =
        groupCache.get(groupId)?.state ?: SessionCache<GroupMessage>({ it.id }).also {
            groupCache.put(groupId, it)
        }.state

    fun putGroupMessages(groupId: Long, messages: List<GroupMessage>) {
        groupCache.get(groupId)?.replace(messages)
            ?: groupCache.put(groupId, SessionCache({ it.id }, messages))
    }

    fun appendGroupMessage(groupId: Long, message: GroupMessage) {
        groupCache.get(groupId)?.let { session ->
            session.append(message)
        }
    }

    fun updateGroupMessage(groupId: Long, messageId: Long, transformer: (GroupMessage) -> GroupMessage) {
        groupCache.get(groupId)?.let { session ->
            session.update { if (it.id == messageId) transformer(it) else it }
        }
    }

    fun removeGroupMessage(groupId: Long, messageId: Long) {
        groupCache.get(groupId)?.let { session ->
            session.remove { it.id == messageId }
        }
    }

    fun evictGroup(groupId: Long) {
        groupCache.remove(groupId)
        groupSummaryCache.remove(groupId)
    }

    data class SessionSummary(
        val lastMessagePreview: String,
        val lastMessageTimestamp: Long,
        val lastMessageIsFromUser: Boolean,
        val unreadCount: Int = 0
    )

    fun getChatSummary(companionId: Long): SessionSummary? = chatSummaryCache.get(companionId)

    fun putChatSummary(companionId: Long, summary: SessionSummary) {
        chatSummaryCache.put(companionId, summary)
    }

    fun getGroupSummary(groupId: Long): SessionSummary? = groupSummaryCache.get(groupId)

    fun putGroupSummary(groupId: Long, summary: SessionSummary) {
        groupSummaryCache.put(groupId, summary)
    }

    fun clearAll() {
        chatCache.evictAll()
        groupCache.evictAll()
        chatSummaryCache.evictAll()
        groupSummaryCache.evictAll()
    }

    fun stats(): String {
        return "chatSessions=${chatCache.size()}, groupSessions=${groupCache.size()}, " +
            "chatSummaries=${chatSummaryCache.size()}, groupSummaries=${groupSummaryCache.size()}"
    }

    private class SessionCache<T>(
        private val idOf: (T) -> Long,
        messages: List<T> = emptyList()
    ) {
        private val deque = ArrayDeque<T>(MESSAGES_PER_SESSION)
        private val mutableState = MutableStateFlow<List<T>>(emptyList())
        @Volatile
        private var publishedSnapshot: List<T> = emptyList()
        @Volatile
        private var publishedById: Map<Long, T> = emptyMap()
        val state: StateFlow<List<T>> = mutableState

        init {
            replace(messages)
        }

        fun snapshot(): List<T> = publishedSnapshot

        fun snapshotById(): Map<Long, T> = publishedById

        @Synchronized
        fun replace(messages: List<T>) {
            deque.clear()
            messages.takeLast(MESSAGES_PER_SESSION).forEach(deque::addLast)
            publish()
        }

        @Synchronized
        fun append(message: T) {
            val msgId = idOf(message)

            if (deque.any { idOf(it) == msgId }) {
                deque.removeIf { idOf(it) == msgId }
            } else if (deque.size == MESSAGES_PER_SESSION) {
                deque.removeFirst()
            }
            deque.addLast(message)
            publish()
        }

        @Synchronized
        fun update(transformer: (T) -> T) {
            val updated = deque.map(transformer)
            deque.clear()
            updated.forEach(deque::addLast)
            publish()
        }

        @Synchronized
        fun remove(predicate: (T) -> Boolean) {
            deque.removeIf(predicate)
            publish()
        }

        private fun publish() {
            val snapshot = Collections.unmodifiableList(ArrayList(deque))
            publishedSnapshot = snapshot
            publishedById = Collections.unmodifiableMap(snapshot.associateBy(idOf))
            mutableState.value = snapshot
        }
    }
}
