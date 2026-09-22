package com.yunian.ai.uicommon.component

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.yunian.ai.uicommon.theme.GlassDarkBg
import com.yunian.ai.uicommon.theme.GlassLightBg
import com.yunian.ai.uicommon.theme.PinkPrimary

fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    isDark: Boolean = false,
    intensity: Float = 1.0f
): Modifier {
    return this
        .clip(shape)
        .glassBackground(isDark, intensity)
        .glassBorder(isDark, shape, intensity)
        .glassEdgeHighlight(isDark, intensity)
        .glassCaustics(isDark, intensity)
}

fun Modifier.ellipseLiquidGlass(
    isExpanded: Boolean = false,
    isDark: Boolean = false,
    intensity: Float = 1.0f
): Modifier {
    val shape = if (isExpanded) {
        RoundedCornerShape(16.dp)
    } else {
        RoundedCornerShape(50)
    }
    return this.liquidGlass(shape = shape, isDark = isDark, intensity = intensity)
}

fun Modifier.frostedGlassNav(
    isDark: Boolean,
    intensity: Float = 1.0f
): Modifier {
    val shape = RoundedCornerShape(26.dp)
    return this
        .clip(shape)
        .navGlassBackground(isDark, intensity)
        .glassBorder(isDark, shape, intensity)
        .glassEdgeHighlight(isDark, intensity)
        .glassCaustics(isDark, intensity)
}

fun Modifier.liquidGlassPill(
    shape: Shape = RoundedCornerShape(32.dp),
    isDark: Boolean = false,
    intensity: Float = 1.0f
): Modifier {
    return this
        .clip(shape)
        .pillGlassBackground(isDark, intensity)
        .pillSpecularHighlight(isDark, intensity)
        .pillFresnelEdge(isDark, intensity)
        .glassBorder(isDark, shape, intensity)
}

fun Modifier.glassEdgeLight(isDark: Boolean, intensity: Float = 1.0f): Modifier {
    return this.drawBehind {
        val i = intensity.coerceIn(0f, 1.5f)
        val width = size.width
        val height = size.height
        val corner = CornerRadius(height * 0.48f)
        drawRoundRect(
            color = if (isDark) PinkPrimary.copy(alpha = 0.045f * i)
            else PinkPrimary.copy(alpha = 0.09f * i),
            topLeft = Offset(0f, 0f),
            size = Size(width, height),
            cornerRadius = corner,
            style = Stroke(width = height * 0.22f)
        )
        drawRoundRect(
            color = if (isDark) PinkPrimary.copy(alpha = 0.11f * i)
            else PinkPrimary.copy(alpha = 0.20f * i),
            topLeft = Offset(0f, 0f),
            size = Size(width, height),
            cornerRadius = corner,
            style = Stroke(width = height * 0.09f)
        )
    }
}

private fun Modifier.glassBackground(isDark: Boolean, intensity: Float): Modifier {
    return this.drawBehind {
        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)
        val baseColor = if (isDark) GlassDarkBg else GlassLightBg

        drawRect(
            color = baseColor.copy(alpha = if (isDark) 0.45f * i else 0.15f * i)
        )

        val primaryHighlight = Brush.verticalGradient(
            colors = listOf(
                PinkPrimary.copy(alpha = if (isDark) 0.08f * i else 0.12f * i),
                PinkPrimary.copy(alpha = if (isDark) 0.04f * i else 0.06f * i),
                Color.Transparent
            ),
            startY = 0f,
            endY = height * 0.50f
        )
        drawRect(brush = primaryHighlight)

        val diagonalHighlight = Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.12f * i else 0.20f * i),
                Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.05f * i else 0.08f * i),
                Color.Transparent
            ),
            start = Offset(0f, 0f),
            end = Offset(width * 0.55f, height * 0.45f)
        )
        drawRect(brush = diagonalHighlight)

        val bottomVolume = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xFF000000).copy(alpha = if (isDark) 0.25f * i else 0.06f * i),
                Color(0xFF000000).copy(alpha = if (isDark) 0.40f * i else 0.12f * i)
            ),
            startY = height * 0.45f,
            endY = height
        )
        drawRect(brush = bottomVolume)
    }
}

