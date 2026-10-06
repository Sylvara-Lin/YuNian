package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yunian.ai.database.model.ChatGroup
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.viewmodel.HomeGroupItem
import com.yunian.ai.uicommon.theme.PinkPrimary
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.rememberAdaptiveSizing
import com.kyant.capsule.ContinuousCapsule
import com.yunian.ai.database.viewmodel.ChatGroupViewModel
import com.yunian.ai.uicommon.component.AppListItemLayout
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.LiquidBottomTab
import com.yunian.ai.uicommon.component.glass.LiquidBottomTabs
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HomeTab {
    ALL, GROUP, FRIEND
}

@Composable
fun HomeScreen(
    onCompanionClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    onAddClick: () -> Unit = {},
    onCreateGroupClick: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
    groupViewModel: ChatGroupViewModel = viewModel()
) {
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()
    val groupItems by groupViewModel.groupItems.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(HomeTab.ALL) }
    val adaptiveSizing = rememberAdaptiveSizing()
    val colorScheme = AppTheme.colors
    val backdrop = LocalPageBackdrop.current

    // 首页会话长按菜单：状态下沉到各 list item 内部（每个 item 自己管自己的菜单），
    // 对齐 ChatMessageScaffold 模式——DropdownMenu 在 item 内部调用时自动锚定到该项，
    // 不会漂到屏幕左下角。这里只保留当前展开菜单的会话 id（防止多菜单同时展开）。
    var openMenuSessionId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()

            .background(Color.Transparent)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Box(modifier = Modifier.weight(1f))

                    Text(
                        text = "予念",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = colorScheme.onSurface
                    )

                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(
                                onClick = { onCreateGroupClick() },
                                backdrop = backdrop,
                                height = 36.dp,
                                horizontalPadding = 0.dp,
                                modifier = Modifier.size(36.dp),
                                surfaceColor = colorScheme.surfaceVariant.copy(alpha = 0.85f)
                            ) {
                                Icon(
                                    imageVector = AppIcons.Users,
                                    contentDescription = "创建群聊",
                                    modifier = Modifier.size(20.dp),
                                    tint = colorScheme.onSurface
                                )
                            }
                            GlassButton(
                                onClick = { onAddClick() },
                                backdrop = backdrop,
                                height = 36.dp,
                                horizontalPadding = 0.dp,
                                modifier = Modifier.size(36.dp),
                                surfaceColor = colorScheme.surfaceVariant.copy(alpha = 0.85f)
                            ) {
                                Icon(
                                    imageVector = AppIcons.User,
                                    contentDescription = "添加好友",
                                    modifier = Modifier.size(20.dp),
                                    tint = colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colorScheme.outlineVariant.copy(alpha = 0.25f))
                )

                Spacer(modifier = Modifier.height(12.dp))

                HomeTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    backdrop = backdrop
                )

                Spacer(modifier = Modifier.height(8.dp))

                val chatCount = when (val state = chatListState) {
                    is HomeViewModel.UiState.Ready -> state.items.size
                    else -> 0
                }
                Text(
                    text = "${chatCount} 个会话 · ${groupItems.size} 个群聊",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }

            // 群聊排序：置顶优先 + 群更新时间倒序（对齐单聊排序规则）。
            val displayGroups = when (selectedTab) {
                HomeTab.ALL, HomeTab.GROUP -> groupItems
                    .filterNot { it.isHidden }
                    .sortedWith(
                        compareByDescending<HomeGroupItem> { it.isPinned }
                            .thenByDescending { it.group.updatedAt }
                    )
                HomeTab.FRIEND -> emptyList()
            }
            // 单聊排序：置顶优先（summary 查询已按 isPinned DESC, lastMessageTimestamp DESC），
            // 再按会话最后消息时间倒序（summary 已有序，此处仅取末条消息时间兜底），
            // 隐藏的会话不渲染（AI 主动消息到达时摘要重建/更新时间自然重显）。
            val displayChats = when (val state = chatListState) {
                is HomeViewModel.UiState.Ready -> {
                    val visible = when (selectedTab) {
                        HomeTab.ALL, HomeTab.FRIEND -> state.items.filterNot { it.isHidden }
                        HomeTab.GROUP -> emptyList()
                    }
                    visible.sortedWith(
                        compareByDescending<ChatListItem> { it.isPinned }
                            .thenByDescending { it.lastMessage?.timestamp ?: it.companion.createdAt }
                    )
                }
                else -> emptyList()
            }

            when {
                chatListState is HomeViewModel.UiState.Error -> {
                    val message = (chatListState as HomeViewModel.UiState.Error).message
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorHomeState(message = message)
                    }
                }
                chatListState is HomeViewModel.UiState.Loading && groupItems.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colorScheme.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                displayGroups.isEmpty() && displayChats.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHomeState()
                    }
                }
                else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部导航（FloatingGlassBottomNav）是覆盖在内容之上的浮层，
                    // 其自身已含 navigationBars 内边距；内容必须让出「导航高度 + 手势条」，
                    // 否则联系人多时最后一条会被导航栏遮住（真机问题）。
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 80.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // key = 会话 id（稳定标识）：列表数据变化时 Compose 按 key 复用 item，
                    // 避免无 key 时整个列表强制重组（性能优化，不影响视觉）。
                    itemsIndexed(displayGroups, key = { _, item -> "group_${item.group.id}" }) { _, item ->
                        GroupListItem(
                            group = item.group,
                            isPinned = item.isPinned,
                            isMenuOpen = openMenuSessionId == item.group.id,
                            onClick = {
                                openMenuSessionId = null
                                onGroupClick(item.group.id)
                            },
                            onLongClick = { openMenuSessionId = item.group.id },
                            onMenuDismiss = { openMenuSessionId = null },
                            onMenuDelete = {
                                openMenuSessionId = null
                                groupViewModel.deleteGroupChat(item.group.id)
                            },
                            onMenuTogglePin = {
                                openMenuSessionId = null
                                groupViewModel.togglePinGroupChat(item.group.id)
                            },
                            onMenuHide = {
                                openMenuSessionId = null
                                groupViewModel.hideGroupChat(item.group.id)
                            },
                            adaptiveSizing = adaptiveSizing
                        )
                    }
                    itemsIndexed(displayChats, key = { _, item -> "chat_${item.companion.id}" }) { _, item ->
                        ChatListItem(
                            companion = item.companion,
                            lastMessage = item.lastMessage,
                            hasUnread = item.hasUnread,
                            isPinned = item.isPinned,
                            isMenuOpen = openMenuSessionId == item.companion.id,
                            onClick = {
                                openMenuSessionId = null
                                onCompanionClick(item.companion.id)
                            },
                            onLongClick = { openMenuSessionId = item.companion.id },
                            onMenuDismiss = { openMenuSessionId = null },
                            onMenuDelete = {
                                openMenuSessionId = null
                                viewModel.deleteChat(item.companion.id)
                            },
                            onMenuTogglePin = {
                                openMenuSessionId = null
                                viewModel.togglePinChat(item.companion.id)
                            },
                            onMenuHide = {
                                openMenuSessionId = null
                                viewModel.hideChat(item.companion.id)
                            },
                            adaptiveSizing = adaptiveSizing
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String
) {
    val colorScheme = AppTheme.colors

    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 4.dp, top = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariant
    )
}

