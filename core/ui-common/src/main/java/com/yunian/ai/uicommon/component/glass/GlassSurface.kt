
package com.yunian.ai.uicommon.component.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

val GlassSurfaceColor: Color
    @Composable get() =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF20242B) else Color(0xF7FFFFFF)

/**
 * 玻璃表面的公共绘制内容：磨砂底 + 顶部微高光。
 *
 * [drawGlass] 与 [drawFrosted] 共用同一份实现，保证两种变体的观感完全一致，
 * 差别只在于 drawGlass 额外叠加了一层背景采样（vibrancy / blur / lens）。
 */
private fun DrawScope.drawGlassSurface(glassColor: Color) {
    // 磨砂底 + 顶部微高光：即使设备不支持 RenderEffect blur，也有明确玻璃观感
    drawRect(glassColor.copy(alpha = 0.50f))
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.14f),
            0.35f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = 0f)
        )
    )
}

/** 解析玻璃底色：调用方传色优先，其次取主题默认色，最后按明暗兜底。 */
@Composable
private fun resolveGlassColor(surfaceColor: Color?, isDark: Boolean?): Color {
    val isDarkResolved = isDark ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    return surfaceColor
        ?: runCatching { GlassSurfaceColor }.getOrNull()
        ?: if (isDarkResolved) Color(0xFF20242B) else Color(0xF7FFFFFF)
}

@Composable
fun Modifier.drawGlass(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(24.dp),
    surfaceColor: Color? = null,
    isDark: Boolean? = null,
): Modifier {
    val glassColor = resolveGlassColor(surfaceColor, isDark)

    return if (backdrop != null) {
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(24.dp.toPx())
                // 库的 lens 效果只支持 CornerBasedShape，其他形状（如 RectangleShape）会直接抛异常
                if (shape is CornerBasedShape) {
                    lens(16.dp.toPx(), 16.dp.toPx())
                }
            },
            onDrawSurface = {
                drawGlassSurface(glassColor)
            }
        )
    } else {
        this.background(glassColor, shape)
    }
}

/**
 * 轻量磨砂（无背景采样）。
 *
 * 与 [drawGlass] 的差别：**完全不调用 `drawBackdrop`**，因此不采样背景层、不做
 * 24dp 实时模糊、不做 lens 折射 —— 每帧只有 2 次纯色/渐变填充，开销与一个普通
 * `background` 相当。观感上保留 [drawGlass] 的磨砂填充与顶部白色高光，仅少了
 * 一层背景透光折射。
 *
 * 适用场景：数量随数据变化的小元素（chip / pill / 小按钮），尤其是 `forEach` 里
 * 动态生成的。这类元素单块视觉贡献极小，但每块都要重做一次模糊，数量一多（几十块）
 * 就会把滚动帧预算吃满。大面积且数量固定的面板 / 卡片 / 顶栏仍应使用 [drawGlass]。
 *
 * 注意：该变体不读取 [Backdrop]，因此在页面背景之上不会随背景变化而"透光"，
 * 这是有意的取舍——用几乎不可见的透光换取滚动时的稳定帧率。
 */
@Composable
fun Modifier.drawFrosted(
    shape: Shape = RoundedCornerShape(24.dp),
    surfaceColor: Color? = null,
    isDark: Boolean? = null,
): Modifier {
    val glassColor = resolveGlassColor(surfaceColor, isDark)

    return this.drawWithCache {
        // outline 与高光渐变都只构建一次，尺寸/布局方向变化时 drawWithCache 会自动重建
        val outline = shape.createOutline(size, layoutDirection, this)
        val topHighlight = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.14f),
            0.35f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = 0f)
        )
        onDrawBehind {
            drawOutline(outline, color = glassColor.copy(alpha = 0.50f))
            drawOutline(outline = outline, brush = topHighlight)
        }
    }
}