private fun Modifier.navGlassBackground(isDark: Boolean, intensity: Float): Modifier {
    return this.drawBehind {
        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)
        val baseColor = if (isDark) GlassDarkBg else GlassLightBg

        drawRect(
            color = baseColor.copy(alpha = if (isDark) 0.55f * i else 0.35f * i)
        )

        val topGloss = Brush.verticalGradient(
            colors = listOf(
                PinkPrimary.copy(alpha = if (isDark) 0.10f * i else 0.15f * i),
                PinkPrimary.copy(alpha = if (isDark) 0.04f * i else 0.06f * i),
                Color.Transparent
            ),
            startY = 0f,
            endY = height * 0.55f
        )
        drawRect(brush = topGloss)

        val bottomShadow = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xFF000000).copy(alpha = if (isDark) 0.30f * i else 0.10f * i),
                Color(0xFF000000).copy(alpha = if (isDark) 0.45f * i else 0.16f * i)
            ),
            startY = height * 0.40f,
            endY = height
        )
        drawRect(brush = bottomShadow)
    }
}

private fun Modifier.pillGlassBackground(isDark: Boolean, intensity: Float): Modifier {
    return this.drawBehind {
        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)

        drawRect(
            color = if (isDark) GlassDarkBg.copy(alpha = 0.26f * i)
            else GlassLightBg.copy(alpha = 0.16f * i)
        )

        val topSheen = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.14f * i else 0.20f * i),
                Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.04f * i else 0.06f * i),
                Color.Transparent
            ),
            startY = 0f,
            endY = height * 0.30f
        )
        drawRect(brush = topSheen)

        val bottomVolume = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xFF000000).copy(alpha = if (isDark) 0.12f * i else 0.04f * i),
                Color(0xFF000000).copy(alpha = if (isDark) 0.24f * i else 0.09f * i)
            ),
            startY = height * 0.58f,
            endY = height
        )
        drawRect(brush = bottomVolume)
    }
}

private fun Modifier.pillSpecularHighlight(isDark: Boolean, intensity: Float): Modifier {
    return this.drawWithContent {
        drawContent()

        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)

        val specular = Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.34f * i else 0.46f * i),
                Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.14f * i else 0.20f * i),
                Color.Transparent
            ),
            center = Offset(width * 0.5f, height * 0.015f),
            radius = width * 0.46f
        )
        drawOval(
            brush = specular,
            topLeft = Offset(width * 0.13f, -height * 0.02f),
            size = Size(width * 0.74f, height * 0.36f)
        )

        drawLine(
            color = Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.22f * i else 0.32f * i),
            start = Offset(width * 0.18f, height * 0.035f),
            end = Offset(width * 0.82f, height * 0.035f),
            strokeWidth = 1.1f
        )
    }
}

private fun Modifier.pillFresnelEdge(isDark: Boolean, intensity: Float): Modifier {
    return this.drawWithContent {
        drawContent()

        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)
        val corner = CornerRadius(height * 0.48f)

        val innerLight = Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.12f * i else 0.20f * i)
        val innerLightSoft = Color(0xFFFFFFFF).copy(alpha = if (isDark) 0.05f * i else 0.08f * i)

        drawRoundRect(
            color = innerLight,
            topLeft = Offset(0f, 0f),
            size = Size(width, height),
            cornerRadius = corner,
            style = Stroke(width = height * 0.035f)
        )

        drawRoundRect(
            color = innerLightSoft,
            topLeft = Offset(0f, 0f),
            size = Size(width, height),
            cornerRadius = corner,
            style = Stroke(width = height * 0.09f)
        )
    }
}

private fun Modifier.glassBorder(isDark: Boolean, shape: Shape, intensity: Float): Modifier {
    val i = intensity.coerceIn(0f, 1.5f)
    return this.border(
        width = 0.8.dp,
        color = if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.10f * i)
        else Color(0xFF000000).copy(alpha = 0.06f * i),
        shape = shape
    )
}

