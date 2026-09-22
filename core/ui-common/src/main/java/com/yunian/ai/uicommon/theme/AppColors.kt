package com.yunian.ai.uicommon.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

object AppColors {

    fun primaryBubbleBackground(colorScheme: ColorScheme): Color =
        if (isDarkSurface(colorScheme)) SelfBubbleDark else SelfBubbleLight
    fun primaryBubbleContent(colorScheme: ColorScheme): Color = BubbleOnPink
    fun secondaryBubbleBackground(colorScheme: ColorScheme): Color =
        if (isDarkSurface(colorScheme)) AiBubbleDark else AiBubbleLight
    fun secondaryBubbleContent(colorScheme: ColorScheme): Color = BubbleOnPink
    fun secondaryBubbleBorder(colorScheme: ColorScheme): Color =
        if (isDarkSurface(colorScheme)) AiBubbleBorderDark else AiBubbleBorderLight

    private fun isDarkSurface(colorScheme: ColorScheme): Boolean =
        colorScheme.surface.luminance() < 0.5f

    fun metadataContent(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant

    fun menuBackground(colorScheme: ColorScheme): Color = colorScheme.surface
    fun menuContent(colorScheme: ColorScheme): Color = colorScheme.onSurface
    fun menuIcon(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant

    fun quoteAuthor(colorScheme: ColorScheme, isPrimary: Boolean): Color =
        if (isPrimary) colorScheme.onPrimaryContainer else colorScheme.primary

    fun quotePreview(colorScheme: ColorScheme, isPrimary: Boolean): Color =
        if (isPrimary) colorScheme.onPrimaryContainer.copy(alpha = 0.82f) else colorScheme.onSurfaceVariant

    fun quoteBackground(colorScheme: ColorScheme, isPrimary: Boolean): Color =
        if (isPrimary) colorScheme.onPrimaryContainer.copy(alpha = 0.12f) else colorScheme.surfaceVariant.copy(alpha = 0.55f)

    fun dividerBackground(colorScheme: ColorScheme): Color = colorScheme.surfaceVariant.copy(alpha = 0.7f)
    fun captionContent(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
}
