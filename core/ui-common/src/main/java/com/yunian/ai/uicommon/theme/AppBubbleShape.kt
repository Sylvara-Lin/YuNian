package com.yunian.ai.uicommon.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.yunian.ai.common.HardwareInfo
import kotlin.math.min

enum class AppBubbleSide {
    Start,
    End,
    None
}

data class AppBubbleSpec(
    val cornerRadius: Dp,
    val side: AppBubbleSide,
    val arrowWidth: Dp = 6.dp,
    val arrowHeight: Dp = 10.dp,
    val arrowOffsetY: Dp = 16.dp
)

/** 气泡外形 Shape（含小箭头/尾巴），供液态玻璃 drawBackdrop 裁剪 */
internal class BubbleShape(
    private val pathFactory: (Size, Density) -> Path
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Generic(pathFactory(size, density))
    }
}

/**
 * 气泡液态玻璃背景：保留原气泡 Path 外形，玻璃面折射页面背景（真液态玻璃，无实色上色）。
 * - backdrop 为 null 或 glassEnabled=false（低端机）时回退纯色 path
 * - 不使用 lens：气泡外形是 Generic Shape，lens 只支持 CornerBasedShape（会抛异常）
 *
 * @param blurRadius 玻璃离屏模糊半径。**默认值 = 现状 20.dp**，因此既有调用点（如 feature:groupchat）
 *   不传即保持原状（向后兼容）。聊天页按性能档传入 [bubbleBlurRadiusFor] 的结果做降级。
 */
fun Modifier.appBubbleGlass(
    backdrop: Backdrop?,
    pathFactory: (Size, Density) -> Path,
    glassSurfaceColor: Color,
    fallbackColor: Color,
    borderColor: Color? = null,
    borderWidth: Dp = 0.dp,
    glassEnabled: Boolean = true,
    blurRadius: Dp = 20.dp
): Modifier {
    val shape = BubbleShape(pathFactory)
    return if (backdrop != null && glassEnabled) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
            },
            onDrawSurface = {
                drawRect(glassSurfaceColor)
                if (borderColor != null && borderWidth > 0.dp) {
                    drawPath(
                        path = pathFactory(size, this),
                        color = borderColor,
                        style = Stroke(width = borderWidth.toPx())
                    )
                }
            }
        )
    } else {
        // 回退纯色分支：用 drawWithCache 按 size 记忆化 Path（尺寸不变则不再每帧重建，R7）。
        this.drawWithCache {
            val path = pathFactory(size, this)
            onDrawBehind {
                drawPath(path, fallbackColor)
                if (borderColor != null && borderWidth > 0.dp) {
                    drawPath(
                        path = path,
                        color = borderColor,
                        style = Stroke(width = borderWidth.toPx())
                    )
                }
            }
        }
    }
}

/**
 * 纯色气泡背景（无玻璃）：同样按 size 记忆化 Path，避免每帧 `buildBubblePath` 重建（R7）。
 */
fun Modifier.appBubbleBackground(
    color: Color,
    borderColor: Color? = null,
    borderWidth: Dp = 0.dp,
    spec: AppBubbleSpec
): Modifier = drawWithCache {
    val radius = spec.cornerRadius.toPx()
    val arrowWidthPx = if (spec.side == AppBubbleSide.None) 0f else spec.arrowWidth.toPx()
    val arrowHeightPx = spec.arrowHeight.toPx()
    val rectLeft = if (spec.side == AppBubbleSide.Start) arrowWidthPx else 0f
    val rectRight = size.width - if (spec.side == AppBubbleSide.End) arrowWidthPx else 0f

    val fillPath = buildBubblePath(
        rectLeft = rectLeft,
        rectRight = rectRight,
        rectHeight = size.height,
        radius = radius,
        arrowWidthPx = arrowWidthPx,
        arrowHeightPx = arrowHeightPx,
        arrowOffsetYPx = spec.arrowOffsetY.toPx(),
        side = spec.side
    )
    onDrawBehind {
        drawPath(path = fillPath, color = color)
        if (borderColor != null && borderWidth > 0.dp) {
            drawPath(
                path = fillPath,
                color = borderColor,
                style = Stroke(width = borderWidth.toPx())
            )
        }
    }
}

/**
 * 性能档 → 气泡液态玻璃的离屏 blur 半径。
 *
 * 用户底线：**HIGH / ULTRA 视觉零变化**（保持现状 `20.dp`）；MEDIUM 适度降级为 `12.dp`；
 * LOW 走实底气泡（`LocalChatGlassEnabled=false`，此处取值不生效），仍返回 `12.dp` 以保持映射单调。
 *
 * 概念输入即原闲置死代码 `glassIntensity`（ULTRA 1.0 / HIGH 0.85 / MEDIUM 0.5 / LOW 0.2）。
 */
fun bubbleBlurRadiusFor(tier: HardwareInfo.Tier): Dp = when (tier) {
    HardwareInfo.Tier.ULTRA, HardwareInfo.Tier.HIGH -> 20.dp
    HardwareInfo.Tier.MEDIUM, HardwareInfo.Tier.LOW -> 12.dp
}

