package com.yunian.ai.feature.chat.ui.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.yunian.ai.uicommon.component.AppListItemLayout
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.AppBubbleSide
import com.yunian.ai.uicommon.theme.LocalChatGlassBackdrop
import com.yunian.ai.uicommon.theme.LocalChatGlassEnabled
import com.yunian.ai.uicommon.theme.appBubbleGlass
import com.yunian.ai.uicommon.theme.buildBubblePath
import androidx.compose.ui.unit.Dp
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.uicommon.theme.AppBubbleSpec
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.appBubbleBackground
import com.yunian.ai.uicommon.theme.bubbleBlurRadiusFor

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatMessageFrame(
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing,
    avatar: @Composable () -> Unit,
    timestamp: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawBubble: Boolean = true,
    isDarkTheme: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    val dimens = AppTheme.dimens
    val haptic = LocalHapticFeedback.current
    val glassBackdrop = LocalChatGlassBackdrop.current
    val glassEnabled = LocalChatGlassEnabled.current

    val bubbleColor = if (isMine) colors.primaryBubbleBackground else colors.secondaryBubbleBackground
    // 去上色的中性玻璃面：保留极淡的差异感由箭头方向与左右对齐承担
    val glassSurface = colors.secondaryBubbleBackground.copy(alpha = 0.55f)

    val bubbleArrowWidth = 5.dp
    val bubbleContentPadding = Modifier.padding(
        horizontal = adaptiveSizing.chatBubblePaddingHorizontal,
        vertical = adaptiveSizing.chatBubblePaddingVertical
    )
    // Path 工厂记忆化（T04）：只在方向 / 圆角变化时重建，避免每次组合新建 lambda（配合 core 的
    // drawWithCache 按 size 缓存，尺寸不变则不再每帧重建 Path）。
    val bubblePathFactory: (androidx.compose.ui.geometry.Size, androidx.compose.ui.unit.Density) -> androidx.compose.ui.graphics.Path =
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
                        // 档位 blur：HIGH/ULTRA 保持 20dp（零变化），MEDIUM 降级为 12dp。
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
    val gestureModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onLongClick?.invoke()
            },
            interactionSource = null,
            indication = null
        )
    } else {
        Modifier
    }

    AppListItemLayout(
        isStartAligned = !isMine,
        startSlot = {
            Box(
                modifier = Modifier.size(adaptiveSizing.avatarSize),
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
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .then(gestureModifier)
                        .then(bubbleModifier)
                ) {
                    content()
                }
            }
        }
    }
}
