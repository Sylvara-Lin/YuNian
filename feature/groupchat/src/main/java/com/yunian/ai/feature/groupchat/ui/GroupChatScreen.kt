package com.yunian.ai.feature.groupchat.ui
import com.yunian.ai.uicommon.icon.AppIcons


import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.yunian.ai.uicommon.component.rememberHorizontalSwipeGuard
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.groupchat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.database.model.GroupMessage
import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.common.StickerManager
import com.yunian.ai.common.PermissionManager
import androidx.compose.runtime.key
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.yunian.ai.uicommon.component.ChatInputExtensionPanel
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import androidx.compose.runtime.CompositionLocalProvider
import com.yunian.ai.uicommon.theme.LocalChatGlassBackdrop
import com.yunian.ai.uicommon.theme.LocalChatGlassEnabled
import com.yunian.ai.uicommon.theme.appBubbleGlass
import com.yunian.ai.uicommon.theme.appBubbleBackground
import com.yunian.ai.uicommon.theme.bubbleBlurRadiusFor
import com.yunian.ai.uicommon.theme.buildBubblePath
import com.yunian.ai.uicommon.theme.AppBubbleSide
import com.yunian.ai.uicommon.theme.AppBubbleSpec
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.rememberAdaptiveSizing
import com.yunian.ai.uicommon.component.AppMessageAvatar
import androidx.compose.foundation.layout.BoxWithConstraints
import com.yunian.ai.database.model.Message as MetadataMessage
import com.yunian.ai.uicommon.component.glass.ProvidePageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.AppListItemLayout
import com.yunian.ai.uicommon.component.StickerPanel
import com.yunian.ai.uicommon.component.getChatBackgroundByKey
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import com.yunian.ai.uicommon.component.isCustomBackground
import com.yunian.ai.uicommon.component.rememberBackgroundBitmap
import com.yunian.ai.uicommon.image.viewer.FullscreenImageViewer
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.feature.groupchat.GroupChatViewModel
import com.yunian.ai.feature.groupchat.GroupChatViewModelFactory
import com.yunian.ai.common.HardwareInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val stickerManager = remember { StickerManager.getInstance(context) }

    val viewModel: GroupChatViewModel = viewModel(
        factory = GroupChatViewModelFactory(context.applicationContext as Application, groupId)
    )
    val userAvatar by viewModel.userAvatar.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val messageMetadata by viewModel.messageMetadata.collectAsState()
    val messageBodies by viewModel.messageBodies.collectAsState()
    // 时间分割线行派生（同步私聊呈现：≥5 分钟间隔插入居中分割线，消息行不再逐条挂时间戳）
    val chatRows = remember(messageMetadata) { buildGroupChatRows(messageMetadata) }
    val groupData by viewModel.groupData.collectAsState()
    val companions by viewModel.allCompanions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRegenerating by viewModel.isRegenerating.collectAsState()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val systemInDarkTheme = isSystemInDarkTheme()
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDarkTheme
    }
    val perfTier = remember { HardwareInfo.tier }

    var showExtensionPanel by remember { mutableStateOf(false) }
    var showStickerPanel by remember { mutableStateOf(false) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }

    // G5：群成员按 id 预建 Map，以 O(1) 查找替代逐条 `companions.find { }`（O(成员数)）。
    val companionMap = remember(companions) { companions.associateBy { it.id } }
    // G6：稳定化 item 回调，避免每次组合新建 lambda 破坏 Compose skipping。
    val onImageClick: (String) -> Unit = remember { { path -> previewImagePath = path } }
    // 群聊图片预览集合改为「按需」计算（G2，见下方 FullscreenImageViewer 处），
    // 不再随每次正文 delta 触发整表 O(n) 扫描。

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch { snackbarHostState.showSnackbar("相机功能开发中...") }
        } else {
            if (PermissionManager.shouldShowRationale(context as android.app.Activity, PermissionManager.CAMERA)) {
                scope.launch { snackbarHostState.showSnackbar(PermissionManager.getPermissionRationale(PermissionManager.CAMERA)) }
            } else {
                scope.launch { snackbarHostState.showSnackbar("相机权限被拒绝，请在设置中开启") }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val path = copyUriToCache(context, it, "image")
                    if (path != null) {
                        viewModel.sendImageMessage(path)
                    } else {
                        snackbarHostState.showSnackbar("图片读取失败")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("发送图片失败: ${e.message}")
                }
            }
        }
    }

    fun handleAlbumClick() {
        showExtensionPanel = false
        if (PermissionManager.canPickImageWithoutPermission()) {
            imagePickerLauncher.launch("image/*")
        } else {
            val requiredPermissions = PermissionManager.getImagePickPermissions()
            if (requiredPermissions.isEmpty()) {
                imagePickerLauncher.launch("image/*")
            } else if (PermissionManager.hasPermissions(context, requiredPermissions.toList())) {
                imagePickerLauncher.launch("image/*")
            } else {
                scope.launch { snackbarHostState.showSnackbar("需要存储权限才能选择图片") }
            }
        }
    }

    fun handleCameraClick() {
        showExtensionPanel = false
        if (PermissionManager.hasPermission(context, PermissionManager.CAMERA)) {
            scope.launch { snackbarHostState.showSnackbar("相机功能开发中...") }
        } else {
            cameraPermissionLauncher.launch(PermissionManager.CAMERA)
        }
    }

    val stickerPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val path = copyUriToCache(context, it, "sticker")
                    if (path != null) {
                        val count = stickerManager.importStickerZip(path)
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

    var messagesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(200)
        messagesReady = true
    }

    var hasDoneInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(messageMetadata, messagesReady) {
        if (messagesReady && messageMetadata.isNotEmpty() && !hasDoneInitialScroll) {
            listState.scrollToItem((chatRows.size - 1).coerceAtLeast(0))
            hasDoneInitialScroll = true
        }
    }

    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long }.toSet()
        }.collect { visibleIds ->
            viewModel.loadVisibleMessageBodies(visibleIds)
        }
    }

    val lastMessageId = messageMetadata.lastOrNull()?.id
    LaunchedEffect(lastMessageId) {
        if (hasDoneInitialScroll && messageMetadata.isNotEmpty()) {
            listState.animateScrollToItem((chatRows.size - 1).coerceAtLeast(0))
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messageMetadata.isNotEmpty()) {
            listState.scrollToItem((chatRows.size - 1).coerceAtLeast(0))
        }
    }

    val themeBgColor = AppTheme.colors.background
    // 背景解析（getChatBackgroundByKey / isCustomBackground）全是纯同步计算，不读磁盘，必须「同步
    // 派生」而非放进 LaunchedEffect + withContext(IO) 异步回填 —— 否则首帧仍是初始默认值，先渲染
    // 默认背景再切真实背景（进入群聊页闪一下），与 ChatScreen 同一类缺陷。群聊有效 key 直接取
    // globalBgKey（同步可得），与首页 MainScreen 同 key，可命中启动时预热缓存，首帧即出图。
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

    val resolvedBgKey = globalBgKey
    val isCustomBg = isCustomBackground(resolvedBgKey)
    val customBgKey = if (isCustomBg) resolvedBgKey else ""
    // 无条件调用：非自定义背景时 customBgKey 为空，内部返回 null。消除条件组合组进/出导致的
    // 首帧不发起位图加载问题。
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
        resolvedBgKey == "default" -> themeBgColor
        else -> chatBgColor
    }

    BackHandler(enabled = previewImagePath == null, onBack = onNavigateBack)

    // 聊天页独立的背景捕获层：群聊可用自己的背景（预设渐变 / 自定义图片），
    // 液态玻璃必须折射「当前真正显示的背景」，不能沿用 MainScreen 的主背景 backdrop。
    val chatBackdrop = key(resolvedBgKey, isCustomBg, customBgPainter, chatBgGradient, backgroundColor) {
        rememberLayerBackdrop {
            drawContent()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            CompositionLocalProvider(
                LocalChatGlassBackdrop provides chatBackdrop,
                LocalChatGlassEnabled provides (perfTier != HardwareInfo.Tier.LOW)
            ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {

            if (messagesReady) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(rememberHorizontalSwipeGuard())
                        .pointerInput(Unit) { detectTapGestures { keyboardController?.hide() } },
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp,
                        top = 120.dp,
                        bottom = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = chatRows,
                        key = { it.key },
                        // G1：契约正确（同一 key 的 contentType 恒定）。消息行统一渲染 GroupChatBubble，
                        // 真正的 text/image 差异依赖正文；分割线行常量。
                        contentType = { row ->
                            if (row is GroupChatRow.Message) "group_message" else "group_divider"
                        }
                    ) { row ->
                        when (row) {
                            is GroupChatRow.Divider -> GroupTimeDividerItem(row.timestamp)
                            is GroupChatRow.Message -> when (val body = messageBodies[row.metadata.id] ?: MessageBodyState.Loading) {
                                MessageBodyState.Loading -> Box(
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                }
                                is MessageBodyState.Error -> Text(
                                    text = body.message,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.retryMessageBody(row.metadata.id) }
                                        .padding(12.dp),
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                is MessageBodyState.Ready -> {
                                    val message = body.value
                                    // G5：O(1) Map 查找替代线性 find。
                                    val companion = companionMap[message.companionId]
                                    GroupChatBubble(
                                        message = message, companion = companion,
                                        isUser = message.companionId == -1L,
                                        userAvatar = userAvatar, userName = userName,
                                        onImageClick = onImageClick
                                    )
                                }
                            }
                        }
                    }

                    if (isRegenerating) {
                        item(key = "regenerating_indicator") {
                            GroupRegeneratingBubble()
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth())
            }

            var showMentionPicker by remember { mutableStateOf(false) }
            var inputText by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(28.dp),
                            surfaceColor = AppTheme.colors.surface
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {

                        IconButton(
                            onClick = {
                                val opening = !showExtensionPanel
                                showStickerPanel = false
                                showMentionPicker = false
                                if (opening) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                    showExtensionPanel = true
                                } else {
                                    showExtensionPanel = false
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.Plus,
                                contentDescription = "更多功能",
                                tint = if (showExtensionPanel) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = { showMentionPicker = !showMentionPicker },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.AtSign,
                                contentDescription = "@艾特",
                                tint = if (showMentionPicker) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Box(
                            modifier = Modifier.weight(1f).height(40.dp)
                                .clip(RoundedCornerShape(21.dp))
                                .drawGlass(
                                    backdrop = LocalPageBackdrop.current,
                                    shape = RoundedCornerShape(21.dp),
                                    surfaceColor = AppTheme.colors.surfaceVariant
                                )
                                .padding(horizontal = 16.dp, vertical = 0.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (inputText.isEmpty()) {
                                            Text("输入消息...",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                                color = AppTheme.colors.onSurfaceVariant)
                                        }
                                        innerTextField()
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Send
                                ),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (inputText.isNotBlank() && !isLoading) {
                                            viewModel.sendMessage(inputText.trim())
                                            inputText = ""
                                        }
                                    }
                                ),
                                maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    color = AppTheme.colors.onSurface
                                )
                            )
                        }

                        val canSend = inputText.isNotBlank() && !isLoading
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(if (canSend) AppTheme.colors.primary else AppTheme.colors.surfaceVariant)
                                .clickable(enabled = canSend) {
                                    viewModel.sendMessage(inputText.trim())
                                    inputText = ""
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            @Suppress("DEPRECATION")
                            Icon(
                                imageVector = AppIcons.Send,
                                contentDescription = "发送",
                                tint = if (canSend) AppTheme.colors.onPrimary else AppTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showMentionPicker && companions.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically(animationSpec = tween(200)) + fadeIn(tween(200)),
                    exit = androidx.compose.animation.shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200))
                ) {
                    val activeCompanions = remember(groupData, companions) {
                        val activeCompanionIds = groupData?.getCompanionIdList()?.toSet() ?: emptySet()
                        companions.filter { activeCompanionIds.contains(it.id) }
                    }

                    if (activeCompanions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .drawGlass(
                                    backdrop = LocalPageBackdrop.current,
                                    shape = RoundedCornerShape(16.dp),
                                    surfaceColor = AppTheme.colors.surface
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "选择要@的角色",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                color = AppTheme.colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                activeCompanions.forEach { companion ->
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(AppTheme.colors.surfaceVariant)
                                            .clickable {
                                                inputText = "$inputText@${companion.name} "
                                                showMentionPicker = false
                                            }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {

                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(AppTheme.colors.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (companion.avatarUrl != null) {
                                                AsyncImage(
                                                    model = companion.avatarUrl,
                                                    contentDescription = companion.name,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Text(
                                                    text = companion.name.firstOrNull()?.toString() ?: "?",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AppTheme.colors.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = companion.name,
                                            fontSize = 13.sp,
                                            color = AppTheme.colors.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                StickerPanel(
                    isVisible = showStickerPanel,
                    onStickerClick = { sticker ->
                        viewModel.sendUserSticker(sticker)
                        showStickerPanel = false
                    },
                    onImportClick = {
                        stickerPickerLauncher.launch("application/zip")
                    },
                    onDeleteAllClick = {
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
                    // 液态玻璃：取样群聊页背景 backdrop（null 时面板内部自动回退纯色）
                    backdrop = LocalChatGlassBackdrop.current
                )

                ChatInputExtensionPanel(
                    isVisible = showExtensionPanel,
                    onAlbumClick = { handleAlbumClick() },
                    onCameraClick = { handleCameraClick() },
                    onVideoCallClick = {},
                    onLocationClick = {},
                    onVoiceInputClick = {},
                    onStickerClick = {
                        showExtensionPanel = false
                        showStickerPanel = !showStickerPanel
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(28.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = stringResource(R.string.group_chat),
                        tint = AppTheme.colors.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = isLoading,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        label = "title_switch"
                    ) { loading ->
                        if (loading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = AppTheme.colors.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.group_typing),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 14.sp
                                    ),
                                    color = AppTheme.colors.onSurfaceVariant
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {

                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.colors.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (groupData?.avatarUrl != null) {
                                        AsyncImage(
                                            model = groupData?.avatarUrl,
                                            contentDescription = groupData?.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            text = groupData?.name?.firstOrNull()?.toString() ?: "?",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppTheme.colors.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = groupData?.name ?: stringResource(R.string.group_chat),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    ),
                                    color = AppTheme.colors.onBackground
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { onNavigateToDetail(groupId) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Info,
                        contentDescription = "群详情",
                        tint = AppTheme.colors.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        val previewPath = previewImagePath
        // G2：图片预览集合「按需」计算 —— 仅在预览激活时扫描，关闭时短路返回空表，
        // 因此不再随每次正文 delta 触发整表 O(n) 扫描。
        val previewModels = remember(previewPath, messageMetadata, messageBodies) {
            if (previewPath == null) {
                emptyList()
            } else {
                val paths = messageMetadata.mapNotNull { meta ->
                    val ready = messageBodies[meta.id] as? MessageBodyState.Ready
                        ?: return@mapNotNull null
                    val content = ready.value.content
                    if (!content.startsWith("[图片]")) return@mapNotNull null
                    content.removePrefix("[图片] ").trim().takeIf { it.isNotBlank() }
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
            scrimColor = Color.Black
        )
        }
    }
        }
}

@Composable
fun GroupImageMessageBubble(
    imagePath: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {

    val imageFile = remember(imagePath) { File(imagePath) }

    if (imageFile.exists()) {
        // 尺寸同步私聊 ImageMessageContent：自适应比例 + imageMaxWidth/Height/CornerRadius
        AsyncImage(
            model = imageFile.absolutePath,
            contentDescription = "图片",
            modifier = modifier
                .widthIn(max = AppTheme.dimens.imageMaxWidth)
                .heightIn(max = AppTheme.dimens.imageMaxHeight)
                .clip(RoundedCornerShape(AppTheme.dimens.imageCornerRadius))
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        )
                    } else Modifier
                ),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier
                .widthIn(max = AppTheme.dimens.imagePlaceholderMaxWidth)
                .clip(RoundedCornerShape(AppTheme.dimens.imageCornerRadius))
                .background(AppTheme.colors.secondaryBubbleBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Image,
                    contentDescription = null,
                    tint = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "图片",
                    fontSize = 12.sp,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri, prefix: String): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val extension = when (context.contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "application/zip" -> "zip"
            else -> "tmp"
        }
        val fileName = "${prefix}_${System.currentTimeMillis()}.$extension"

        val cacheFile = File(context.cacheDir, fileName)
        inputStream.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@Composable
fun GroupStickerMessageBubble(
    stickerName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(stickerName) {
        val manager = StickerManager.getInstance(context)
        var sticker = manager.findStickerByDescription(stickerName)
        if (sticker == null && !stickerName.endsWith(".png")) {
            sticker = manager.findStickerByDescription("$stickerName.png")
        }
        if (sticker != null) {
            // 同步私聊 P11：气泡显示 ≤140dp，用 ≤512px 降采样加载，避免全尺寸位图内存抖动
            bitmap = manager.loadStickerBitmapSampled(sticker.path, 512)
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = stickerName,
            modifier = modifier
                .sizeIn(maxWidth = 140.dp, maxHeight = 140.dp)
                .width(120.dp)
                .height(120.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Brush,
                contentDescription = stickerName,
                modifier = Modifier.size(32.dp),
                tint = AppTheme.colors.metadataContent
            )
        }
    }
}

@Composable
fun GroupRegeneratingBubble() {
    val adaptiveSizing = rememberAdaptiveSizing()
    val infiniteTransition = rememberInfiniteTransition(label = "group_regenerate")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, 150), RepeatMode.Reverse), label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, 300), RepeatMode.Reverse), label = "dot3"
    )

    GroupMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        avatar = { Spacer(modifier = Modifier.size(adaptiveSizing.avatarSize)) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "正在重新生成",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = adaptiveSizing.fontSizeBody.sp,
                    color = AppTheme.colors.secondaryBubbleContent
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            repeat(3) { i ->
                val alpha = listOf(dot1Alpha, dot2Alpha, dot3Alpha)[i]
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(AppTheme.colors.onSurfaceVariant)
                )
                if (i < 2) Spacer(modifier = Modifier.width(3.dp))
            }
        }
    }
}

/**
 * 群聊消息行框架 —— 镜像 feature:chat 的 ChatMessageFrame（feature 模块不可互依，故本地实现）。
 * 气泡 Path / 玻璃参数 / 边框 / 内边距 / 头像尺寸 / 宽度约束与私聊**逐项一致**；
 * 差异点仅两处：可选的群成员名字行（speakerName，私聊无）+ 无逐条时间戳（时间交给分割线）。
 */
@Composable
private fun GroupMessageFrame(
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawBubble: Boolean = true,
    speakerName: String? = null,
    content: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    val dimens = AppTheme.dimens
    val glassBackdrop = LocalChatGlassBackdrop.current
    val glassEnabled = LocalChatGlassEnabled.current

    val bubbleColor = if (isMine) colors.primaryBubbleBackground else colors.secondaryBubbleBackground
    // 与私聊一致：去上色的中性玻璃面，差异感由箭头方向与左右对齐承担
    val glassSurface = colors.secondaryBubbleBackground.copy(alpha = 0.55f)

    val bubbleArrowWidth = 5.dp
    val bubbleContentPadding = Modifier.padding(
        horizontal = adaptiveSizing.chatBubblePaddingHorizontal,
        vertical = adaptiveSizing.chatBubblePaddingVertical
    )
    // Path 工厂记忆化：只在方向/圆角变化时重建（配合 core 的 drawWithCache 按 size 缓存）
    val bubblePathFactory: (androidx.compose.ui.geometry.Size, androidx.compose.ui.unit.Density) -> Path =
        remember(isMine, adaptiveSizing.cornerRadius) {
            { size, density ->
                fun Dp.px(): Float = with(density) { this@px.toPx() }
                buildBubblePath(
                    rectLeft = if (isMine) 0f else bubbleArrowWidth.px(),
                    rectRight = size.width - if (isMine) bubbleArrowWidth.px() else 0f,
                    rectHeight = size.height,
                    radius = adaptiveSizing.cornerRadius.px(),
                    arrowWidthPx = bubbleArrowWidth.px(),
                    arrowHeightPx = 8.dp.px(),
                    arrowOffsetYPx = 14.dp.px(),
                    side = if (isMine) AppBubbleSide.End else AppBubbleSide.Start
                )
            }
        }
    val bubbleModifier = if (drawBubble) {
        Modifier
            .then(
                if (glassBackdrop != null && glassEnabled) {
                    Modifier.appBubbleGlass(
                        backdrop = glassBackdrop,
                        pathFactory = bubblePathFactory,
                        glassSurfaceColor = glassSurface,
                        fallbackColor = bubbleColor,
                        borderColor = colors.secondaryBubbleBorder,
                        borderWidth = dimens.bubbleBorderWidth,
                        blurRadius = bubbleBlurRadiusFor(HardwareInfo.tier)
                    )
                } else {
                    Modifier.appBubbleBackground(
                        color = bubbleColor,
                        borderColor = colors.secondaryBubbleBorder,
                        borderWidth = dimens.bubbleBorderWidth,
                        spec = AppBubbleSpec(
                            cornerRadius = adaptiveSizing.cornerRadius,
                            side = if (isMine) AppBubbleSide.End else AppBubbleSide.Start,
                            arrowWidth = bubbleArrowWidth,
                            arrowHeight = 8.dp,
                            arrowOffsetY = 14.dp
                        )
                    )
                }
            )
            .then(bubbleContentPadding)
    } else {
        Modifier
    }

    // 名字行（10sp 字 + 16sp 行高 + 2dp 间距）的占位高度：头像据此下移，与气泡顶部对齐
    val nameLineHeight = 18.dp

    AppListItemLayout(
        isStartAligned = !isMine,
        startSlot = {
            Box(
                modifier = Modifier
                    // 有名字行时头像下移，顶部与气泡平齐（名字悬在头像右上方），不再顶着名字行
                    .padding(top = if (!speakerName.isNullOrBlank()) nameLineHeight else 0.dp)
                    .size(adaptiveSizing.avatarSize),
                contentAlignment = Alignment.TopCenter
            ) {
                avatar()
            }
        },
        endSlot = {},
        modifier = modifier.fillMaxWidth(),
        slotGap = dimens.avatarGap
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val oppositeReserve = adaptiveSizing.avatarSize + dimens.avatarGap + bubbleArrowWidth
            val bubbleMaxWidth = (maxWidth - oppositeReserve).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
            ) {
                if (!speakerName.isNullOrBlank()) {
                    Text(
                        text = speakerName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = colors.metadataContent,
                        // 名字与气泡「主体」左缘对齐：带气泡时主体从 arrowWidth 处起画（箭头占 0..arrowWidth）
                        modifier = Modifier.padding(start = bubbleArrowWidth, bottom = 2.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        // 无气泡媒体（表情包/图片）补上箭头占位偏移，使媒体边缘与
                        // 相邻文字气泡的主体边缘对齐（AI 左缘 +5dp，用户右缘 +5dp）
                        .then(
                            if (drawBubble) Modifier else Modifier.padding(
                                start = if (!isMine) bubbleArrowWidth else 0.dp,
                                end = if (isMine) bubbleArrowWidth else 0.dp
                            )
                        )
                        .then(bubbleModifier)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun GroupChatBubble(
    message: GroupMessage,
    companion: com.yunian.ai.database.model.CompanionEntity?,
    isUser: Boolean,
    userAvatar: String?,
    userName: String,
    onImageClick: (String) -> Unit = {}
) {
    val adaptiveSizing = rememberAdaptiveSizing()

    // 内容类型判定（同步私聊：表情包/图片无气泡框直接显示）
    val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
    val isStickerMessage = message.content.startsWith("[") && message.content.endsWith("]") &&
            message.content.removeSurrounding("[", "]") !in systemTags
    val isImageMessage = message.content.startsWith("[图片]")
    val imagePath = if (isImageMessage) {
        message.content.removePrefix("[图片] ").trim()
    } else null

    GroupMessageFrame(
        isMine = isUser,
        adaptiveSizing = adaptiveSizing,
        drawBubble = !isStickerMessage && !(isImageMessage && imagePath != null),
        speakerName = if (!isUser) companion?.name else null,
        avatar = {
            AppMessageAvatar(
                isMine = isUser,
                companionAvatarUrl = companion?.avatarUrl,
                companionName = companion?.name,
                userAvatarUrl = userAvatar,
                userName = userName,
                size = adaptiveSizing.avatarSize
            )
        }
    ) {
        when {
            isStickerMessage -> {
                GroupStickerMessageBubble(
                    stickerName = message.content.removeSurrounding("[", "]")
                )
            }
            isImageMessage && imagePath != null -> {
                GroupImageMessageBubble(
                    imagePath = imagePath,
                    onClick = { onImageClick(imagePath) }
                )
            }
            else -> {
                // 文本样式同步私聊 TextMessageContent（字号/行高/颜色逐项一致）
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = adaptiveSizing.fontSizeBody.sp,
                        lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp,
                        color = AppTheme.colors.secondaryBubbleContent
                    ),
                    softWrap = true
                )
            }
        }
    }
}

// ===== 时间分割线（同步私聊 TimeDivider 模式） =====

private const val GROUP_TIME_DIVIDER_INTERVAL_MILLIS = 5 * 60 * 1000L

/** 群聊列表行：消息（key=消息 id，Long，供正文按需加载）+ 时间分割线（String key，被 as? Long 安全过滤） */
private sealed class GroupChatRow {
    abstract val key: Any

    data class Message(val metadata: MetadataMessage) : GroupChatRow() {
        override val key: Any get() = metadata.id
    }

    data class Divider(val timestamp: Long) : GroupChatRow() {
        override val key: Any get() = "group_divider_$timestamp"
    }
}

private fun buildGroupChatRows(metadata: List<MetadataMessage>): List<GroupChatRow> {
    val rows = mutableListOf<GroupChatRow>()
    var previousTimestamp: Long? = null
    for (item in metadata) {
        if (previousTimestamp == null || item.timestamp - previousTimestamp >= GROUP_TIME_DIVIDER_INTERVAL_MILLIS) {
            rows += GroupChatRow.Divider(item.timestamp)
        }
        rows += GroupChatRow.Message(item)
        previousTimestamp = item.timestamp
    }
    return rows
}

@Composable
private fun GroupTimeDividerItem(timestamp: Long) {
    val label = remember(timestamp) { formatGroupTimeDividerLabel(timestamp) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.dimens.timeDividerVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(AppTheme.dimens.timeDividerCornerRadius))
                .background(AppTheme.colors.dividerBackground)
                .padding(
                    horizontal = AppTheme.dimens.timeDividerHorizontalPadding,
                    vertical = AppTheme.dimens.timeDividerInnerVerticalPadding
                ),
            style = AppTheme.typography.labelSmall.copy(fontSize = AppTheme.dimens.timeDividerFontSize),
            color = AppTheme.colors.metadataContent,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatGroupTimeDividerLabel(timestamp: Long): String {
    val zoneId = java.time.ZoneId.systemDefault()
    val dateTime = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDateTime()
    val today = java.time.LocalDate.now(zoneId)
    val timeText = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when (dateTime.toLocalDate()) {
        today -> timeText
        today.minusDays(1) -> "昨天 $timeText"
        else -> dateTime.format(java.time.format.DateTimeFormatter.ofPattern("MM月dd日 HH:mm"))
    }
}
