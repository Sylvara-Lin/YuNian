package com.yunian.ai.feature.groupchat

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.database.model.GroupMessage
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.repository.GroupMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 群聊「分页 + 正文懒加载」状态机。
 *
 * 从 [GroupChatViewModel] 外提（上帝类拆分第二轮）。原实现散落在：
 * init 种子块 / `seedBodiesFromCache` / `GroupMessage.toMetadataMessage` /
 * `loadVisibleMessageBodies` / `retryMessageBody` / `publishLoadedMessages` / `loadMoreMessages`。
 *
 * 外提前做过**全量依赖闭包分析**（非 grep 抽样）：该簇只读写自己的 5 个状态流
 * （messages / messageMetadata / messageBodies / hasMore / isLoadingMore），
 * 外部依赖仅 `groupMessageRepository`、`groupId` 与一个协程作用域；
 * ViewModel 其余成员（用户资料、群数据、成员表、发送循环、记忆、AI、保活）零交叉，
 * 因此可以整体外提而不改变任何行为。
 *
 * 纯逻辑（元数据合并去重、hasMore 计算、正文播种与投影、待加载筛选、状态迁移）
 * 全部收敛到 [Companion]，可脱离 Android 直测（见 GroupChatPagerTest）。
 */
internal class GroupChatPager(
    private val scope: CoroutineScope,
    private val groupId: Long,
    private val repository: GroupMessageRepository
) {
    private val cachedRecent: List<GroupMessage> = repository.getCachedRecent(groupId).orEmpty()

    private val _messages = MutableStateFlow(cachedRecent)
    val messages: StateFlow<List<GroupMessage>> = _messages.asStateFlow()

    private val _messageMetadata = MutableStateFlow(
        cachedRecent.takeLast(ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
            .map { toMetadataMessage(it, groupId) }
    )
    val messageMetadata: StateFlow<List<Message>> = _messageMetadata.asStateFlow()

    private val _messageBodies =
        MutableStateFlow<Map<Long, MessageBodyState<GroupMessage>>>(
            cachedRecent.associate { it.id to MessageBodyState.Ready(it) }
        )
    val messageBodies: StateFlow<Map<Long, MessageBodyState<GroupMessage>>> = _messageBodies.asStateFlow()

    private val _hasMore = MutableStateFlow(
        initialHasMore(cachedRecent.size, ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
    )
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** 原 GroupChatViewModel 的 init 种子块 + 实时元数据订阅（行为逐行等价）。 */
    fun start() {
        scope.launch(Dispatchers.IO) {
            var metadataSeeded = cachedRecent.isNotEmpty()
            if (!metadataSeeded) {
                runCatching {
                    repository.hydrateRecent(groupId, ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
                }
                val hydrated = repository.getCachedRecent(groupId).orEmpty()
                if (hydrated.isNotEmpty()) {
                    _messages.value = hydrated
                    _messageBodies.value = hydrated.associate { it.id to MessageBodyState.Ready(it) }
                    _messageMetadata.value = hydrated
                        .takeLast(ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
                        .map { toMetadataMessage(it, groupId) }
                    metadataSeeded = true
                }
            }

            if (!metadataSeeded) {
                val recentMetadata = repository
                    .getRecentMetadata(groupId, ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
                    .reversed()
                _messageMetadata.value = recentMetadata
                seedBodiesFromCache(recentMetadata)
            }
            _hasMore.value = hasMoreAfterSeed(
                _messageMetadata.value.size,
                repository.getMessageCount(groupId)
            )
            repository.observeRecentMetadata(
                groupId,
                ChatConstants.GROUP_CHAT_MESSAGE_LIMIT
            ).collectLatest { recent ->
                val merged = mergeIncomingMetadata(_messageMetadata.value, recent)
                if (merged != _messageMetadata.value) {
                    _messageMetadata.value = merged
                    seedBodiesFromCache(merged)
                }
                _hasMore.value = hasMoreAfterSeed(merged.size, repository.getMessageCount(groupId))
                if (recent.isNotEmpty()) repository.markReadThroughLatest(groupId)
            }
        }
    }

    /** 原 `loadVisibleMessageBodies`：为可见但仍无正文（或上次失败）的条目加载正文。 */
    fun loadVisibleMessageBodies(messageIds: Set<Long>) {
        val pending = pendingBodyIds(messageIds, _messageMetadata.value, _messageBodies.value)
        if (pending.isEmpty()) return

        _messageBodies.value = _messageBodies.value + pending.associate {
            it.id to MessageBodyState.Loading
        }
        scope.launch(Dispatchers.IO) {
            runCatching { repository.loadMessages(pending) }
                .onSuccess { loaded ->
                    _messageBodies.value = applyLoadedResults(_messageBodies.value, pending, loaded)
                    publishLoadedMessages()
                }
                .onFailure { error ->
                    _messageBodies.value = applyLoadFailure(_messageBodies.value, pending, error.message)
                }
        }
    }

    /** 原 `retryMessageBody`：清掉该条的正文状态后按可见加载重试。 */
    fun retryMessageBody(messageId: Long) {
        _messageBodies.value = _messageBodies.value - messageId
        loadVisibleMessageBodies(setOf(messageId))
    }

    /** 原 `loadMoreMessages`：按最旧一条向前翻一页元数据。 */
    fun loadMoreMessages() {
        scope.launch(Dispatchers.IO) {
            if (_isLoadingMore.value) return@launch
            _isLoadingMore.value = true
            try {
                val oldest = _messageMetadata.value.firstOrNull()
                if (oldest != null) {
                    val older = repository.getMetadataBefore(
                        groupId = groupId,
                        beforeTimestamp = oldest.timestamp,
                        beforeId = oldest.id,
                        limit = ChatConstants.GROUP_CHAT_MESSAGE_LIMIT
                    )
                    if (older.isNotEmpty()) {
                        _messageMetadata.value = prependOlder(_messageMetadata.value, older)
                    }
                    _hasMore.value = hasMoreAfterLoadMore(older.size, ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
                } else {
                    val recent = repository.getRecentMetadata(
                        groupId,
                        ChatConstants.GROUP_CHAT_MESSAGE_LIMIT
                    )
                    _messageMetadata.value = recent.reversed()
                    _hasMore.value = hasMoreAfterLoadMore(recent.size, ChatConstants.GROUP_CHAT_MESSAGE_LIMIT)
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private fun seedBodiesFromCache(metadata: List<Message>) {
        if (metadata.isEmpty()) return
        val cachedById = repository.getCachedRecent(groupId)
            ?.associateBy { it.id }
            .orEmpty()
        if (cachedById.isEmpty()) return
        val updated = seedBodies(metadata, cachedById, _messageBodies.value)
        if (updated != _messageBodies.value) {
            _messageBodies.value = updated
            publishLoadedMessages()
        }
    }

    private fun publishLoadedMessages() {
        _messages.value = publishLoaded(_messageMetadata.value, _messageBodies.value)
    }

    internal companion object {

        /** 原 `GroupChatViewModel.GroupMessage.toMetadataMessage`：正文记录 → 列表占位元数据。 */
        internal fun toMetadataMessage(message: GroupMessage, conversationId: Long): Message = Message(
            id = message.id,
            conversationId = conversationId,
            conversationType = "group",
            isFromUser = message.companionId == -1L,
            senderId = message.companionId,
            timestamp = message.timestamp,
            fileFormat = message.fileFormat
        )

        /** 首帧 hasMore：缓存是否已满页（原 `cachedRecent.size >= GROUP_CHAT_MESSAGE_LIMIT`）。 */
        internal fun initialHasMore(cachedSize: Int, limit: Int): Boolean = cachedSize >= limit

        /** 种子/实时流后的 hasMore：可见元数据少于库内总数即还有更多（原 `< getMessageCount`）。 */
        internal fun hasMoreAfterSeed(visibleCount: Int, totalCount: Int): Boolean = visibleCount < totalCount

        /** 上拉分页后的 hasMore：本次取满一页才认为还有更多（原 `== limit` 语义，刻意不同）。 */
        internal fun hasMoreAfterLoadMore(loadedCount: Int, limit: Int): Boolean = loadedCount == limit

        /** 原实时流合并：剔除将被新流覆盖的旧条目 → 倒序拼接 → 按 id 去重 → 时间+id 稳定排序。 */
        internal fun mergeIncomingMetadata(current: List<Message>, recent: List<Message>): List<Message> =
            (current.filterNot { c -> recent.any { it.id == c.id } } + recent.reversed())
                .distinctBy { it.id }
                .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })

        /** 原上拉分页合并：更早一页倒序接在头部，按 id 去重。 */
        internal fun prependOlder(current: List<Message>, older: List<Message>): List<Message> =
            (older.reversed() + current).distinctBy { it.id }

        /** 原 `seedBodiesFromCache` 纯段：仅给尚无 Ready 正文的条目播种缓存正文。 */
        internal fun seedBodies(
            metadata: List<Message>,
            cachedById: Map<Long, GroupMessage>,
            existingBodies: Map<Long, MessageBodyState<GroupMessage>>
        ): Map<Long, MessageBodyState<GroupMessage>> {
            if (metadata.isEmpty() || cachedById.isEmpty()) return existingBodies
            val ready = metadata.mapNotNull { item ->
                val body = cachedById[item.id] ?: return@mapNotNull null
                when (existingBodies[item.id]) {
                    is MessageBodyState.Ready -> null
                    else -> item.id to MessageBodyState.Ready(body)
                }
            }
            return if (ready.isEmpty()) existingBodies else existingBodies + ready
        }

        /** 原 `publishLoadedMessages` 纯段：按元数据顺序投影已就绪正文。 */
        internal fun publishLoaded(
            metadata: List<Message>,
            bodies: Map<Long, MessageBodyState<GroupMessage>>
        ): List<GroupMessage> = metadata.mapNotNull { m ->
            (bodies[m.id] as? MessageBodyState.Ready)?.value
        }

        /** 原 `loadVisibleMessageBodies` 待加载过滤：在可见集合内且（无正文 或 上次失败）。 */
        internal fun pendingBodyIds(
            messageIds: Set<Long>,
            metadata: List<Message>,
            bodies: Map<Long, MessageBodyState<GroupMessage>>
        ): List<Message> = metadata.filter { m ->
            if (m.id !in messageIds) return@filter false
            when (bodies[m.id]) {
                null, is MessageBodyState.Error -> true
                else -> false
            }
        }

        /** 正文加载成功的状态迁移：命中 → Ready，未命中 → Error(缺正文)。 */
        internal fun applyLoadedResults(
            existingBodies: Map<Long, MessageBodyState<GroupMessage>>,
            pending: List<Message>,
            loaded: Map<Long, GroupMessage>,
            missingMessage: String = "正文不存在"
        ): Map<Long, MessageBodyState<GroupMessage>> = existingBodies + pending.associate { m ->
            val message = loaded[m.id]
            m.id to if (message != null) MessageBodyState.Ready(message) else MessageBodyState.Error(missingMessage)
        }

        /** 正文加载失败的状态迁移：全部置 Error（保留原始原因，无原因用兜底文案）。 */
        internal fun applyLoadFailure(
            existingBodies: Map<Long, MessageBodyState<GroupMessage>>,
            pending: List<Message>,
            reason: String?,
            fallback: String = "正文加载失败"
        ): Map<Long, MessageBodyState<GroupMessage>> = existingBodies + pending.associate {
            it.id to MessageBodyState.Error(reason ?: fallback)
        }
    }
}
