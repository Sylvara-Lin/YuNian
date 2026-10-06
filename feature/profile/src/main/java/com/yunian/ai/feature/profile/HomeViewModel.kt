package com.yunian.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.HomeListCache
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.ConversationSummary
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.domain.HomeSessionListOperator
import com.yunian.ai.domain.HomeSessionType
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val summaryDao = AppDatabase.getDatabase(application).conversationSummaryDao()
    private val sessionOperator =
        ServiceRegistry.getOrThrow(HomeSessionListOperator::class.java)

    sealed class UiState {
        object Loading : UiState()
        data class Ready(val items: List<ChatListItem>) : UiState()
        data class Error(val message: String) : UiState()
    }

    val chatListState: StateFlow<UiState>

    private var cachedItems: List<ChatListItem> = emptyList()

    init {
        val initialState = if (HomeListCache.isWarmed()) {
            buildReady(
                HomeListCache.snapshotCompanions(),
                HomeListCache.snapshotChatSummaries()
            )
        } else {
            UiState.Loading
        }

        chatListState = combine(
            companionRepository.getAllCompanions(),
            summaryDao.getSummariesByType("chat"),
            sessionOperator.observeHiddenAt(HomeSessionType.CHAT),
            sessionOperator.observePinned(HomeSessionType.CHAT),
            sessionOperator.observePinnedAtMs(HomeSessionType.CHAT)
        ) { companions, summaries, hiddenAt, pinnedAt, pinnedAtMsMap ->
            HomeListCache.putCompanions(companions)
            HomeListCache.putChatSummaries(summaries)
            buildReady(companions, summaries, hiddenAt, pinnedAt, pinnedAtMsMap) as UiState
        }
            .catch { e ->

                emit(UiState.Error(e.message?.take(80) ?: "加载会话失败"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = initialState
            )
    }

    fun markCompanionAsRead(companionId: Long) {
        viewModelScope.launch {
            chatRepository.markReadThroughLatest(companionId)
        }
    }

    // ── 首页会话长按操作（删除 / 顶置 / 隐藏）──
    // 语义与微信对齐（2026-10-06 需求定稿，见 HomeSessionListOperator KDoc）：
    // 删除=清消息+摘要（AI 主动消息落库时摘要重建、会话重显）；隐藏=只藏入口，
    // 摘要 lastMessageTimestamp 晚于隐藏时刻（有新消息，含 AI 主动消息）即重显。
    fun deleteChat(companionId: Long) {
        viewModelScope.launch {
            sessionOperator.deleteConversation(companionId, HomeSessionType.CHAT)
        }
    }

    fun togglePinChat(companionId: Long) {
        viewModelScope.launch {
            sessionOperator.togglePinned(companionId, HomeSessionType.CHAT)
        }
    }

    fun hideChat(companionId: Long) {
        viewModelScope.launch {
            sessionOperator.hideConversation(companionId, HomeSessionType.CHAT)
        }
    }

    private fun buildReady(
        companions: List<CompanionEntity>,
        summaries: List<ConversationSummary>,
        hiddenAt: Map<Long, Long> = emptyMap(),
        pinnedAt: Map<Long, Boolean> = emptyMap(),
        pinnedAtMsMap: Map<Long, Long> = emptyMap()
    ): UiState.Ready {
        val summariesById = summaries.associateBy { it.sessionId }
        val items = companions.map { companion ->
            val summary = summariesById[companion.id]
            val lastMessage = summary?.let {
                ChatMessage(
                    companionId = companion.id,
                    content = it.lastMessagePreview,
                    isFromUser = it.lastMessageIsFromUser,
                    timestamp = it.lastMessageTimestamp
                )
            }
            val isPinned = pinnedAt[companion.id] == true
            ChatListItem(
                companion = companion,
                lastMessage = lastMessage,
                hasUnread = (summary?.unreadCount ?: 0) > 0,
                isPinned = isPinned,
                pinnedAtMs = if (isPinned) pinnedAtMsMap[companion.id] ?: 0L else 0L,
                isHidden = isHiddenNow(companion.id, summary, hiddenAt)
            )
        }
        // 性能优化：内容相同则不更新引用（ChatListItem 是 data class，
        // equals 比较所有字段），避免每次 combine 发射都触发 LazyColumn 全量重组。
        if (items == cachedItems) return UiState.Ready(cachedItems)
        cachedItems = items
        return UiState.Ready(items)
    }

    private fun isHiddenNow(
        sessionId: Long,
        summary: ConversationSummary?,
        hiddenAt: Map<Long, Long>
    ): Boolean {
        val hiddenAtMs = hiddenAt[sessionId] ?: return false
        // 隐藏判定：摘要存在且新消息晚于隐藏时刻 → 已有新消息（AI 主动消息同），重显。
        // 摘要不存在（清记录后）则视为隐藏中——有新消息时摘要重建，比较晚于隐藏时刻即重显。
        val lastMessageTimestamp = summary?.lastMessageTimestamp ?: return true
        return lastMessageTimestamp <= hiddenAtMs
    }
}

data class ChatListItem(
    val companion: CompanionEntity,
    val lastMessage: ChatMessage?,
    val hasUnread: Boolean = false,
    val isPinned: Boolean = false,
    val pinnedAtMs: Long = 0L,
    val isHidden: Boolean = false
)