fun buildBubblePath(
    rectLeft: Float,
    rectRight: Float,
    rectHeight: Float,
    radius: Float,
    arrowWidthPx: Float,
    arrowHeightPx: Float,
    arrowOffsetYPx: Float,
    side: AppBubbleSide
): Path {
    val width = (rectRight - rectLeft).coerceAtLeast(0f)
    val height = rectHeight.coerceAtLeast(0f)
    val baseR = radius.coerceIn(0f, min(width, height) / 2f)

    if (side == AppBubbleSide.None || arrowWidthPx <= 0f || arrowHeightPx <= 0f || height <= 0f) {
        return Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset(rectLeft, 0f), Size(width, height)),
                    cornerRadius = CornerRadius(baseR, baseR)
                )
            )
        }
    }

    val halfH = arrowHeightPx / 2f

    val maxArrowSideR = ((height - arrowHeightPx) / 2f).coerceAtLeast(0f)
    val arrowSideR = min(baseR, maxArrowSideR)
    val otherR = baseR

    val minMid = arrowSideR + halfH
    val maxMid = (height - arrowSideR - halfH).coerceAtLeast(minMid)
    val arrowMidY = arrowOffsetYPx.coerceIn(minMid, maxMid)
    val arrowTop = arrowMidY - halfH
    val arrowBottom = arrowMidY + halfH

    val tl: Float
    val tr: Float
    val br: Float
    val bl: Float
    when (side) {
        AppBubbleSide.Start -> {
            tl = arrowSideR
            bl = arrowSideR
            tr = otherR
            br = otherR
        }
        AppBubbleSide.End -> {
            tr = arrowSideR
            br = arrowSideR
            tl = otherR
            bl = otherR
        }
        AppBubbleSide.None -> {
            tl = otherR
            tr = otherR
            br = otherR
            bl = otherR
        }
    }

    return Path().apply {
        when (side) {
            AppBubbleSide.Start -> {

                moveTo(0f, arrowMidY)
                lineTo(rectLeft, arrowTop)
                lineTo(rectLeft, tl)
                if (tl > 0f) {
                    arcTo(Rect(rectLeft, 0f, rectLeft + tl * 2f, tl * 2f), 180f, 90f, false)
                } else {
                    lineTo(rectLeft, 0f)
                }
                lineTo(rectRight - tr, 0f)
                if (tr > 0f) {
                    arcTo(Rect(rectRight - tr * 2f, 0f, rectRight, tr * 2f), 270f, 90f, false)
                } else {
                    lineTo(rectRight, 0f)
                }
                lineTo(rectRight, height - br)
                if (br > 0f) {
                    arcTo(Rect(rectRight - br * 2f, height - br * 2f, rectRight, height), 0f, 90f, false)
                } else {
                    lineTo(rectRight, height)
                }
                lineTo(rectLeft + bl, height)
                if (bl > 0f) {
                    arcTo(Rect(rectLeft, height - bl * 2f, rectLeft + bl * 2f, height), 90f, 90f, false)
                } else {
                    lineTo(rectLeft, height)
                }
                lineTo(rectLeft, arrowBottom)
                close()
            }
            AppBubbleSide.End -> {

                moveTo(rectRight + arrowWidthPx, arrowMidY)
                lineTo(rectRight, arrowTop)
                lineTo(rectRight, tr)
                if (tr > 0f) {
                    arcTo(Rect(rectRight - tr * 2f, 0f, rectRight, tr * 2f), 0f, -90f, false)
                } else {
                    lineTo(rectRight, 0f)
                }
                lineTo(rectLeft + tl, 0f)
                if (tl > 0f) {
                    arcTo(Rect(rectLeft, 0f, rectLeft + tl * 2f, tl * 2f), 270f, -90f, false)
                } else {
                    lineTo(rectLeft, 0f)
                }
                lineTo(rectLeft, height - bl)
                if (bl > 0f) {
                    arcTo(Rect(rectLeft, height - bl * 2f, rectLeft + bl * 2f, height), 180f, -90f, false)
                } else {
                    lineTo(rectLeft, height)
                }
                lineTo(rectRight - br, height)
                if (br > 0f) {
                    arcTo(Rect(rectRight - br * 2f, height - br * 2f, rectRight, height), 90f, -90f, false)
                } else {
                    lineTo(rectRight, height)
                }
                lineTo(rectRight, arrowBottom)
                close()
            }
            AppBubbleSide.None -> Unit
        }
    }
}

/** 聊天气泡液态玻璃的 backdrop 与开关（由聊天页在 ProvidePageBackdrop 层提供） */
val LocalChatGlassBackdrop = androidx.compose.runtime.staticCompositionLocalOf<com.kyant.backdrop.Backdrop?> { null }
val LocalChatGlassEnabled = androidx.compose.runtime.staticCompositionLocalOf { false }