@Composable
fun HomeTabBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = LocalPageBackdrop.current
) {
    val colorScheme = AppTheme.colors
    val isDark = colorScheme.background.luminance() < 0.5f
    val contentColor = if (isDark) Color.White else Color.Black

    val tabs = HomeTab.values().toList()
    val labels = mapOf(
        HomeTab.ALL to "全部",
        HomeTab.GROUP to "群聊",
        HomeTab.FRIEND to "好友"
    )
    val currentIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)

    var visualSelectedIndex by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(currentIndex)
    }
    var pendingNavigationIndex by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val currentOnTabSelected by androidx.compose.runtime.rememberUpdatedState(onTabSelected)

    androidx.compose.runtime.LaunchedEffect(currentIndex) { visualSelectedIndex = currentIndex }
    androidx.compose.runtime.LaunchedEffect(pendingNavigationIndex) {
        val index = pendingNavigationIndex ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos {}
        currentOnTabSelected(tabs[index])
        if (pendingNavigationIndex == index) pendingNavigationIndex = null
    }
    val selectTab: (Int) -> Unit = { index ->
        if (index != visualSelectedIndex) {
            visualSelectedIndex = index
            pendingNavigationIndex = index
        }
    }

    LiquidBottomTabs(
        selectedTabIndex = { visualSelectedIndex },
        onTabSelected = selectTab,
        backdrop = backdrop,
        tabsCount = tabs.size,
        modifier = Modifier.fillMaxWidth(),
        isDark = isDark,
        containerHeight = 56.dp,
        contentPadding = 4.dp
    ) {
        tabs.forEachIndexed { index, tab ->
            LiquidBottomTab(
                onClick = { selectTab(index) },
                modifier = Modifier.semantics { selected = index == visualSelectedIndex }
            ) {
                Text(
                    text = labels[tab] ?: "",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (index == visualSelectedIndex) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                        fontSize = 14.sp
                    ),
                    color = contentColor
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupListItem(
    group: ChatGroup,
    isPinned: Boolean,
    isMenuOpen: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuDismiss: () -> Unit = {},
    onMenuDelete: () -> Unit = {},
    onMenuTogglePin: () -> Unit = {},
    onMenuHide: () -> Unit = {},
    adaptiveSizing: AdaptiveSizing
) {
    val colorScheme = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // 捕获 item 在窗口中的位置：Popup 用此坐标精确弹出到手指按压处
    // 只在菜单打开时才更新坐标，避免每次 LazyColumn 重组都触发坐标回调（性能优化）
    var itemWindowPos by remember { mutableStateOf(IntOffset.Zero) }
    var itemHeightPx by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            if (isMenuOpen) {
                val bounds = coords.boundsInWindow()
                itemWindowPos = IntOffset(bounds.left.toInt(), bounds.top.toInt())
                itemHeightPx = bounds.height.toInt()
            }
        }
    ) {
        AppListItemLayout(
            isStartAligned = true,
            startSlot = {
                Box(
                    modifier = Modifier.size(adaptiveSizing.avatarSize),
                    contentAlignment = Alignment.TopEnd
                ) {
                    if (group.avatarUrl != null) {
                        AsyncImage(
                            model = group.avatarUrl,
                            contentDescription = group.name,
                            modifier = Modifier
                                .size(adaptiveSizing.avatarSize)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(adaptiveSizing.avatarSize)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            PinkPrimary.copy(alpha = 0.6f),
                                            PinkPrimary.copy(alpha = 0.3f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Users,
                                contentDescription = group.name,
                                tint = colorScheme.onPrimary,
                                modifier = Modifier.size(adaptiveSizing.iconSize)
                            )
                        }
                    }
                }
            },
            endSlot = {},
            modifier = Modifier
                .fillMaxWidth()
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = ContinuousCapsule,
                    surfaceColor = colorScheme.surfaceVariant
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            slotGap = AppTheme.dimens.avatarGap
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = adaptiveSizing.fontSizeBody.sp
                        ),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = AppIcons.Star,
                            contentDescription = "已顶置",
                            tint = PinkPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = "${group.getCompanionIdList().size} 人",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (adaptiveSizing.fontSizeBody - 1).sp,
                        lineHeight = 20.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 菜单锚定到 item 位置（手指按压处）弹出
        HomeSessionMenu(
            expanded = isMenuOpen,
            isPinned = isPinned,
            anchorPosition = itemWindowPos,
            itemHeightPx = itemHeightPx,
            onDismiss = onMenuDismiss,
            onDelete = onMenuDelete,
            onTogglePin = onMenuTogglePin,
            onHide = onMenuHide
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListItem(
    companion: CompanionEntity,
    lastMessage: ChatMessage?,
    hasUnread: Boolean = false,
    isPinned: Boolean = false,
    isMenuOpen: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuDismiss: () -> Unit = {},
    onMenuDelete: () -> Unit = {},
    onMenuTogglePin: () -> Unit = {},
    onMenuHide: () -> Unit = {},
    adaptiveSizing: AdaptiveSizing
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val time = lastMessage?.let { dateFormat.format(Date(it.timestamp)) } ?: ""

    val colorScheme = AppTheme.colors
    val haptic = LocalHapticFeedback.current

    // 捕获 item 在窗口中的位置：Popup 用此坐标精确弹出到手指按压处
    // 只在菜单打开时才更新坐标，避免每次 LazyColumn 重组都触发坐标回调（性能优化）
    var itemWindowPos by remember { mutableStateOf(IntOffset.Zero) }
    var itemHeightPx by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            if (isMenuOpen) {
                val bounds = coords.boundsInWindow()
                itemWindowPos = IntOffset(bounds.left.toInt(), bounds.top.toInt())
                itemHeightPx = bounds.height.toInt()
            }
        }
    ) {
        AppListItemLayout(
            isStartAligned = true,
            startSlot = {
                Box(
                    modifier = Modifier.size(adaptiveSizing.avatarSize),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(adaptiveSizing.avatarSize)
                            .clip(CircleShape)
                            .background(colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        if (companion.avatarUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(companion.avatarUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = companion.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = AppIcons.User,
                                contentDescription = null,
                                tint = AppTheme.colors.captionContent,
                                modifier = Modifier.size((adaptiveSizing.avatarSize * 0.58f))
                            )
                        }
                    }
                    if (hasUnread) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(PinkPrimary)
                        )
                    }
                }
            },
            endSlot = {},
            modifier = Modifier
                .fillMaxWidth()
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = ContinuousCapsule,
                    surfaceColor = colorScheme.surfaceVariant
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            slotGap = AppTheme.dimens.avatarGap
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = companion.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = adaptiveSizing.fontSizeBody.sp
                        ),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = AppIcons.Star,
                            contentDescription = "已顶置",
                            tint = PinkPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (time.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = adaptiveSizing.fontSizeSmall.sp
                            ),
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = lastMessage?.content ?: "还没有聊天记录，开始聊天吧",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (adaptiveSizing.fontSizeBody - 1).sp,
                        lineHeight = 20.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 菜单锚定到 item 位置（手指按压处）弹出
        HomeSessionMenu(
            expanded = isMenuOpen,
            isPinned = isPinned,
            anchorPosition = itemWindowPos,
            itemHeightPx = itemHeightPx,
            onDismiss = onMenuDismiss,
            onDelete = onMenuDelete,
            onTogglePin = onMenuTogglePin,
            onHide = onMenuHide
        )
    }
}

