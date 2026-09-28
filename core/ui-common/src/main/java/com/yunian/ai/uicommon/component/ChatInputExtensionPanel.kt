package com.yunian.ai.uicommon.component
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.model.ApiProviderInfo
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.rememberAdaptiveSizing

private const val EXTENSION_COLUMNS = 4
private const val EXTENSION_ROWS = 2
private const val EXTENSION_PAGE_CAPACITY = EXTENSION_COLUMNS * EXTENSION_ROWS

@Composable
fun ChatInputExtensionPanel(
    isVisible: Boolean,
    availableApis: List<ApiProviderInfo> = emptyList(),
    currentApi: ApiProviderInfo? = null,
    onSwitchApi: ((ApiProviderInfo) -> Unit)? = null,
    onAlbumClick: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    onVoiceCallClick: (() -> Unit)? = null,
    onTtsModeClick: (() -> Unit)? = null,
    onLocationClick: () -> Unit = {},
    onVoiceInputClick: () -> Unit = {},
    onStickerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
        ) + fadeIn(animationSpec = tween(180)) +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f)
            ),
        exit = shrinkVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)
        ) + fadeOut(animationSpec = tween(140)),
        modifier = modifier
    ) {
        val adaptiveSizing = rememberAdaptiveSizing()
        val glassColor = MaterialTheme.colorScheme.surfaceVariant
        val textColor = MaterialTheme.colorScheme.onSurfaceVariant
        val iconTintColor = MaterialTheme.colorScheme.onSurface
        val indicatorActive = MaterialTheme.colorScheme.primary
        val indicatorInactive = MaterialTheme.colorScheme.outlineVariant

        val items = remember(
            availableApis,
            currentApi,
            onSwitchApi,
            onAlbumClick,
            onCameraClick,
            onVideoCallClick,
            onVoiceCallClick,
            onTtsModeClick,
            onLocationClick,
            onStickerClick,
            onVoiceInputClick
        ) {
            buildExtensionItems(
                availableApis = availableApis,
                currentApi = currentApi,
                onSwitchApi = onSwitchApi,
                onAlbumClick = onAlbumClick,
                onCameraClick = onCameraClick,
                onVideoCallClick = onVideoCallClick,
                onVoiceCallClick = onVoiceCallClick,
                onTtsModeClick = onTtsModeClick,
                onLocationClick = onLocationClick,
                onStickerClick = onStickerClick,
                onVoiceInputClick = onVoiceInputClick
            )
        }
        val pages = remember(items) { items.chunked(EXTENSION_PAGE_CAPACITY).ifEmpty { listOf(emptyList()) } }
        val pagerState = rememberPagerState(pageCount = { pages.size })

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    // 库的 lens 只支持 CornerBasedShape；面板贴底，只做顶部圆角
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    surfaceColor = glassColor
                )
                .padding(top = 10.dp, bottom = 8.dp)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),

                contentPadding = PaddingValues(horizontal = 8.dp),
                pageSpacing = 0.dp,

                verticalAlignment = Alignment.Top
            ) { page ->
                ExtensionSlotGrid(
                    pageItems = pages[page],
                    textColor = textColor,
                    iconTintColor = iconTintColor,
                    adaptiveSizing = adaptiveSizing
                )
            }

            if (pages.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pages.size) { index ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (selected) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(if (selected) indicatorActive else indicatorInactive)
                        )
                    }
                }
            }
        }
    }
}

private fun buildExtensionItems(
    availableApis: List<ApiProviderInfo>,
    currentApi: ApiProviderInfo?,
    onSwitchApi: ((ApiProviderInfo) -> Unit)?,
    onAlbumClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onVoiceCallClick: (() -> Unit)?,
    onTtsModeClick: (() -> Unit)?,
    onLocationClick: () -> Unit,
    onStickerClick: () -> Unit,
    onVoiceInputClick: () -> Unit
): List<ExtensionItem> {
    val hasMultipleApis = availableApis.size > 1 && onSwitchApi != null
    return buildList {
        add(ExtensionItem("相册", AppIcons.Image, onAlbumClick))
        add(ExtensionItem("拍摄", AppIcons.Camera, onCameraClick))
        add(ExtensionItem("视频通话", AppIcons.Video, onVideoCallClick))
        onVoiceCallClick?.let { add(ExtensionItem("语音通话", AppIcons.Phone, it)) }
        onTtsModeClick?.let { add(ExtensionItem("朗读模式", AppIcons.Volume2, it)) }
        add(ExtensionItem("位置", AppIcons.MapPin, onLocationClick))
        add(ExtensionItem("表情包", AppIcons.Smile, onStickerClick))
        add(ExtensionItem("语音输入", AppIcons.Mic, onVoiceInputClick))
        if (hasMultipleApis) {
            add(
                ExtensionItem("切换模型", AppIcons.ArrowLeftRight) {
                    val next = currentApi?.let { c ->
                        val idx = availableApis.indexOfFirst { it.name == c.name }
                        availableApis.getOrNull((idx + 1) % availableApis.size)
                    } ?: availableApis.first()
                    onSwitchApi!!(next)
                }
            )
        }
    }
}

@Composable
private fun ExtensionSlotGrid(
    pageItems: List<ExtensionItem>,
    textColor: Color,
    iconTintColor: Color,
    adaptiveSizing: AdaptiveSizing
) {
    val slots: List<ExtensionItem?> = List(EXTENSION_PAGE_CAPACITY) { index ->
        pageItems.getOrNull(index)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(adaptiveSizing.extensionGridSpacing)
    ) {
        repeat(EXTENSION_ROWS) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),

                horizontalArrangement = Arrangement.Start
            ) {
                repeat(EXTENSION_COLUMNS) { col ->
                    val slotIndex = row * EXTENSION_COLUMNS + col
                    val item = slots[slotIndex]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),

                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (item != null) {
                            ExtensionIconButton(
                                label = item.label,
                                icon = item.icon,
                                textColor = textColor,
                                iconTintColor = iconTintColor,
                                onClick = item.onClick,
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
private fun ExtensionIconButton(
    label: String,
    icon: ImageVector,
    textColor: Color,
    iconTintColor: Color,
    onClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "extensionIconPress"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {

        Box(
            modifier = Modifier
                .size(adaptiveSizing.extensionIconBoxSize)
                .scale(pressScale)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(adaptiveSizing.extensionIconSize),
                tint = iconTintColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = adaptiveSizing.fontSizeSmall.sp,
            color = textColor,
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )
    }
}

private data class ExtensionItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)
