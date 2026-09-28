
package com.yunian.ai.uicommon.component.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** Android / Material 无障碍规范要求的最小触控目标边长。 */
private val MinTouchTargetSize = 48.dp

/** 禁用态整体不透明度：玻璃层与内容一起变淡，不做其他绘制改动。 */
private const val DisabledAlpha = 0.45f

@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop? = LocalPageBackdrop.current,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    height: Dp = 48.dp,
    horizontalPadding: Dp = 16.dp,
    minTouchTarget: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }

    val glassModifier = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { ContinuousCapsule },
            effects = {
                vibrancy()
                blur(2.dp.toPx())
                lens(12.dp.toPx(), 24.dp.toPx())
            },
            layerBlock = if (isInteractive) {
                {
                    val progress = interactiveHighlight.pressProgress
                    val scale = lerp(1f, 1f + 4.dp.toPx() / size.height, progress)

                    val maxOffset = size.minDimension
                    val initialDerivative = 0.05f
                    val offset = interactiveHighlight.offset
                    translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                    translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                    val maxDragScale = 4.dp.toPx() / size.height
                    val offsetAngle = atan2(offset.y, offset.x)
                    scaleX =
                        scale +
                            maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                            (size.width / size.height).fastCoerceAtMost(1f)
                    scaleY =
                        scale +
                            maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                            (size.height / size.width).fastCoerceAtMost(1f)
                }
            } else {
                null
            },
            onDrawSurface = {
                if (tint.isSpecified) {
                    drawRect(tint, blendMode = BlendMode.Hue)
                    drawRect(tint.copy(alpha = 0.75f))
                }
                if (surfaceColor.isSpecified) {
                    drawRect(surfaceColor)
                }
            }
        )
    } else {
        val finalColor = when {
            tint.isSpecified && surfaceColor.isSpecified -> surfaceColor
            surfaceColor.isSpecified -> surfaceColor
            tint.isSpecified -> tint.copy(alpha = 0.15f)
            else -> Color.Unspecified
        }
        if (finalColor.isSpecified) Modifier.background(finalColor, ContinuousCapsule) else Modifier
    }

    // 禁用态：整体降低不透明度。
    // enabled = true 时返回空 Modifier，因此 modifier 链与改动前完全一致（逐像素不变）。
    val disabledAlphaModifier = if (enabled) Modifier else Modifier.alpha(DisabledAlpha)

    val clickModifier = if (onLongClick == null) {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = null,
            indication = if (isInteractive) null else LocalIndication.current,
            role = Role.Button,
            onClick = onClick
        )
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            interactionSource = null,
            indication = if (isInteractive) null else LocalIndication.current,
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }

    // 按压高光只在「可交互且已启用」时挂载：禁用按钮不应再有按压动效，否则会被误认为可点击。
    val isHighlightActive = isInteractive && enabled
    val highlightModifier = if (isHighlightActive) {
        interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
    } else {
        Modifier
    }

    if (minTouchTarget) {
        // 触控容器与绘制容器分离：
        // 外层 Box 负责 ≥48dp 的触控 / 无障碍尺寸并承接点击（含胶囊左右的 horizontalPadding 区域），
        // 内层 Row 只负责绘制玻璃，视觉高度仍是传入的 height，不会被撑大。
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = MinTouchTargetSize, minHeight = MinTouchTargetSize)
                .then(clickModifier),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier
                    .then(disabledAlphaModifier)
                    .then(glassModifier)
                    .then(highlightModifier)
                    .height(height)
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    } else {
        Row(
            modifier
                .then(disabledAlphaModifier)
                .then(glassModifier)
                .then(clickModifier)
                .then(highlightModifier)
                .height(height)
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
