package com.yunian.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.UserProfileProvider
import com.yunian.ai.feature.chat.data.ChatContextResolver
import com.yunian.ai.feature.chat.data.ChatDraftStore
import com.yunian.ai.feature.chat.voice.ChatTtsState
import com.yunian.ai.network.stt.AndroidSttProvider
import com.yunian.ai.network.stt.SttService
import com.yunian.ai.network.tts.ChatTtsConfig
import com.yunian.ai.network.tts.ChatTtsMode
import com.yunian.ai.uicommon.model.ApiProviderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ChatViewModel(
    application: Application,
    private val companionId: Long
) : AndroidViewModel(application) {

    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val messageWriter = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
    private val companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
    private val apiConfigRepository = ServiceRegistry.getOrThrow(ApiConfigRepository::class.java)
    private val contextResolver = ChatContextResolver(chatRepository)
    private val draftStore = ChatDraftStore(application)
    private val generation = ChatGenerationManager.acquire(application, companionId)

    /** 当前轮次工具调用活动（OpenMinis 风格过程气泡） */
    val toolActivity: StateFlow<List<ToolActivity>> = generation.toolActivity

    /** 生图进行中：聊天页据此渲染液态玻璃等待动画（进程级状态，不依赖 manager 实例存活） */
    val imageGenGenerating: StateFlow<Boolean> = ImageGenGenerationStatus.activeCompanionIds
        .map { companionId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")

    /** 统一 AI 对话中间层（core:agent 实现）：语音通话回复走 Agent 回合。 */
    private val dialogueCoordinator: com.yunian.ai.domain.DialogueCoordinator
        get() = ServiceRegistry.getOrThrow(com.yunian.ai.domain.DialogueCoordinator::class.java)

    private val sttService by lazy { SttService.getInstance(application) }

    private val cachedRecent = chatRepository.getCachedRecent(companionId).orEmpty()
    private val _recentMessages = MutableStateFlow(cachedRecent)
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _messages = MutableStateFlow(cachedRecent)
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _messageMetadata = MutableStateFlow(
        cachedRecent.takeLast(ChatConstants.CHAT_PAGE_SIZE).map { it.toMetadataMessage() }
    )
    val messageMetadata: StateFlow<List<Message>> = _messageMetadata.asStateFlow()

    private val _messageBodies = MutableStateFlow<Map<Long, MessageBodyState<ChatMessage>>>(
        cachedRecent.associate { it.id to MessageBodyState.Ready(it) }
    )
    val messageBodies: StateFlow<Map<Long, MessageBodyState<ChatMessage>>> = _messageBodies.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()
    private var reachedHistoryStart = false

    /**
     * 首屏历史是否已装载完成（**会话本身就是空的**也算完成）。
     *
     * 消息入场动画用它锁定「水位线」，以便区分「进入会话时本来就在的消息」与「此后新到的消息」。
     * 不能改用「最新一条消息正文 Ready」来判断：空会话下那个条件恒为 false，而最新一条正文
     * 加载失败（[MessageBodyState.Error]）时也会恒为 false，两种情况都会导致整个会话不做入场动画。
     */
    private val _initialHistoryLoaded = MutableStateFlow(false)
    val initialHistoryLoaded: StateFlow<Boolean> = _initialHistoryLoaded.asStateFlow()

    private val _companionData = MutableStateFlow<CompanionEntity?>(null)
    val companionData: StateFlow<CompanionEntity?> = _companionData.asStateFlow()

    private val _userName = MutableStateFlow("我")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    private val _availableApis = MutableStateFlow<List<ApiProviderInfo>>(emptyList())
    val availableApis: StateFlow<List<ApiProviderInfo>> = _availableApis.asStateFlow()

    private val _currentApi = MutableStateFlow<ApiProviderInfo?>(null)
    val currentApi: StateFlow<ApiProviderInfo?> = _currentApi.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    private val _draftText = MutableStateFlow(draftStore.getDraft(companionId))
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    val isLoading: StateFlow<Boolean> = generation.isLoading
    val isTyping: StateFlow<Boolean> = generation.isTyping
    val typingText: StateFlow<String> = generation.typingText
    val isRegenerating: StateFlow<Boolean> = generation.isRegenerating
    val chatTtsConfig: StateFlow<ChatTtsConfig> = generation.chatTtsConfig
    val ttsState: StateFlow<ChatTtsState> = generation.ttsState

    val confirmationRequest: StateFlow<ToolConfirmationRequest?> = generation.confirmationRequest

    fun respondToConfirmation(id: Long, confirmed: Boolean) = generation.respondToConfirmation(id, confirmed)

    private var avatarUnsubscribe: (() -> Unit)? = null
    private var nicknameUnsubscribe: (() -> Unit)? = null

    init {
        observeMessageMetadata()
        observeCachedMessages()
        observeCompanion()
        observeApiConfigs()
        observeGenerationEvents()
        observeUserProfile()
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendText -> {
                generation.sendText(intent.content)
                clearDraft()
            }
            is ChatIntent.SendImage -> generation.sendImage(intent.imagePath)
            is ChatIntent.SendVideo -> sendVideo(intent.videoPath)
            is ChatIntent.SendVoice -> sendVoice(intent.audioPath, intent.duration)
            is ChatIntent.SendSticker -> sendSticker(intent.sticker)
            ChatIntent.ShareLocation -> _events.tryEmit(ChatUiEvent.Error("位置分享暂不可用"))
            is ChatIntent.SwitchApi -> switchApi(intent.provider)
            ChatIntent.LoadEarlier -> loadEarlierMessages()
            is ChatIntent.Recall -> recallMessage(intent.message)
            is ChatIntent.Regenerate -> {
                // regenerate 会在 ChatGenerationManager 内直接 deleteMessage 删除旧回复，
                // 但聊天页列表渲染读取的是 messageMetadata + messageBodies，且合并逻辑对正 id
                // 消息“只增不减”，故在此乐观清理被再生成的那条消息（与 recallMessage 同款处理），
                // 确保旧回复立即消失。新回复 id 不同，不受影响。
                val targetId = intent.message.id
                _messages.value = _messages.value.filterNot { it.id == targetId }
                _recentMessages.value = _recentMessages.value.filterNot { it.id == targetId }
                _olderMessages.value = _olderMessages.value.filterNot { it.id == targetId }
                _messageMetadata.value = _messageMetadata.value.filterNot { it.id == targetId }
                _messageBodies.value = _messageBodies.value - targetId
                // 同轮（turnId）的工具调用卡片也一并乐观移除，与 generation.regenerate 内的库删除保持一致
                val targetTurnId = intent.message.turnId
                if (targetTurnId != null) {
                    val toolActivityIds = _messageMetadata.value
                        .filter { it.type == MessageType.TOOL_ACTIVITY && it.turnId == targetTurnId }
                        .mapTo(HashSet()) { it.id }
                    if (toolActivityIds.isNotEmpty()) {
                        _messages.value = _messages.value.filterNot { it.id in toolActivityIds }
                        _recentMessages.value = _recentMessages.value.filterNot { it.id in toolActivityIds }
                        _olderMessages.value = _olderMessages.value.filterNot { it.id in toolActivityIds }
                        _messageMetadata.value = _messageMetadata.value.filterNot { it.id in toolActivityIds }
                        _messageBodies.value = _messageBodies.value - toolActivityIds
                    }
                }
                generation.regenerate(intent.message)
            }
            is ChatIntent.QuoteReply,
            is ChatIntent.CopyText,
            is ChatIntent.OpenMedia -> Unit
            is ChatIntent.SaveImage -> saveImage(intent.path)
            is ChatIntent.NavigateToMessage -> navigateToMessage(intent.messageId)
        }
    }

    /** 保存图片到系统相册并给出结果提示。 */
    private fun saveImage(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { saveImageToGallery(getApplication(), path) }.getOrDefault(false)
            _events.tryEmit(
                if (saved) {
                    ChatUiEvent.Info("已保存到相册")
                } else {
                    ChatUiEvent.Error("保存失败：图片文件不存在或已被清理")
                }
            )
        }
    }

    fun setDraftText(text: String) {
        if (_draftText.value == text) return
        _draftText.value = text
        draftStore.setDraft(companionId, text)
    }

    private fun clearDraft() = setDraftText("")

    fun refreshCompanionData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { companionRepository.getCompanionById(companionId) }
                .onSuccess { _companionData.value = it }
                .onFailure { SecureLog.e("ChatViewModel", "Refresh companion failed", it) }
        }
    }

    fun markAsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.markReadThroughLatest(companionId)
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.clearChatHistory(companionId) }
                .onSuccess {
                    // 列表渲染读的是 messageMetadata + messageBodies；Room recent 清空后
                    // 合并逻辑会把所有正 id 当作“更早消息”保留，必须显式复位这些状态，
                    // 否则清空聊天记录后列表不会立即清空（要离开页面才生效）。
                    _messageMetadata.value = emptyList()
                    _messageBodies.value = emptyMap()
                    _recentMessages.value = emptyList()
                    _olderMessages.value = emptyList()
                    reachedHistoryStart = true
                    _hasMoreMessages.value = false
                    publishMessages()
                }
                .onFailure { SecureLog.e("ChatViewModel", "Clear chat history failed", it) }
        }
    }

    fun setTtsMode(mode: ChatTtsMode) = generation.setTtsMode(mode)

    fun stopTts() = generation.stopTts()

    fun setCallActive(active: Boolean) = generation.setCallActive(active)

    suspend fun sendVoiceCallMessage(text: String): String? {
        val companion = _companionData.value ?: return null
        return runCatching {
            // 通话语音回复走统一 AI 对话中间层（core:agent）：
            // 安全过滤 / 落库 / 记忆提取全部内聚，UI 侧不再直连 AiService。
            val result = dialogueCoordinator.generateReply(
                com.yunian.ai.domain.DialogueRequest(
                    companionId = companionId,
                    text = text,
                    imagePath = null,
                )
            )
            val content = result.replyText.takeIf { it.isNotBlank() } ?: return@runCatching null
            val toastMsg = com.yunian.ai.domain.AiOperationalMessages.asToastMessage(content)
            if (toastMsg != null) {
                _events.tryEmit(ChatUiEvent.Error(toastMsg))
                return@runCatching null
            }
            content
        }.onFailure { SecureLog.e("ChatViewModel", "Voice call message failed", it) }.getOrNull()
    }

    private fun observeMessageMetadata() {

        if (cachedRecent.isNotEmpty()) {
            _hasMoreMessages.value = cachedRecent.size >= ChatConstants.CHAT_PAGE_SIZE
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {

                var metadataSeeded = cachedRecent.isNotEmpty()
                if (!metadataSeeded) {
                    runCatching {
                        chatRepository.hydrateRecent(companionId, ChatConstants.CHAT_PAGE_SIZE)
                    }
                    val hydrated = chatRepository.getCachedRecent(companionId).orEmpty()
                    if (hydrated.isNotEmpty()) {
                        _recentMessages.value = hydrated
                        _messages.value = hydrated
                        _messageBodies.value = hydrated.associate { it.id to MessageBodyState.Ready(it) }
                        _messageMetadata.value =
                            hydrated.takeLast(ChatConstants.CHAT_PAGE_SIZE).map { it.toMetadataMessage() }
                        metadataSeeded = true
                    }
                }

                if (!metadataSeeded) {
                    val recentMetadata = chatRepository
                        .getRecentMetadata(companionId, ChatConstants.CHAT_PAGE_SIZE)
                        .reversed()
                    _messageMetadata.value = recentMetadata
                    seedBodiesFromCache(recentMetadata)
                }
                // 到此为止首屏历史已装载完成：缓存命中 / hydrate 命中 / DB 查页三条路径都汇合在这里，
                // 且"会话本身就是空的"也照样走到这里（此时 metadata 为空列表）。一次性置位，
                // 后续的「会话被清空」复位分支不要重置它 —— 水位线只需要定一次。
                _initialHistoryLoaded.value = true
                _hasMoreMessages.value =
                    _messageMetadata.value.size < chatRepository.getMessageCount(companionId)
                chatRepository.observeRecentMetadata(companionId, ChatConstants.CHAT_PAGE_SIZE).collect { recent ->

                    // 会话被清空时必须复位内存态：本 VM 可能不是执行清空的那个实例
                    // （例如在「聊天详情页」点清空，而本页 VM 一直存活），此时只能靠
                    // 数据源变化来感知。若不复位，下面的 olderIds 会把内存里所有正 id
                    // 当作“更早消息”继续保留 → 返回聊天页仍能看到旧记录。
                    if (recent.isEmpty() && chatRepository.getMessageCount(companionId) == 0) {
                        _messageMetadata.value = emptyList()
                        _messageBodies.value = emptyMap()
                        _recentMessages.value = emptyList()
                        _olderMessages.value = emptyList()
                        _hasMoreMessages.value = false
                        publishMessages()
                        return@collect
                    }

                    val recentIds = recent.mapTo(HashSet(recent.size)) { it.id }
                    val streamingMeta = chatRepository.getCachedRecent(companionId)
                        .orEmpty()
                        .filter { it.id < 0L }
                        .map { it.toMetadataMessage() }
                    val olderIds = _messageMetadata.value.filter { current ->
                        current.id >= 0L && current.id !in recentIds
                    }
                    val merged = (olderIds + recent.reversed() + streamingMeta)
                        .distinctBy { it.id }
                        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })

                    if (merged != _messageMetadata.value) {
                        _messageMetadata.value = merged
                        seedBodiesFromCache(merged)
                    }
                    _hasMoreMessages.value = !reachedHistoryStart &&
                        merged.count { it.id >= 0L } < chatRepository.getMessageCount(companionId)
                    if (recent.isNotEmpty()) markAsRead()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                SecureLog.e("ChatViewModel", "Message observation failed", exception)
                _events.tryEmit(ChatUiEvent.Error("消息加载失败"))
                // 装载失败也要放行入场动画的水位线，否则它会永远定不下来 → 该会话一条消息都不弹。
                // 此时水位线取当前已知的最大 id，语义仍是「此前就在的消息不弹」。
                _initialHistoryLoaded.value = true
            }
        }
    }

    private fun observeCachedMessages() {
        viewModelScope.launch {
            chatRepository.observeCachedRecent(companionId).collect { cached ->
                val streaming = cached.filter { it.id < 0L }
                val streamingIds = streaming.mapTo(HashSet()) { it.id }
                val withoutStaleStreaming = _messageMetadata.value.filterNot {
                    it.id < 0L && it.id !in streamingIds
                }
                val streamingMeta = streaming.map { it.toMetadataMessage() }
                // 缓存里新增的正 id 消息（如微信 Bridge 镜像的图片消息）也要进 metadata，
                // 否则聊天页常开时这些消息永远不渲染。
                val cachedMeta = cached.asSequence()
                    .filter { it.id > 0L }
                    .map { it.toMetadataMessage() }
                    .toList()
                val mergedMeta = (withoutStaleStreaming + streamingMeta + cachedMeta)
                    .distinctBy { it.id }
                    .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                if (mergedMeta != _messageMetadata.value) {
                    _messageMetadata.value = mergedMeta
                }

                val bodyUpdates = cached.mapNotNull { msg ->
                    val existing = _messageBodies.value[msg.id]
                    if (existing is MessageBodyState.Ready && existing.value == msg) null
                    else msg.id to MessageBodyState.Ready(msg)
                }
                if (bodyUpdates.isNotEmpty()) {
                    _messageBodies.value = _messageBodies.value + bodyUpdates

                    val staleStreamingBodyIds = _messageBodies.value.keys.filter {
                        it < 0L && it !in streamingIds
                    }
                    if (staleStreamingBodyIds.isNotEmpty()) {
                        _messageBodies.value = _messageBodies.value - staleStreamingBodyIds.toSet()
                    }
                    publishLoadedMessages()
                } else if (mergedMeta != withoutStaleStreaming) {

                    val staleStreamingBodyIds = _messageBodies.value.keys.filter {
                        it < 0L && it !in streamingIds
                    }
                    if (staleStreamingBodyIds.isNotEmpty()) {
                        _messageBodies.value = _messageBodies.value - staleStreamingBodyIds.toSet()
                    }
                    publishLoadedMessages()
                }
            }
        }
    }

    private fun seedBodiesFromCache(metadata: List<Message>) {
        if (metadata.isEmpty()) return
        val cachedById = chatRepository.getCachedRecent(companionId)
            ?.associateBy { it.id }
            .orEmpty()
        if (cachedById.isEmpty()) return
        val ready = metadata.mapNotNull { item ->
            val body = cachedById[item.id] ?: return@mapNotNull null
            when (_messageBodies.value[item.id]) {
                is MessageBodyState.Ready -> null
                else -> item.id to MessageBodyState.Ready(body)
            }
        }
        if (ready.isNotEmpty()) {
            _messageBodies.value = _messageBodies.value + ready
            publishLoadedMessages()
        }
    }

    fun loadVisibleMessageBodies(messageIds: Set<Long>) {
        val metadata = _messageMetadata.value.filter { it.id in messageIds }
        val pending = metadata.filter { item ->
            when (_messageBodies.value[item.id]) {
                null, is MessageBodyState.Error -> true
                else -> false
            }
        }
        if (pending.isEmpty()) return

        _messageBodies.value = _messageBodies.value + pending.associate {
            it.id to MessageBodyState.Loading
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.loadMessages(pending) }
                .onSuccess { loaded ->
                    _messageBodies.value = _messageBodies.value + pending.associate { metadataItem ->
                        val message = loaded[metadataItem.id]
                        metadataItem.id to if (message != null) {
                            MessageBodyState.Ready(message)
                        } else {
                            MessageBodyState.Error("正文不存在")
                        }
                    }
                    publishLoadedMessages()
                }
                .onFailure { error ->
                    _messageBodies.value = _messageBodies.value + pending.associate {
                        it.id to MessageBodyState.Error(error.message ?: "正文加载失败")
                    }
                }
        }
    }

    fun retryMessageBody(messageId: Long) {
        _messageBodies.value = _messageBodies.value - messageId
        loadVisibleMessageBodies(setOf(messageId))
    }

    private fun observeCompanion() {
        viewModelScope.launch(Dispatchers.IO) {
            companionRepository.getCompanionByIdFlow(companionId).collect { _companionData.value = it }
        }
    }

    private fun observeApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            apiConfigRepository.getAllConfiguredConfigs().collect { configs ->
                _availableApis.value = configs.map { it.toProviderInfo() }
                _currentApi.value = apiConfigRepository.getActiveEnabledConfig()?.toProviderInfo()
            }
        }
    }

    private fun observeGenerationEvents() {
        viewModelScope.launch { generation.events.collect { _events.emit(it) } }
    }

    private fun observeUserProfile() {
        val provider = ServiceRegistry.get(UserProfileProvider::class.java)
        _userName.value = provider?.getNickname() ?: "我"
        _userAvatar.value = provider?.getAvatar()

        avatarUnsubscribe = provider?.observeAvatar { _userAvatar.value = it }
        nicknameUnsubscribe = provider?.observeNickname { _userName.value = it }
    }

    private fun loadEarlierMessages() {
        if (_isLoadingMore.value || !_hasMoreMessages.value) return
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cursor = _messageMetadata.value.firstOrNull()
                val page = chatRepository.getMetadataBefore(
                    companionId,
                    cursor?.timestamp ?: Long.MAX_VALUE,
                    cursor?.id ?: Long.MAX_VALUE,
                    ChatConstants.CHAT_LOAD_MORE_SIZE
                )
                if (page.isNotEmpty()) {
                    _messageMetadata.value = (page.reversed() + _messageMetadata.value).distinctBy { it.id }
                }
                reachedHistoryStart = page.size < ChatConstants.CHAT_LOAD_MORE_SIZE
                _hasMoreMessages.value = !reachedHistoryStart
                publishMessages()
            } catch (exception: Exception) {
                SecureLog.e("ChatViewModel", "Loading earlier messages failed", exception)
                _events.tryEmit(ChatUiEvent.Error("历史消息加载失败"))
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private fun navigateToMessage(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = chatRepository.getMessageById(messageId)
            if (target == null || target.companionId != companionId) {
                _events.emit(ChatUiEvent.Error("原消息已不存在"))
                return@launch
            }
            if (_messages.value.none { it.id == messageId }) {
                val halfPage = ChatConstants.CHAT_LOAD_MORE_SIZE / 2
                val before = chatRepository.getMessagesBeforeSync(
                    companionId,
                    target.timestamp,
                    target.id,
                    halfPage
                ).reversed()
                val after = chatRepository.getMessagesAfterSync(
                    companionId,
                    target.timestamp,
                    target.id,
                    halfPage
                )
                val recentIds = _recentMessages.value.mapTo(HashSet()) { it.id }
                _olderMessages.value = (before + target + after + _olderMessages.value)
                    .filterNot { it.id in recentIds }
                    .distinctBy { it.id }
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                reachedHistoryStart = before.size < halfPage
                _hasMoreMessages.value = !reachedHistoryStart
                publishMessages()
            }
            _events.emit(ChatUiEvent.MessageReadyToNavigate(messageId))
        }
    }

    private fun publishMessages() {
        _messages.value = contextResolver.capUiMessages(
            (_olderMessages.value + _recentMessages.value).distinctBy { it.id }
        ).first
    }

    private fun publishLoadedMessages() {
        val loaded = _messageMetadata.value.mapNotNull { metadata ->
            (_messageBodies.value[metadata.id] as? MessageBodyState.Ready)?.value
        }
        _recentMessages.value = loaded.takeLast(ChatConstants.CHAT_PAGE_SIZE)
        _olderMessages.value = loaded.dropLast(_recentMessages.value.size)
        publishMessages()
    }

    private fun switchApi(apiInfo: ApiProviderInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val config = apiInfo.configId?.let { apiConfigRepository.getConfigById(it) }
                    ?: apiConfigRepository.getConfigByProvider(ApiProvider.valueOf(apiInfo.name))
                    ?: return@runCatching
                apiConfigRepository.disableOtherConfigs(config.id)
                apiConfigRepository.saveConfig(config.copy(isEnabled = true))
            }.onFailure {
                SecureLog.e("ChatViewModel", "Switch API failed", it)
                _events.tryEmit(ChatUiEvent.Error("API 切换失败"))
            }
        }
    }

    private fun recallMessage(message: ChatMessage) {

        _messages.value = _messages.value.filterNot { it.id == message.id }
        _recentMessages.value = _recentMessages.value.filterNot { it.id == message.id }
        _olderMessages.value = _olderMessages.value.filterNot { it.id == message.id }
        // 聊天页列表渲染读取的是 messageMetadata + messageBodies（见 ChatScreen.toChatListItems），
        // 而 observeCachedMessages/observeMessageMetadata 的合并对正 id 消息“只增不减”，
        // 若不在这里同步清理，撤回后消息会残留在 metadata/bodies 中直到重进对话才消失。
        _messageMetadata.value = _messageMetadata.value.filterNot { it.id == message.id }
        _messageBodies.value = _messageBodies.value - message.id
        _events.tryEmit(ChatUiEvent.Info("消息已撤回"))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.deleteMessage(message) }
                .onFailure {
                    SecureLog.e("ChatViewModel", "Recall message failed", it)
                    _events.tryEmit(ChatUiEvent.Error("撤回失败"))
                }
        }
    }

    private fun sendSticker(sticker: StickerInfo) {
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf(String::isNotBlank)
            ?: sticker.name
        generation.sendText("[$stickerId]")
    }

    private fun sendVoice(audioPath: String, duration: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = companionId,
                        content = "[语音] $duration\"",
                        isFromUser = true,
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.VOICE,
                        linkString = audioPath
                    )
                )
                withTimeoutOrNull(AndroidSttProvider.RECOGNITION_TIMEOUT_MS) { sttService.recognize(audioPath) }
                    ?.takeIf(String::isNotBlank)
                    ?.let(generation::sendText)
            }.onFailure {
                SecureLog.e("ChatViewModel", "Voice message failed", it)
                _events.tryEmit(ChatUiEvent.Error("语音发送失败"))
            }
        }
    }

    private fun sendVideo(videoPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = companionId,
                        content = "[视频]",
                        isFromUser = true,
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.VIDEO,
                        linkString = videoPath
                    )
                )
            }.onFailure {
                SecureLog.e("ChatViewModel", "Video message failed", it)
                _events.tryEmit(ChatUiEvent.Error("视频发送失败"))
            }
        }
    }

    private fun ApiConfig.toProviderInfo(): ApiProviderInfo {
        val base = ApiProviderInfo.fromName(provider.name)
        return base.copy(displayName = name.ifBlank { base.displayName }, configId = id)
    }

    private fun CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id,
        name = name,
        personality = personality,
        age = age,
        backstory = backstory,
        speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser,
        content = content,
        timestamp = timestamp,
        type = if (type == MessageType.IMAGE) AiMessageType.IMAGE else AiMessageType.TEXT,
        companionId = companionId
    )

    private fun ChatMessage.toMetadataMessage(): Message = Message(
        id = id,
        conversationId = companionId,
        conversationType = "chat",
        isFromUser = isFromUser,
        timestamp = timestamp,
        type = type,
        fileFormat = fileFormat,
        turnId = turnId,
        eventIndex = eventIndex,
        durationMs = durationMs,
        anchorMessageId = anchorMessageId,
    )

    override fun onCleared() {
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
        nicknameUnsubscribe?.invoke()
        nicknameUnsubscribe = null

        draftStore.setDraft(companionId, _draftText.value)

        generation.stopTts()
        contextResolver.clearCache(companionId)

        ChatGenerationManager.release(companionId)
        super.onCleared()
    }
}

class ChatViewModelFactory(
    private val application: Application,
    private val companionId: Long
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(application, companionId) as T
    }
}
