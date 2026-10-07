package com.yunian.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.HomeListCache
import com.yunian.ai.database.model.ChatGroup
import com.yunian.ai.database.repository.ChatGroupRepository
import com.yunian.ai.domain.HomeSessionListOperator
import com.yunian.ai.domain.HomeSessionType
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatGroupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChatGroupRepository
    private val sessionOperator =
        ServiceRegistry.getOrThrow(HomeSessionListOperator::class.java)
    private val summaryDao = AppDatabase.getDatabase(application).conversationSummaryDao()

    /** 群列表（按群更新时间倒序，既有行为）。 */
    val groups: StateFlow<List<ChatGroup>>

    /** 首页展示用群条目：组信息 + 顶置/隐藏状态（长按菜单数据源）。 */
    val groupItems: StateFlow<List<HomeGroupItem>>

    private var cachedGroupItems: List<HomeGroupItem> = emptyList()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatGroupRepository(database.chatGroupDao())
        groups = repository.getAllGroups()
            .onEach { HomeListCache.putGroups(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = HomeListCache.snapshotGroups()
            )
        groupItems = combine(
            repository.getAllGroups(),
            summaryDao.getSummariesByType("group"),
            sessionOperator.observeHiddenAt(HomeSessionType.GROUP),
            sessionOperator.observePinned(HomeSessionType.GROUP),
            sessionOperator.observePinnedAtMs(HomeSessionType.GROUP)
        ) { groupList, summaries, hiddenAt, pinnedAt, pinnedAtMsMap ->
            val summariesById = summaries.associateBy { it.sessionId }
            val items = groupList.map { group ->
                val summary = summariesById[group.id]
                val hiddenAtMs = hiddenAt[group.id]
                // 与单聊同判定：有新消息（lastMessageTimestamp > hiddenAtMs，含 AI 主动消息）即重显；
                // 无摘要（记录已清）则视为隐藏中，新消息落库时摘要重建自然重显。
                val isHidden = if (hiddenAtMs == null) false
                else summary == null || summary.lastMessageTimestamp <= hiddenAtMs
                val isPinned = pinnedAt[group.id] == true
                HomeGroupItem(
                    group = group,
                    isPinned = isPinned,
                    pinnedAtMs = if (isPinned) pinnedAtMsMap[group.id] ?: 0L else 0L,
                    // 群聊排序用 conversation_summary.lastMessageTimestamp（群聊最后消息时间），
                    // 不是 group.updatedAt（群信息更新时间）——群聊收到消息后 summary 更新，
                    // group.updatedAt 不更新，用 group.updatedAt 会导致群聊收到消息后不按微信规则排序。
                    lastMessageTimestamp = summary?.lastMessageTimestamp ?: group.updatedAt,
                    isHidden = isHidden
                )
            }
            // 性能优化：内容相同则不更新引用，避免每次 combine 发射都触发全量重组。
            if (items == cachedGroupItems) cachedGroupItems else items.also { cachedGroupItems = it }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )
    }

    fun createGroup(name: String, companionIds: List<Long>) {
        viewModelScope.launch {
            val group = ChatGroup(
                name = name,
                companionIds = companionIds.joinToString(",")
            )
            repository.insertGroup(group)
        }
    }

    fun deleteGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.deleteGroup(group)
        }
    }

    fun updateGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.updateGroup(group)
        }
    }

    // ── 首页群聊长按操作（删除 / 顶置 / 隐藏；语义同单聊，见 HomeSessionListOperator）──
    fun deleteGroupChat(groupId: Long) {
        viewModelScope.launch {
            sessionOperator.deleteConversation(groupId, HomeSessionType.GROUP)
        }
    }

    fun togglePinGroupChat(groupId: Long) {
        viewModelScope.launch {
            sessionOperator.togglePinned(groupId, HomeSessionType.GROUP)
        }
    }

    fun hideGroupChat(groupId: Long) {
        viewModelScope.launch {
            sessionOperator.hideConversation(groupId, HomeSessionType.GROUP)
        }
    }
}

data class HomeGroupItem(
    val group: ChatGroup,
    val isPinned: Boolean = false,
    val pinnedAtMs: Long = 0L,
    val lastMessageTimestamp: Long = 0L,
    val isHidden: Boolean = false
)
