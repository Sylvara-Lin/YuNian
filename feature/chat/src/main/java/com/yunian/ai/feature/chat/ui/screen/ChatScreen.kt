package com.yunian.ai.feature.chat.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons



import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.common.PerformanceTrace
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.chat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.feature.chat.ui.message.ChatRowRenderer
import com.yunian.ai.feature.chat.ui.message.ImageGenGeneratingItem
import com.yunian.ai.feature.chat.ui.message.RegeneratingItem
import com.yunian.ai.feature.chat.ui.message.TypingIndicatorItem
import com.yunian.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatRow
import com.yunian.ai.feature.chat.ui.viewmodel.buildChatRows
import com.yunian.ai.feature.chat.ui.viewmodel.ChatUiEvent
import com.yunian.ai.feature.chat.ui.viewmodel.QuoteReply
import com.yunian.ai.feature.chat.ui.viewmodel.encodeQuotedMessage
import com.yunian.ai.feature.chat.ui.viewmodel.toQuoteReply
import com.yunian.ai.feature.chat.data.ChatDetailSettingsStore
import com.yunian.ai.common.StickerManager
import com.yunian.ai.common.image.ImageFormatSniffer
import com.yunian.ai.common.StickerInfo
import androidx.compose.runtime.key
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import com.yunian.ai.uicommon.component.sticker.StickerImportOverlay
import com.yunian.ai.uicommon.component.sticker.suggestStickerName
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.yunian.ai.uicommon.component.glass.ProvidePageBackdrop
import com.yunian.ai.uicommon.component.VoiceRecorder
import com.yunian.ai.uicommon.component.getChatBackgroundByKey
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import com.yunian.ai.uicommon.component.isCustomBackground
import com.yunian.ai.uicommon.component.rememberBackgroundBitmap
import com.yunian.ai.uicommon.component.resolveEffectiveChatBackgroundKey
import com.yunian.ai.uicommon.component.jellyEntrance
import com.yunian.ai.network.tts.ChatTtsMode
import com.yunian.ai.uicommon.image.viewer.FullscreenImageViewer
import com.yunian.ai.uicommon.picker.ui.CustomImagePicker
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import com.yunian.ai.feature.chat.ui.message.LocalCompanionAvatarClick
import com.yunian.ai.feature.chat.ui.message.LocalUserAvatarClick
import com.yunian.ai.uicommon.theme.LocalChatGlassBackdrop
import com.yunian.ai.uicommon.theme.LocalChatGlassEnabled
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.rememberAdaptiveSizing
import com.yunian.ai.uicommon.utils.PageTransitions
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.common.MessageBodyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {},
    onNavigateToVoiceCall: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId)
    )
    val confirmationRequest by viewModel.confirmationRequest.collectAsStateWithLifecycle()
    val toolActivities by viewModel.toolActivity.collectAsStateWithLifecycle()
    val currentOnIntent by rememberUpdatedState(viewModel::handleIntent)
    val onIntent: (ChatIntent) -> Unit = remember { { intent -> currentOnIntent(intent) } }
    // 头像点击回调（T03 上提）：以稳定 lambda 一次性 provide（static local 值变化会令整棵子树重组，
    // 故这里用 remember + rememberUpdatedState 保证引用稳定），供整条 LazyColumn 复用。
    val currentOnNavigateToDetail by rememberUpdatedState(onNavigateToDetail)
    val currentOnNavigateToUserProfile by rememberUpdatedState(onNavigateToUserProfile)
    val companionAvatarClick: () -> Unit = remember(companionId) {
        { currentOnNavigateToDetail(companionId) }
    }
    val userAvatarClick: () -> Unit = remember {
        { currentOnNavigateToUserProfile() }
    }
    var quoteReply by remember { mutableStateOf<QuoteReply?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var showExtensionPanel by remember { mutableStateOf(false) }
    var showStickerPanel by remember { mutableStateOf(false) }

    // 表情包拖拽导入：全屏 drop 区域接收 image/* 与 application/zip（minSdk 26，foundation dragAndDropTarget）
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var stickerDragHovering by remember { mutableStateOf(false) }
    val stickerDropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { stickerDragHovering = true }
            override fun onExited(event: DragAndDropEvent) { stickerDragHovering = false }
            override fun onEnded(event: DragAndDropEvent) { stickerDragHovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                stickerDragHovering = false
                val dragEvent = event.toAndroidDragEvent()
                val clip = dragEvent.clipData ?: return false
                if (clip.itemCount <= 0) return false
                val uri = clip.getItemAt(0).uri ?: return false
                val mime = clip.description?.getMimeType(0)
                return when {
                    mime != null && mime.startsWith("image/") -> {
                        // 图片：进入「导入 → 命名 → 确认」过场（与其他导入路径收口同一流程）
                        pendingImportUri = uri
                        true
                    }
                    mime == "application/zip" -> {
                        // ZIP：沿用既有 ZIP 通道（合并导入，无需命名）
                        scope.launch { importStickerZipFromUri(context, uri, snackbarHostState) }
                        true
                    }
                    mime == null || mime == "application/octet-stream" -> {
                        // Bug2 对齐：部分 provider（微信 / QQ / 系统分享）拖入时不带 MIME 或仅返回
                        // 通用二进制（application/octet-stream）。若直接 reject 会让合法 jpg/png 被
                        // 静默丢弃。改为按文件头内容嗅探判定图片 / zip，以内容为准。
                        scope.launch {
                            when (sniffDroppedKind(context, uri)) {
                                DroppedKind.IMAGE -> pendingImportUri = uri
                                DroppedKind.ZIP -> importStickerZipFromUri(context, uri, snackbarHostState)
                                DroppedKind.UNKNOWN -> snackbarHostState.showSnackbar("无法识别的文件格式")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    var showImagePicker by remember { mutableStateOf(false) }

    val stickerPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val path = copyUriToCache(context, it)
                    if (path != null) {
                        val count = StickerManager.getInstance(context).importStickerZip(path)
                        snackbarHostState.showSnackbar("成功导入 $count 个表情包")
                    } else {
                        snackbarHostState.showSnackbar("文件读取失败")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败: ${e.message}")
                }
            }
        }
    }

    var cameraPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            showExtensionPanel = false
            cameraPhotoUri?.let { photoUri ->
                scope.launch {
                    try {
                        val inputStream = context.contentResolver.openInputStream(photoUri)
                        val cacheFile = java.io.File(context.cacheDir, "sent_photo_${System.currentTimeMillis()}.jpg")
                        inputStream?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        onIntent(ChatIntent.SendImage(cacheFile.absolutePath))
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("拍照失败: ${e.message}")
                    }
                }
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            val photoFile = java.io.File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            photoFile.parentFile?.mkdirs()
            photoFile.createNewFile()
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.yunian.fileprovider", photoFile
            )
            cameraPhotoUri = photoUri
            cameraLauncher.launch(photoUri)
        } else {
            scope.launch { snackbarHostState.showSnackbar("相机权限被拒绝") }
        }
    }

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            pendingAudioAction?.invoke()
        } else {
            scope.launch { snackbarHostState.showSnackbar("需要麦克风权限才能使用语音功能") }
        }
        pendingAudioAction = null
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            showExtensionPanel = false
            scope.launch {
                try {
                    val videoPath = copyUriToCache(context, it)
                    if (videoPath != null) {
                        onIntent(ChatIntent.SendVideo(videoPath))
                    } else {
                        snackbarHostState.showSnackbar("视频读取失败")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("视频处理失败: ${e.message}")
                }
            }
        }
    }

    val userAvatar by viewModel.userAvatar.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageMetadata by viewModel.messageMetadata.collectAsStateWithLifecycle()
    val messageBodies by viewModel.messageBodies.collectAsStateWithLifecycle()
    val appSettingsStore = remember(context) { com.yunian.ai.common.AppSettingsStore(context) }
    val showReasoning by appSettingsStore.showReasoningFlow.collectAsStateWithLifecycle(initialValue = false)
    val autoCollapseReasoning by appSettingsStore.autoCollapseReasoningFlow.collectAsStateWithLifecycle(initialValue = true)
    val showTypingSpinner by appSettingsStore.showTypingSpinnerFlow.collectAsStateWithLifecycle(initialValue = false)
    // 列表结构只依赖 messageMetadata + showReasoning（正文由行内读取 messageBodies 解耦），
    // 因此流式 delta（正文变化）不再触发整表 O(n) 重建。
    val chatRows = remember(messageMetadata, showReasoning) {
        buildChatRows(messageMetadata, showReasoning = showReasoning)
    }

    // 当前轮进行中的工具调用卡片：注入消息流最新位置，让过程实时可见；
    // 轮次结束后由持久化的 TOOL_ACTIVITY 消息接管（见 ChatGenerationManager）。
    val liveToolRow = remember(toolActivities) {
        if (toolActivities.isEmpty()) {
            null
        } else {
            ChatRow.LiveToolGroup(activities = toolActivities)
        }
    }
    val visibleRows = remember(chatRows, liveToolRow) {
        (if (liveToolRow != null) chatRows + liveToolRow else chatRows).asReversed()
    }
    val visibleMessagesReady = messageMetadata.lastOrNull()?.let { latest ->
        messageBodies[latest.id] is MessageBodyState.Ready
    } == true
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsStateWithLifecycle()
    val companionData by viewModel.companionData.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val typingText by viewModel.typingText.collectAsStateWithLifecycle()
    val imageGenGenerating by viewModel.imageGenGenerating.collectAsStateWithLifecycle()
    val isRegenerating by viewModel.isRegenerating.collectAsStateWithLifecycle()
    // 首屏历史是否已装载完成（空会话也算完成）—— 果冻入场动画的水位线由它来锁定。
    val initialHistoryLoaded by viewModel.initialHistoryLoaded.collectAsStateWithLifecycle()
    val availableApis by viewModel.availableApis.collectAsStateWithLifecycle()
    val currentApi by viewModel.currentApi.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val ttsConfig by viewModel.chatTtsConfig.collectAsStateWithLifecycle()

    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val perfTier = remember { HardwareInfo.tier }
    val adaptiveSizing = rememberAdaptiveSizing()

    // ---- 消息入场「果冻」动画的闸门 --------------------------------------------------
    // 进入会话时已存在的消息一律不弹（否则整屏消息一起 duang），此后新到的消息只弹一次。
    val jellyAnimEnabled = perfTier != HardwareInfo.Tier.LOW
    // 水位线：进入会话那一刻「本来就该在」的最新消息 id，只有比它还新的才算新消息。
    var jellyWatermarkId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(initialHistoryLoaded, messageMetadata) {
        // 首屏历史装载完成后才定水位线：此刻"本来就该在"的消息都已在 messageMetadata 里。
        // 不能改用「最新一条正文 Ready」—— 空会话下它恒 false、最新一条正文加载失败时也恒 false，
        // 两种情况都会让水位线永远定不下来，整个会话都不做入场动画。
        // 空会话时 messageMetadata 为空 → 水位线取 0，随后的第一条消息照样能弹。
        if (initialHistoryLoaded && jellyWatermarkId == null) {
            jellyWatermarkId = messageMetadata.maxOfOrNull { it.id } ?: 0L
        }
    }
    // 幂等锁：已播放过的消息 stableId（普通集合，**只在行级 effect 中写入**，组合期只读）。
    // 翻来覆去滚动 / 回收重组都不会重播。
    val jellyPlayedIds = remember { HashSet<String>() }
    // 行上报「已播放」后自增，驱动下面 remember 立即把已播 id 移出待播集合（关闭重播窗口）。
    var jellyPlayTick by remember { mutableIntStateOf(0) }
    // 待播 id 集合：组合期**同步**计算（在 LazyColumn 组合之前），保证新增行首帧即可读到；
    // 只在结构 / 水位线变化或行上报时重算，**不随流式 delta 抖动** ——
    // 替代旧实现「逐行组合期 seen.add」的副作用（R10）。
    val jellyPlayIds: Set<String> = remember(
        visibleRows, jellyWatermarkId, jellyAnimEnabled, jellyPlayTick
    ) {
        if (!jellyAnimEnabled) {
            emptySet()
        } else {
            visibleRows.asSequence()
                .filter { jellyQualifiesForEntrance(it, jellyWatermarkId) }
                .map { it.stableId }
                .filter { it !in jellyPlayedIds }
                .toSet()
        }
    }
    // 行上报「已播放」的稳定回调：捕获项（jellyPlayedIds / jellyPlayTick）恒定 → 传入 LazyColumn item
    // 的引用稳定，避免每次组合新建 lambda 破坏 ChatRowRenderer 的 skipping。
    val onJellyPlayed: (String) -> Unit = remember {
        { id -> if (jellyPlayedIds.add(id)) jellyPlayTick++ }
    }

    val appContext = remember(context) { context.applicationContext }
    val settingsStore = remember(appContext) { ChatDetailSettingsStore(appContext) }
    val detailSettingsFlow = remember(settingsStore, companionId) { settingsStore.settingsFlow(companionId) }
    val detailSettings by detailSettingsFlow.collectAsStateWithLifecycle(
        // T03/B5：首帧优先用进程级内存快照（由 MainScreen 进入前预热填充），使首帧即解析出正确的
        // 背景 key，消除 R4 的「首帧全局背景 → DataStore 到达后翻转成角色独立背景」的窗口内翻转。
        // 快照不存在时回退默认值；DataStore 首次发射后 collectAsStateWithLifecycle 会以远端值为准。
        initialValue = ChatDetailSettingsStore.getCachedSettings(companionId)
            ?: com.yunian.ai.feature.chat.data.CompanionChatDetailSettings()
    )

    // T01/T04：首帧组合打点 + 把重活（markAsRead / refreshCompanionData，二者内部均在 IO）延后到
    // 转场结束之后再发起，从而把 DB 写/查挪出进入窗口。delay 取与转场一致的进入时长（LOW 档为 0，立即执行）。
    // 仅改变「何时发起」，不改任何观感（未读清零晚 ~350ms，页面本身不显示该状态）。
    //
    // D2 加固：用 try/finally 保证关键调用一定执行 —— 本效果在导航离开聊天页时会随组合取消，
    // 原实现在 delay 之后调用二者，取消时可能被跳过；改为 finally 后无论正常结束还是被取消都会调用。
    // （markAsRead / refreshCompanionData 均非 suspend，可直接在 finally 中调用。）
    LaunchedEffect(Unit) {
        PerformanceTrace.markChatFirstCompose()
        try {
            val transitionMs = PageTransitions.enterDurationMillis().toLong()
            if (transitionMs > 0L) delay(transitionMs)
            PerformanceTrace.markChatTransitionEnd()
        } finally {
            viewModel.markAsRead()
            viewModel.refreshCompanionData()
        }
    }

    var pendingNavigationMessageId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatUiEvent.Error -> com.yunian.ai.uicommon.component.YuNianToast.error(event.message)
                is ChatUiEvent.ContentBlocked -> com.yunian.ai.uicommon.component.YuNianToast.warning("内容已拦截: ${event.reason}")
                is ChatUiEvent.Info -> com.yunian.ai.uicommon.component.YuNianToast.info(event.message)
                is ChatUiEvent.StreamCompleted -> {  }
                is ChatUiEvent.MessageReadyToNavigate -> pendingNavigationMessageId = event.messageId
            }
        }
    }

    var initialBottomScrollSettled by remember { mutableStateOf(false) }

    val itemCount = chatRows.size +
        (if (isLoadingMore) 1 else 0) +
        (if (isTyping) 1 else 0) +
        (if (isRegenerating) 1 else 0)

    val listState = remember { LazyListState() }
    // 稳定化 item 回调（A4）：会把变化的 companionData / userName 经 rememberUpdatedState 旁路，
    // 使传入 LazyColumn item 的 lambda 捕获项稳定 → 恢复 Compose skipping，
    // 避免「任一页面状态变化 → 所有可见项重组（进而重跑气泡模糊）」。
    val currentCompanionForIntent by rememberUpdatedState(companionData)
    val currentUserNameForIntent by rememberUpdatedState(userName)
    val handleChatIntent: (ChatIntent) -> Unit = remember {
        { intent ->
            when (intent) {
                is ChatIntent.QuoteReply -> quoteReply = intent.message.toQuoteReply(
                    companionName = currentCompanionForIntent?.name,
                    userName = currentUserNameForIntent
                )
                is ChatIntent.CopyText -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("聊天消息", intent.text))
                    scope.launch { snackbarHostState.showSnackbar("已复制") }
                }
                is ChatIntent.OpenMedia -> {
                    if (intent.mimeType.startsWith("image/")) {
                        previewImagePath = intent.path
                    } else {
                        scope.launch {
                            val result = openChatMedia(context, intent.path, intent.mimeType)
                            if (!result) snackbarHostState.showSnackbar("无法打开该文件")
                        }
                    }
                }
                is ChatIntent.NavigateToMessage -> {
                    onIntent(intent)
                }
                else -> onIntent(intent)
            }
        }
    }

    LaunchedEffect(pendingNavigationMessageId, visibleRows) {
        val targetId = pendingNavigationMessageId ?: return@LaunchedEffect
        val messageIndex = visibleRows.indexOfFirst { row ->
            row is ChatRow.Message && row.metadata.id == targetId
        }
        if (messageIndex >= 0) {
            listState.animateScrollToItem(messageIndex)
            pendingNavigationMessageId = null
        }
    }

    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty() && !initialBottomScrollSettled) {
            initialBottomScrollSettled = true
        }
    }

    val isAtBottom = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
            firstVisible != null && firstVisible.index <= 1
        }
    }

    var wasAtBottom by remember { mutableStateOf(true) }
    var unreadNewMessages by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom.value }.collect { nearBottom ->
            wasAtBottom = nearBottom
            if (nearBottom) unreadNewMessages = 0
        }
    }

    // 说明：可见正文加载（原独立 snapshotFlow）与「触顶加载更早」的观察者已合并到下方
    // historyRestore* 区域（R5：减少滚动期对 layoutInfo 的重复扫描）。

    val lastMessageId = messages.lastOrNull()?.id
    val bottomRow = visibleRows.firstOrNull()
    val bottomItemStableId = bottomRow?.stableId
    val bottomItemContentReady = when (bottomRow) {
        null -> false
        is ChatRow.Message -> {
            val body = messageBodies[bottomRow.metadata.id]
            body is MessageBodyState.Ready || body is MessageBodyState.Error
        }
        else -> true
    }
    var autoScrollCountedId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lastMessageId, bottomItemStableId, bottomItemContentReady) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastMessage = messages.lastOrNull()
        val isMyMessage = lastMessage?.isFromUser == true
        val isNewMessage = autoScrollCountedId != lastMessageId
        if (isNewMessage) autoScrollCountedId = lastMessageId
        if (wasAtBottom || isMyMessage) {
            listState.scrollToBottomIfNeeded()
            if (isNewMessage) unreadNewMessages = 0
        } else if (isNewMessage) {
            unreadNewMessages += 1
        }
    }

    LaunchedEffect(typingText) {
        if (typingText.isNotBlank() && wasAtBottom) {
            listState.scrollToBottomIfNeeded()
        }
    }

    // 工具调用卡片实时刷新时，若用户停在底部则跟随滚动，保证过程可见
    LaunchedEffect(liveToolRow != null, toolActivities.size) {
        if (liveToolRow != null && wasAtBottom) {
            listState.scrollToBottomIfNeeded()
        }
    }

    // 结构派生下沉：O(n) 扫描只在 chatRows 结构变化时执行一次（不再随流式 delta 抖动，R3）；
    // 正文 id 的实时判定只读「最新一条 reasoning 行」的 body（O(1)）。
    val latestReasoningRow = remember(chatRows) {
        chatRows.lastOrNull { it is ChatRow.Message && it.metadata.type == MessageType.REASONING }
            as? ChatRow.Message
    }
    val streamingReasoningActive = latestReasoningRow?.let { row ->
        (messageBodies[row.metadata.id] as? MessageBodyState.Ready)?.value?.id?.let { it < 0L } == true
    } == true
    LaunchedEffect(streamingReasoningActive, chatRows.lastOrNull()?.stableId) {
        if (streamingReasoningActive && wasAtBottom) {
            listState.scrollToBottomIfNeeded()
        }
    }

    // 生图等待气泡 / 生图结果消息都是插到列表最底部（reverseLayout 的 index 0），
    // 而带 key 的 LazyColumn 会锚定旧可见项、不会自动跟随，必须显式滚到底。
    LaunchedEffect(imageGenGenerating) {
        if (wasAtBottom && itemCount > 0) {
            listState.scrollToBottomIfNeeded()
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            showExtensionPanel = false
            showStickerPanel = false
            if (itemCount > 0) listState.scrollToBottomIfNeeded()
        }
    }

    var historyRestoreKey by remember { mutableStateOf<String?>(null) }
    var historyRestoreScrollOffset by remember { mutableStateOf(0) }
    var isLoadingMoreTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(listState, visibleRows, initialBottomScrollSettled) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleMessageIds = layoutInfo.visibleItemsInfo.mapNotNull { item ->
                (item.key as? String)
                    ?.removePrefix("message-")
                    ?.takeIf { it != item.key }
                    ?.toLongOrNull()
            }.toSet()
            val maxVisibleIndex = layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            val nearTop = maxVisibleIndex >= layoutInfo.totalItemsCount - 6
            VisibleListSnapshot(visibleMessageIds, nearTop)
        }.collect { snapshot ->
            // ① 可见正文加载（始终生效）
            viewModel.loadVisibleMessageBodies(snapshot.visibleMessageIds)
            // ② 触顶加载更早（首屏稳定后才参与，避免首屏定位阶段误触发）
            if (!initialBottomScrollSettled) return@collect
            if (!snapshot.nearTop) isLoadingMoreTriggered = false
            if (snapshot.nearTop && hasMoreMessages && !isLoadingMore && !isLoadingMoreTriggered) {
                val anchorItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key != "load_more_indicator" }
                historyRestoreKey = (anchorItem?.key as? String)
                    ?: visibleRows.getOrNull(listState.firstVisibleItemIndex)?.stableId
                historyRestoreScrollOffset = anchorItem?.offset?.let { -it }
                    ?: listState.firstVisibleItemScrollOffset
                isLoadingMoreTriggered = true
                onIntent(ChatIntent.LoadEarlier)
            }
        }
    }

    LaunchedEffect(historyRestoreKey, visibleRows) {
        val restoreKey = historyRestoreKey ?: return@LaunchedEffect
        val anchorIndex = visibleRows.indexOfFirst { it.stableId == restoreKey }
        if (anchorIndex >= 0) {
            listState.scrollToItem(anchorIndex, historyRestoreScrollOffset)
            historyRestoreKey = null
            historyRestoreScrollOffset = 0
        }
    }

    val colors = AppTheme.colors
    // 背景解析（resolveEffectiveChatBackgroundKey / getChatBackgroundByKey / isCustomBackground）
    // 全部是纯同步计算，不读磁盘。必须「同步派生」而不能放进 LaunchedEffect + withContext(IO)
    // 异步回填：否则首帧状态仍是初始默认值（resolvedBgKey="default"、isCustomBg=false）→ 先渲染
    // 默认背景、至少一帧后（叠加 IO 调度）才切到真实背景 —— 这就是进入聊天页「闪一下」的根因。
    //
    // 同步派生后，首帧 detailSettings 即默认值（useGlobalBackground = true、backgroundKey = null）
    // → resolvedBgKey == globalBgKey，与首页 MainScreen 使用同一 key；同时 customBgPainter 无条件
    // 调用，会立刻命中启动时 ChatBackgroundCache.preload 预热的缓存 —— 首帧即出正确背景。
    var globalBgKey by remember { mutableStateOf(getChatBackgroundKey(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                globalBgKey = getChatBackgroundKey(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val resolvedBgKey = resolveEffectiveChatBackgroundKey(
        useGlobalBackground = detailSettings.useGlobalBackground,
        companionBackgroundKey = detailSettings.backgroundKey,
        globalBackgroundKey = globalBgKey
    )
    val isCustomBg = isCustomBackground(resolvedBgKey)
    val customBgKey = if (isCustomBg) resolvedBgKey else ""
    // 无条件调用：非自定义背景时 customBgKey 为空，内部返回 null（回退纯色/渐变）。
    // 不再用 if 条件包裹，避免条件组合组的进/出导致首帧不发起位图加载、无法命中预热缓存。
    val customBgPainter = rememberBackgroundBitmap(customBgKey)
    val (targetBgColor, chatBgGradient) = remember(resolvedBgKey, isDarkTheme) {
        if (isCustomBg) {
            Color.Transparent to null
        } else {
            getChatBackgroundByKey(context, resolvedBgKey, isDarkTheme)
        }
    }

    val chatBgColor by animateColorAsState(targetBgColor, tween(300), label = "bgColor")
    val backgroundColor = when {
        isCustomBg -> Color.Transparent
        resolvedBgKey == "default" -> colors.background
        else -> chatBgColor
    }

    var showVoiceRecorder by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var isCanceling by remember { mutableStateOf(false) }
    val voiceRecorder = remember { VoiceRecorder.getInstance(context) }

    val exitChat = {
        focusManager.clearFocus(force = true)
        showExtensionPanel = false
        showStickerPanel = false
        showVoiceRecorder = false
        onNavigateBack()
    }

    BackHandler(enabled = previewImagePath == null, onBack = exitChat)

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            voiceRecorder.start()
            while (isRecording) {
                delay(1000)
                if (isRecording) recordingDuration++
            }
        }
    }

    val stickers = remember { mutableStateListOf<StickerInfo>() }

    // 聊天页独立的背景捕获层：单聊可用自己的背景（预设渐变 / 自定义图片），
    // 液态玻璃必须折射「当前真正显示的背景」，不能沿用 MainScreen 的主背景 backdrop。
    //
    // A0/B4：key 只保留「稳定身份」(resolvedBgKey, isCustomBg, customBgPainter, chatBgGradient)，
    // **移除 backgroundColor** —— 它是 animateColorAsState(tween(300)) 的动画中间值，放进 key 会在
    // 过渡窗口内每帧改变 key → 每帧重建 backdrop 层 + 所有玻璃重绑重模糊（例外①）。
    // 移除后 backgroundColor 仍照常参与实际绘制（见下方背景 Box 的 else 分支），层内容由
    // drawContent() 反映：背景色变化会失效该背景 Box 的绘制并向上重录捕获层，故观感不变。
    val chatBackdrop = key(resolvedBgKey, isCustomBg, customBgPainter, chatBgGradient) {
        rememberLayerBackdrop {
            PerformanceTrace.markChatBackdropRecordStart()
            drawContent()
            PerformanceTrace.markChatBackdropRecordDone()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                PerformanceTrace.markChatShellDrawn()
                drawContent()
            }
            .testTag("chat_shell_ready")
    ) {

        // 纯背景层（只画背景，不含玻璃组件，避免 RenderNode 环）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(chatBackdrop)
        ) {
            if (isCustomBg && customBgPainter != null) {
                Image(
                    painter = customBgPainter,
                    contentDescription = stringResource(R.string.chat_background),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            when {
                                isCustomBg -> Modifier.background(Color.Transparent)

                                chatBgGradient != null && resolvedBgKey != "default" ->
                                    Modifier.background(chatBgGradient!!)
                                else -> Modifier.background(backgroundColor)
                            }
                        )
                )
            }
        }

        ProvidePageBackdrop(chatBackdrop) {
            // 气泡液态玻璃：backdrop + 低端机降级开关（LOW 档保持纯色气泡）；
            // 头像点击回调在此一次性 provide（T03：替代原先每条消息逐项包裹的 provider）。
            CompositionLocalProvider(
                LocalChatGlassBackdrop provides chatBackdrop,
                LocalChatGlassEnabled provides (perfTier != HardwareInfo.Tier.LOW),
                LocalCompanionAvatarClick provides companionAvatarClick,
                LocalUserAvatarClick provides userAvatarClick
            ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .zIndex(10f)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {

                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            if (visibleMessagesReady) PerformanceTrace.markChatMessagesDrawn()
                        }
                        .then(
                            if (visibleMessagesReady) Modifier.testTag("chat_messages_ready")
                            else Modifier
                        )
                        .nestedScroll(rememberHorizontalSwipeGuard())
                        .pointerInput(Unit) {
                            detectTapGestures {
                                keyboardController?.hide()
                                focusManager.clearFocus(force = true)
                                showExtensionPanel = false
                                showStickerPanel = false
                            }
                        },
                    contentPadding = PaddingValues(
                        start = adaptiveSizing.listHorizontalPadding, end = adaptiveSizing.listHorizontalPadding,
                        top = ChatTopBarOverlayDefaults.ContentTopPadding,
                        bottom = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isRegenerating) {
                        item(key = "regenerating_indicator") {
                            RegeneratingItem(
                                companionData = companionData,
                                adaptiveSizing = adaptiveSizing
                            )
                        }
                    }

                    if (isTyping && typingText.isNotBlank()) {
                        item(key = "typing_indicator") {
                            TypingIndicatorItem(
                                companionData = companionData,
                                typingText = typingText,
                                adaptiveSizing = adaptiveSizing,
                                isDarkTheme = isDarkTheme,
                                // 打字气泡也走果冻入场，否则只有正式消息在弹、观感不一致。
                                modifier = Modifier.jellyEntrance(
                                    play = jellyAnimEnabled,
                                    transformOrigin = TransformOrigin(0f, 1f)
                                )
                            )
                        }
                    }

                    if (imageGenGenerating) {
                        item(key = "image_gen_indicator") {
                            ImageGenGeneratingItem(
                                companionData = companionData,
                                adaptiveSizing = adaptiveSizing,
                                isDarkTheme = isDarkTheme,
                                modifier = Modifier.jellyEntrance(
                                    play = jellyAnimEnabled,
                                    transformOrigin = TransformOrigin(0f, 1f)
                                )
                            )
                        }
                    }

                    items(
                        items = visibleRows,
                        key = { it.stableId },
                        contentType = { it.contentType }
                    ) { row ->
                        // 只有"比进入会话时的最新消息还新"的正 id 消息才弹一次（详见 jellyQualifiesForEntrance）；
                        // 待播集合 jellyPlayIds 在组合期同步计算，行首帧 effect 播放后回报移出 → 回收重组不重播。
                        val jellyPlay = row.stableId in jellyPlayIds
                        ChatRowRenderer(
                            row = row,
                            messageBodies = messageBodies,
                            companionData = companionData,
                            userAvatar = userAvatar,
                            userName = userName,
                            onIntent = handleChatIntent,
                            adaptiveSizing = adaptiveSizing,
                            isDarkTheme = isDarkTheme,
                            onRetryBody = viewModel::retryMessageBody,
                            autoCollapseReasoning = autoCollapseReasoning,
                            jellyEntrance = jellyPlay,
                            onJellyPlayed = onJellyPlayed,
                        )
                    }

                    if (isLoadingMore) {
                        item(key = "load_more_indicator") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.metadataContent
                                    )
                                    Text(
                                        "加载更早的消息...",
                                        fontSize = 12.sp,
                                        color = colors.metadataContent
                                    )
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = unreadNewMessages > 0,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Surface(
                        modifier = Modifier.clickable {
                            scope.launch {
                                listState.animateScrollToItem(0)
                                unreadNewMessages = 0
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = colors.primary,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                AppIcons.ChevronDown,
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${unreadNewMessages} 条新消息",
                                color = colors.onPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            ChatInputRegion(
                isBlocked = detailSettings.blocked,
                isLoading = isLoading,
                quoteReply = quoteReply,
                inputText = draftText,
                onInputTextChange = viewModel::setDraftText,
                ttsState = ttsState,
                showStickerPanel = showStickerPanel,
                showExtensionPanel = showExtensionPanel,
                availableApis = availableApis,
                currentApi = currentApi,
                onStopTtsClick = { viewModel.stopTts() },
                onStickerClick = { sticker ->
                    onIntent(ChatIntent.SendSticker(sticker))
                    showStickerPanel = false
                },
                onImportStickersClick = { stickerPickerLauncher.launch("application/zip") },
                onDeleteAllStickersClick = {
                    scope.launch {
                        val manager = StickerManager.getInstance(context)
                        val success = manager.deleteAllImportedStickers()
                        if (success) {
                            snackbarHostState.showSnackbar("已删除全部表情包")
                        } else {
                            snackbarHostState.showSnackbar("删除失败")
                        }
                    }
                },
                onSwitchApi = { provider -> onIntent(ChatIntent.SwitchApi(provider)) },
                onClearQuoteReply = { quoteReply = null },
                onAlbumClick = {
                    showExtensionPanel = false
                    showImagePicker = true
                },
                onCameraClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                onVideoCallClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        onNavigateToVoiceCall(companionId)
                    } else {
                        pendingAudioAction = { onNavigateToVoiceCall(companionId) }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceCallClick = {
                    showExtensionPanel = false
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        onNavigateToVoiceCall(companionId)
                    } else {
                        pendingAudioAction = { onNavigateToVoiceCall(companionId) }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onTtsModeClick = {
                    val nextMode = when (ttsConfig.mode) {
                        ChatTtsMode.VOICE_BAR -> ChatTtsMode.SILENT
                        else -> ChatTtsMode.VOICE_BAR
                    }
                    viewModel.setTtsMode(nextMode)
                    showExtensionPanel = false
                    scope.launch { snackbarHostState.showSnackbar("语音模式：${nextMode.displayName}") }
                },
                onLocationClick = { onIntent(ChatIntent.ShareLocation) },
                onVoiceInputClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        showExtensionPanel = false
                        showVoiceRecorder = true
                    } else {
                        pendingAudioAction = {
                            showExtensionPanel = false
                            showVoiceRecorder = true
                        }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStickerPanelClick = {
                    val opening = !showStickerPanel
                    showExtensionPanel = false
                    if (opening) {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        showStickerPanel = true
                    } else {
                        showStickerPanel = false
                    }
                },
                onSendMessage = { msg ->
                    val currentQuote = quoteReply
                    val content = if (currentQuote != null) encodeQuotedMessage(currentQuote, msg) else msg
                    quoteReply = null
                    onIntent(ChatIntent.SendText(content))
                },
                onPlusClick = {
                    val opening = !showExtensionPanel
                    showStickerPanel = false
                    if (opening) {

                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        showExtensionPanel = true
                    } else {
                        showExtensionPanel = false
                    }
                },
                onVoiceRecordStart = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        showExtensionPanel = false
                        showStickerPanel = false
                        isCanceling = false
                        isRecording = true
                        showVoiceRecorder = true
                    } else {
                        pendingAudioAction = {
                            showExtensionPanel = false
                            showStickerPanel = false
                            isCanceling = false
                            isRecording = true
                            showVoiceRecorder = true
                        }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceRecordStop = {
                    val audioPath = voiceRecorder.stop()
                    isRecording = false
                    showVoiceRecorder = false
                    if (audioPath != null && recordingDuration >= 1) {
                        onIntent(ChatIntent.SendVoice(audioPath, recordingDuration))
                    }
                },
                onVoiceRecordCancel = {
                    voiceRecorder.cancel()
                    isRecording = false
                    showVoiceRecorder = false
                }
            )
        }

        if (showVoiceRecorder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim.copy(alpha = 0.5f))
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "录音中...",
                        fontSize = 18.sp,
                        color = colors.inverseContent,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${recordingDuration}s",
                        fontSize = 48.sp,
                        color = colors.inverseContent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isCanceling) "松开取消" else "松开发送，上滑取消",
                        fontSize = 13.sp,
                        color = if (isCanceling) colors.danger else colors.inverseContent.copy(alpha = 0.72f)
                    )
                }
            }
        }

        // 拖入预览态：拖拽悬停时升起投递提示（LOW 端为纯色提示）
        if (stickerDragHovering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "松手导入表情包",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.inverseContent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.scrim.copy(alpha = 0.6f))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }

        // 表情包拖拽导入命名过场：与 StickerPanel "+" 入口收口同一「导入 → 命名 → 确认」流程
        pendingImportUri?.let { importUri ->
            StickerImportOverlay(
                visible = true,
                previewUri = importUri,
                suggestedName = suggestStickerName(context, importUri),
                isNameTaken = { StickerManager.getInstance(context).isNameTaken(it) },
                onConfirm = { name, semantic, aliases ->
                    scope.launch {
                        StickerManager.getInstance(context)
                            .importStickerFile(importUri, name, semantic, aliases)
                            .onSuccess {
                                pendingImportUri = null
                                snackbarHostState.showSnackbar("已添加表情「$name」")
                            }
                            .onFailure { snackbarHostState.showSnackbar("导入失败: ${it.message}") }
                    }
                },
                onDismiss = { pendingImportUri = null }
            )
        }

        ChatTopBarRegion(
            companionId = companionId,
            companionData = companionData,
            isLoading = isLoading,
            showTypingSpinner = showTypingSpinner,
            onBackClick = exitChat,
            onDetailClick = onNavigateToDetail,
            adaptiveSizing = adaptiveSizing
        )

        val previewPath = previewImagePath
        // 图片预览集合「按需」计算（T03）：仅在预览激活时扫描图片消息正文，
        // 关闭时短路返回空表 → 不再随每次正文 delta 触发整表 O(n) 扫描（R2）。
        val previewModels = remember(previewPath, messageMetadata, messageBodies) {
            if (previewPath == null) {
                emptyList()
            } else {
                val paths = messageMetadata.mapNotNull { metadata ->
                    if (metadata.type != MessageType.IMAGE) return@mapNotNull null
                    val message = (messageBodies[metadata.id] as? MessageBodyState.Ready)?.value
                        ?: return@mapNotNull null
                    message.linkString.ifBlank { message.content }.takeIf { it.isNotBlank() }
                }.distinct()
                if (previewPath in paths) paths else listOf(previewPath)
            }
        }
        val previewIndex = remember(previewPath, previewModels) {
            previewPath?.let { previewModels.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        }
        FullscreenImageViewer(
            models = previewModels,
            initialIndex = previewIndex,
            visible = previewPath != null && previewModels.isNotEmpty(),
            onDismiss = { previewImagePath = null },
            onOpenFailed = { failed ->
                val failedPath = failed as? String ?: previewImagePath
                previewImagePath = null
                if (failedPath != null) {
                    scope.launch {
                        val result = openChatMedia(context, failedPath, "image/*")
                        if (!result) snackbarHostState.showSnackbar("无法打开该文件")
                    }
                }
            },
            scrimColor = colors.scrim
        )

        if (showImagePicker) {
            CustomImagePicker(
                maxSelection = 9,
                onConfirmed = { uris ->
                    showImagePicker = false
                    if (uris.isNotEmpty()) {
                        scope.launch {
                            for (uri in uris) {
                                try {
                                    val path = copyUriToCache(context, uri)
                                    if (path != null) {
                                        onIntent(ChatIntent.SendImage(path))
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("图片处理失败: ${e.message}")
                                }
                            }
                        }
                    }
                },
                onDismiss = { showImagePicker = false }
            )
        }
    }

    confirmationRequest?.let { request ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.respondToConfirmation(request.id, confirmed = false) },
            title = {
                androidx.compose.material3.Text(
                    when (request.toolName) {
                        "automation_create" -> "AI 请求创建自动化"
                        "luckin_create_order" -> "AI 请求确认下单"
                        else -> "AI 请求执行操作"
                    }
                )
            },
            text = {
                androidx.compose.material3.Text(request.summary)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.respondToConfirmation(request.id, confirmed = true) }
                ) { androidx.compose.material3.Text("确认") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.respondToConfirmation(request.id, confirmed = false) }
                ) { androidx.compose.material3.Text("取消") }
            }
        )
    }
        }
        }
}