@Composable
fun ErrorHomeState(message: String) {
    val colorScheme = AppTheme.colors

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.MessageCircle,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "会话加载失败",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmptyHomeState() {
    val colorScheme = AppTheme.colors

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.MessageCircle,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "还没有聊天记录",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "去通讯录找你的女友聊天吧",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 首页会话长按菜单（删除 / 顶置 / 隐藏）
//
// 样式与 ChatMessageMenu（feature:chat）逐一对齐：
// 液态玻璃 DropdownMenu + PinkPrimary 0.22f 边框 + menuContent/menuIcon 主题色 +
// 删除项 danger 色 + 无 indication 点击（避免双重玻璃抖动）。
// 语义见 HomeSessionListOperator KDoc：删除=清记录（AI 主动消息落库时摘要重建、
// 会话重显）；隐藏=只藏入口（有新消息即重显）；顶置=列表最前（可取消）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的会话菜单（单聊/群聊共用；isPinned 控制顶置/取消顶置文案切换）。
 *
 * 用 Popup（Alignment.TopStart + offset）替代 DropdownMenu：
 * DropdownMenu 在 LazyColumn item 内部时锚点机制失效，Popup 漂到屏幕左下角；
 * Popup 的 offset 直接指定窗口坐标（由 onGloballyPositioned 捕获的 item 位置），
 * 精确弹出到手指按压的会话项处，且不受 LazyColumn 回收重建影响。
 */
@Composable
private fun HomeSessionMenu(
    expanded: Boolean,
    isPinned: Boolean,
    anchorPosition: IntOffset,
    itemHeightPx: Int,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onHide: () -> Unit
) {
    if (!expanded) return

    val colors = AppTheme.colors
    val dimens = AppTheme.dimens
    val density = LocalDensity.current

    val menuBg = colors.surface
    val contentColor = colors.menuContent
    val iconColor = colors.menuIcon
    val borderColor = PinkPrimary.copy(alpha = 0.22f)

    val iconSize = dimens.menuIconSize
    val labelSize = dimens.menuTextFontSize
    val itemHorizontalPadding = 12.dp
    val itemVerticalPadding = 7.dp
    val iconTextGap = 10.dp
    val menuMinWidth = 140.dp
    val menuMaxWidth = 168.dp
    val menuShape = RoundedCornerShape(14.dp)

    // 菜单显示在 item 下方，与 item 左对齐（微信风格）
    // y 偏移 = item 顶部 + item 高度（菜单出现在 item 正下方）
    val menuYOffset = with(density) { itemHeightPx.toDp().toPx().toInt() }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(anchorPosition.x, anchorPosition.y + menuYOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = menuMinWidth, max = menuMaxWidth)
                .clip(menuShape)
                // 液态玻璃：Popup 是独立 window，LocalPageBackdrop 为 null，
                // drawGlass 退化为纯色 surfaceColor，必须传 menuBg 兜底。
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = menuShape,
                    surfaceColor = menuBg
                )
                .border(1.dp, borderColor, menuShape)
                .padding(vertical = 2.dp)
        ) {
            HomeMenuItem(
                text = if (isPinned) "取消顶置" else "顶置该聊天",
                icon = AppIcons.Star,
                contentColor = contentColor,
                iconColor = iconColor,
                iconSize = iconSize,
                labelSize = labelSize,
                horizontalPadding = itemHorizontalPadding,
                verticalPadding = itemVerticalPadding,
                iconTextGap = iconTextGap
            ) {
                onDismiss()
                onTogglePin()
            }
            HomeMenuItem(
                text = "隐藏该聊天",
                icon = AppIcons.EyeOff,
                contentColor = contentColor,
                iconColor = iconColor,
                iconSize = iconSize,
                labelSize = labelSize,
                horizontalPadding = itemHorizontalPadding,
                verticalPadding = itemVerticalPadding,
                iconTextGap = iconTextGap
            ) {
                onDismiss()
                onHide()
            }
            HomeMenuItem(
                text = "删除该聊天",
                icon = AppIcons.Trash2,
                contentColor = colors.danger,
                iconColor = colors.danger,
                iconSize = iconSize,
                labelSize = labelSize,
                horizontalPadding = itemHorizontalPadding,
                verticalPadding = itemVerticalPadding,
                iconTextGap = iconTextGap
            ) {
                onDismiss()
                onDelete()
            }
        }
    }
}

@Composable
private fun HomeMenuItem(
    text: String,
    icon: ImageVector,
    contentColor: Color,
    iconColor: Color,
    iconSize: Dp,
    labelSize: TextUnit,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    iconTextGap: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(iconTextGap))
        Text(
            text = text,
            color = contentColor,
            fontSize = labelSize,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
