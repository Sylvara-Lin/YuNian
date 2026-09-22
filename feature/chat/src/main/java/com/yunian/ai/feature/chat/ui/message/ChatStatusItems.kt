package com.yunian.ai.feature.chat.ui.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.feature.chat.ui.viewmodel.ReasoningUiProjector
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.uicommon.component.CompanionAvatar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.WeChatDarkCard
import kotlinx.coroutines.delay

@Composable
fun TypingIndicatorItem(
    companionData: CompanionModel?,
    typingText: String,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val displayText = typingText.trim()

    if (displayText.isEmpty()) return

    ChatMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        isDarkTheme = isDarkTheme,
        modifier = modifier,
        avatar = {
            CompanionAvatar(
                avatarUrl = companionData?.avatarUrl,
                name = companionData?.name,
                size = adaptiveSizing.avatarSize
            )
        },
        timestamp = {}
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = displayText,
                style = typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp, lineHeight = 20.sp),
                color = colors.secondaryBubbleContent
            )
        }
    }
}

/**
 * 生图等待动画（液态玻璃）。
 *
 * 生图通常要 10–60s，一闪而过的 toast 起不到提示作用，所以做成一条常驻的玻璃气泡：
 * 玻璃底 + 高光扫过 + 呼吸圆点 + 已等待秒数，图片落地后由上层移除。
 */
@Composable
fun ImageGenGeneratingItem(
    companionData: CompanionModel?,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val shape = RoundedCornerShape(percent = 50)

    val infiniteTransition = rememberInfiniteTransition(label = "image_gen_glass")
    // 高光扫过：-1 → 2 表示光带从左侧完全移出到右侧完全移出
    val sweep by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
        label = "sweep"
    )
    val dotAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "gen_dot1"
    )
    val dotAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, 180), RepeatMode.Reverse), label = "gen_dot2"
    )
    val dotAlpha3 by infiniteTransition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, 360), RepeatMode.Reverse), label = "gen_dot3"
    )

    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            delay(1000L)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 48.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        CompanionAvatar(
            avatarUrl = companionData?.avatarUrl,
            name = companionData?.name,
            size = adaptiveSizing.avatarSize
        )
        Spacer(modifier = Modifier.width(8.dp))

        Row(
            modifier = Modifier
                .clip(shape)
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = shape,
                    surfaceColor = if (isDarkTheme) {
                        WeChatDarkCard
                    } else {
                        colors.surface.copy(alpha = 0.72f)
                    }
                )
                .drawWithContent {
                    drawContent()
                    // 液态玻璃质感：一条柔和高光斜向扫过
                    val bandWidth = size.width * 0.42f
                    val startX = sweep * (size.width + bandWidth) - bandWidth
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (isDarkTheme) 0.14f else 0.42f),
                                Color.Transparent
                            ),
                            start = Offset(startX, 0f),
                            end = Offset(startX + bandWidth, size.height)
                        )
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Sparkles,
                contentDescription = null,
                tint = PetalPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "正在生成配图",
                style = typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp),
                color = colors.secondaryBubbleContent
            )
            Spacer(modifier = Modifier.width(6.dp))
            listOf(dotAlpha1, dotAlpha2, dotAlpha3).forEachIndexed { index, alpha ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(PetalPrimary)
                )
                if (index < 2) Spacer(modifier = Modifier.width(3.dp))
            }
            if (elapsedSeconds > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${elapsedSeconds}s",
                    style = typography.bodyLarge.copy(fontSize = (adaptiveSizing.fontSizeBody - 3).coerceAtLeast(10).sp),
                    color = colors.metadataContent
                )
            }
        }
    }
}

@Composable
fun RegeneratingItem(
    companionData: CompanionModel?,
    adaptiveSizing: AdaptiveSizing
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val infiniteTransition = rememberInfiniteTransition(label = "regenerate_dots")
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

    ChatMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        avatar = {
            CompanionAvatar(
                avatarUrl = companionData?.avatarUrl,
                name = companionData?.name,
                size = adaptiveSizing.avatarSize
            )
        },
        timestamp = {}
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "正在重新生成",
                style = typography.bodyLarge.copy(fontSize = 14.sp),
                color = colors.secondaryBubbleContent
            )
            Spacer(modifier = Modifier.width(4.dp))
            repeat(3) { index ->
                val alpha = listOf(dot1Alpha, dot2Alpha, dot3Alpha)[index]
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(colors.metadataContent)
                )
                if (index < 2) {
                    Spacer(modifier = Modifier.width(3.dp))
                }
            }
        }
    }
}

@Composable
fun ReasoningItem(
    reasoningText: String,
    adaptiveSizing: AdaptiveSizing,
    companionData: CompanionModel? = null,
    userAvatar: String? = null,
    userName: String = "",
    autoCollapse: Boolean = true,
    isStreaming: Boolean = false,
    durationMs: Long? = null,
) {

    var expanded by remember(reasoningText, autoCollapse, isStreaming, durationMs) {
        mutableStateOf(if (isStreaming) true else !autoCollapse)
    }
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val collapsed = remember(reasoningText, durationMs) {
        ReasoningUiProjector.collapsedLabel(durationMs = durationMs, text = reasoningText)
    }
    val streaming = ReasoningUiProjector.streamingLabel()

    ChatMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        avatar = {

            ChatMessageAvatar(
                isMine = false,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                adaptiveSizing = adaptiveSizing,
            )
        },
        timestamp = {}
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded }
            ) {
                Text(
                    text = when {
                        isStreaming && expanded -> "$streaming ▼"
                        isStreaming -> "$streaming ▶"
                        expanded -> "$collapsed ▼"
                        else -> "$collapsed ▶"
                    },
                    style = typography.labelSmall.copy(fontSize = 12.sp),
                    color = colors.metadataContent
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = reasoningText,
                    style = typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.metadataContent
                )
            }
        }
    }
}