private fun openChatMedia(context: Context, path: String, mimeType: String): Boolean {
    val file = java.io.File(path)
    if (!file.exists()) return false

    return try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.yunian.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 合并后的滚动快照：一次读取 `layoutInfo` 同时拿到「可见消息 id 集」与「是否接近顶部」，
 * 供 `ChatScreen` 的单一观察者分发「可见正文加载」与「触顶加载更早」（R5）。
 */
private data class VisibleListSnapshot(
    val visibleMessageIds: Set<Long>,
    val nearTop: Boolean,
)

/**
 * 某个聊天行是否有「资格」播放入场果冻动画（基于结构行 [ChatRow]）。**纯函数、无副作用。**
 *
 * - 时间分隔线不弹（弹起来很怪）；
 * - 流式临时消息（id < 0）不弹：它每秒都在刷新，且落库时会换成正式正 id 再弹一次 → 会重复；
 * - 只有「比进入会话时的最新消息还新」的正 id 消息才算新消息 —— 这样上滑加载更早的历史
 *   时（id 都小于水位线）不会被整屏弹一遍；
 * - 本轮进行中的工具调用卡片 [ChatRow.LiveToolGroup]：只要它出现就弹（它只在工具真正跑的时候存在）。
 *
 * 「只弹一次」的幂等由调用方 `ChatScreen` 的 `jellyPlayedIds` 保证 —— 行首帧组合后经
 * `onJellyPlayed` effect 上报，随即被移出待播集合 `jellyPlayIds`，故滚动回收重组不会重播；
 * 本函数只判定「是否够格」，因此是纯函数（不再在组合期写任何共享状态，R10）。
 */
private fun jellyQualifiesForEntrance(row: ChatRow, watermarkId: Long?): Boolean = when (row) {
    is ChatRow.TimeDivider -> false
    is ChatRow.LiveToolGroup -> true
    is ChatRow.Message -> {
        val id = row.metadata.id
        id >= 0L && watermarkId != null && id > watermarkId
    }
}

/**
 * T04（C1）· 已到底部时跳过冗余滚动。
 *
 * `reverseLayout = true` 的列表 index 0 即「最新消息 / 视觉底部」；进入窗口内多处 effect 会对
 * 「已经处在 (0, 0)」的位置重复发起 `scrollToItem(0)` → 触发冗余 measure/layout（R6）。
 * 本扩展只在**确实不在底部**时才滚动，对以下既有行为保持不变：
 * - 新消息到达且用户停在底部 → 自动到底；
 * - 自己发送的消息 → 强制到底（此时通常 firstVisibleItemIndex != 0）；
 * - 触屏回底 / 打字指示 / 生图等待 / 键盘弹起 → 到底。
 */
private suspend fun LazyListState.scrollToBottomIfNeeded() {
    if (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0) {
        scrollToItem(0)
    }
}

/** 拖拽导入时的文件类型（内容嗅探判定，不依赖 clip MIME）。 */
private enum class DroppedKind { IMAGE, ZIP, UNKNOWN }

/**
 * 按文件头魔数判定拖入的 uri 是图片还是 zip（Bug2 对齐）：
 * `PK\x03\x04` / `PK\x05\x06` / `PK\x07\x08` → zip；否则交给 [ImageFormatSniffer] 判定图片。
 * 读取失败返回 UNKNOWN，不抛异常。
 */
private fun sniffDroppedKind(context: Context, uri: Uri): DroppedKind {
    val header = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(ZIP_OR_IMAGE_HEADER_SIZE)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset == buffer.size) buffer else buffer.copyOf(offset)
        }
    } catch (_: Exception) {
        null
    } ?: return DroppedKind.UNKNOWN

    val isZip = header.size >= 4 &&
        header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
        (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
    if (isZip) return DroppedKind.ZIP
    return if (ImageFormatSniffer.detect(header) != null) DroppedKind.IMAGE else DroppedKind.UNKNOWN
}

/** 拖拽 ZIP 导入：拷贝到 cache 后走既有 ZIP 合并通道，用 snackbar 反馈结果。 */
private suspend fun importStickerZipFromUri(
    context: Context,
    uri: Uri,
    snackbarHostState: SnackbarHostState
) {
    try {
        val path = copyUriToCache(context, uri)
        val count = path?.let { StickerManager.getInstance(context).importStickerZip(it) } ?: 0
        snackbarHostState.showSnackbar("成功导入 $count 个表情包")
    } catch (e: Exception) {
        snackbarHostState.showSnackbar("导入失败: ${e.message}")
    }
}

/** 拖拽嗅探读取的头部字节数（zip 魔数 4 字节 / 图片魔数 ≤16 字节）。 */
private const val ZIP_OR_IMAGE_HEADER_SIZE = 16