private fun Modifier.glassEdgeHighlight(isDark: Boolean, intensity: Float): Modifier {
    return this.drawWithContent {
        drawContent()

        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)

        val topEdgeDark = if (isDark) Color(0xFF000000).copy(alpha = 0.45f * i)
        else Color(0xFF000000).copy(alpha = 0.18f * i)

        drawLine(
            color = topEdgeDark,
            start = Offset(24f, 1.2f),
            end = Offset(width - 24f, 1.2f),
            strokeWidth = 1.8f
        )

        val topHighlight = if (isDark) PinkPrimary.copy(alpha = 0.20f * i)
        else PinkPrimary.copy(alpha = 0.35f * i)

        drawLine(
            color = topHighlight,
            start = Offset(24f, 0.5f),
            end = Offset(width - 24f, 0.5f),
            strokeWidth = 0.8f
        )

        val topHighlight2 = if (isDark) Color(0xFFFFFFFF).copy(alpha = 0.10f * i)
        else Color(0xFFFFFFFF).copy(alpha = 0.15f * i)

        drawLine(
            color = topHighlight2,
            start = Offset(30f, 0.15f),
            end = Offset(width - 30f, 0.15f),
            strokeWidth = 0.4f
        )

        val sideEdgeDark = if (isDark) Color(0xFF000000).copy(alpha = 0.35f * i)
        else Color(0xFF000000).copy(alpha = 0.12f * i)

        drawLine(
            color = sideEdgeDark,
            start = Offset(1.2f, 22f),
            end = Offset(1.2f, height - 22f),
            strokeWidth = 1.5f
        )

        drawLine(
            color = sideEdgeDark,
            start = Offset(width - 1.2f, 22f),
            end = Offset(width - 1.2f, height - 22f),
            strokeWidth = 1.5f
        )

        drawArc(
            color = topEdgeDark,
            startAngle = 180f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(44f, 44f),
            style = Stroke(width = 1.8f)
        )

        drawArc(
            color = topHighlight,
            startAngle = 180f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(44f, 44f),
            style = Stroke(width = 0.8f)
        )

        drawArc(
            color = topEdgeDark,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(width - 44f, 0f),
            size = androidx.compose.ui.geometry.Size(44f, 44f),
            style = Stroke(width = 1.8f)
        )

        drawArc(
            color = topHighlight,
            startAngle = 270f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(width - 44f, 0f),
            size = androidx.compose.ui.geometry.Size(44f, 44f),
            style = Stroke(width = 0.8f)
        )
    }
}

private fun Modifier.glassCaustics(isDark: Boolean, intensity: Float): Modifier {
    return this.drawWithContent {
        drawContent()

        val width = size.width
        val height = size.height
        val i = intensity.coerceIn(0f, 1.5f)

        val causticAlpha = if (isDark) 0.08f * i else 0.12f * i
        val causticBrush = Brush.radialGradient(
            colors = listOf(
                PinkPrimary.copy(alpha = causticAlpha),
                PinkPrimary.copy(alpha = causticAlpha * 0.5f),
                Color.Transparent
            ),
            center = Offset(width * 0.5f, height * 0.08f),
            radius = width * 0.35f
        )
        drawRect(brush = causticBrush)

        val cornerCaustic = Brush.radialGradient(
            colors = listOf(
                PinkPrimary.copy(alpha = causticAlpha * 0.6f),
                Color.Transparent
            ),
            center = Offset(width * 0.08f, height * 0.12f),
            radius = width * 0.15f
        )
        drawRect(brush = cornerCaustic)

        val cornerCaustic2 = Brush.radialGradient(
            colors = listOf(
                PinkPrimary.copy(alpha = causticAlpha * 0.6f),
                Color.Transparent
            ),
            center = Offset(width * 0.92f, height * 0.12f),
            radius = width * 0.15f
        )
        drawRect(brush = cornerCaustic2)
    }
}
