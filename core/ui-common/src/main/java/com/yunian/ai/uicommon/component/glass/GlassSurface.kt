
package com.yunian.ai.uicommon.component.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

@Composable
fun Modifier.drawGlass(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(24.dp),
    surfaceColor: Color? = null,
    isDark: Boolean? = null,
): Modifier {
    val isDarkResolved = isDark ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    val glassColor = surfaceColor
        ?: runCatching { GlassSurfaceColor }.getOrNull()
        ?: if (isDarkResolved) Color(0xFF20242B) else Color(0xF7FFFFFF)

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
        )
    } else {
        this.background(glassColor, shape)
    }
}
