package com.yunian.ai.uicommon.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

@Immutable
data class AppThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val primaryContainer: Color,
    val tertiaryContainer: Color,
    val error: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
    val onTertiaryContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val inverseContent: Color,
    val staticWhite: Color,
    val staticBlack: Color,
    val scrim: Color,
    val primaryBubbleBackground: Color,
    val primaryBubbleContent: Color,
    val secondaryBubbleBackground: Color,
    val secondaryBubbleContent: Color,
    val secondaryBubbleBorder: Color,
    val metadataContent: Color,
    val menuBackground: Color,
    val menuContent: Color,
    val menuIcon: Color,
    val quotePrimaryBackground: Color,
    val quoteSecondaryBackground: Color,
    val quotePrimaryAuthor: Color,
    val quoteSecondaryAuthor: Color,
    val quotePrimaryPreview: Color,
    val quoteSecondaryPreview: Color,
    val dividerBackground: Color,
    val captionContent: Color
)

@Immutable
data class AppThemeDimens(
    val avatarGap: Dp = AppDimens.AvatarGap,
    val bubbleTimestampGap: Dp = AppDimens.BubbleTimestampGap,
    val bubbleBorderWidth: Dp = AppDimens.BubbleBorderWidth,
    val textBubbleMaxWidth: Dp = AppDimens.TextBubbleMaxWidth,
    val quoteCornerRadius: Dp = AppDimens.QuoteCornerRadius,
    val quoteHorizontalPadding: Dp = AppDimens.QuoteHorizontalPadding,
    val quoteVerticalPadding: Dp = AppDimens.QuoteVerticalPadding,
    val quoteBottomGap: Dp = AppDimens.QuoteBottomGap,
    val quoteAuthorFontSize: TextUnit = AppDimens.QuoteAuthorFontSize,
    val quotePreviewFontSize: TextUnit = AppDimens.QuotePreviewFontSize,
    val imageMaxWidth: Dp = AppDimens.ImageMaxWidth,
    val imageMaxHeight: Dp = AppDimens.ImageMaxHeight,
    val imageCornerRadius: Dp = AppDimens.ImageCornerRadius,
    val imagePlaceholderMaxWidth: Dp = AppDimens.ImagePlaceholderMaxWidth,
    val imagePlaceholderHorizontalPadding: Dp = AppDimens.ImagePlaceholderHorizontalPadding,
    val imagePlaceholderVerticalPadding: Dp = AppDimens.ImagePlaceholderVerticalPadding,
    val attachmentMaxWidth: Dp = AppDimens.AttachmentMaxWidth,
    val attachmentHorizontalPadding: Dp = AppDimens.AttachmentHorizontalPadding,
    val attachmentVerticalPadding: Dp = AppDimens.AttachmentVerticalPadding,
    val attachmentIconSize: Dp = AppDimens.AttachmentIconSize,
    val attachmentIconTextGap: Dp = AppDimens.AttachmentIconTextGap,
    val menuIconSize: Dp = AppDimens.MenuIconSize,
    val menuTextFontSize: TextUnit = AppDimens.MenuTextFontSize,
    val timeDividerVerticalPadding: Dp = AppDimens.TimeDividerVerticalPadding,
    val timeDividerCornerRadius: Dp = AppDimens.TimeDividerCornerRadius,
    val timeDividerHorizontalPadding: Dp = AppDimens.TimeDividerHorizontalPadding,
    val timeDividerInnerVerticalPadding: Dp = AppDimens.TimeDividerInnerVerticalPadding,
    val timeDividerFontSize: TextUnit = AppDimens.TimeDividerFontSize,
    val systemTipHorizontalPadding: Dp = AppDimens.SystemTipHorizontalPadding,
    val systemTipVerticalPadding: Dp = AppDimens.SystemTipVerticalPadding,
    val systemTipFontSize: TextUnit = AppDimens.SystemTipFontSize
)

private val LocalAppThemeColors = compositionLocalOf<AppThemeColors?> { null }
private val LocalAppThemeDimens = compositionLocalOf { AppThemeDimens() }

object AppTheme {
    val colors: AppThemeColors
        @Composable get() = LocalAppThemeColors.current ?: materialAppThemeColors()

    val dimens: AppThemeDimens
        @Composable get() = LocalAppThemeDimens.current

    val typography: Typography
        @Composable get() = MaterialTheme.typography
}

@Composable
fun AppTheme(
    colors: AppThemeColors = materialAppThemeColors(),
    dimens: AppThemeDimens = AppThemeDimens(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAppThemeColors provides colors,
        LocalAppThemeDimens provides dimens,
        content = content
    )
}

@Composable
fun materialAppThemeColors(): AppThemeColors {
    val colorScheme = MaterialTheme.colorScheme
    return AppThemeColors(
        background = colorScheme.background,
        surface = colorScheme.surface,
        surfaceVariant = colorScheme.surfaceVariant,
        primary = colorScheme.primary,
        primaryContainer = colorScheme.primaryContainer,
        tertiaryContainer = colorScheme.tertiaryContainer,
        error = colorScheme.error,
        onBackground = colorScheme.onBackground,
        onSurface = colorScheme.onSurface,
        onSurfaceVariant = colorScheme.onSurfaceVariant,
        onPrimary = colorScheme.onPrimary,
        onPrimaryContainer = colorScheme.onPrimaryContainer,
        onTertiaryContainer = colorScheme.onTertiaryContainer,
        outline = colorScheme.outline,
        outlineVariant = colorScheme.outlineVariant,
        success = Color(0xFF07C160),
        successContainer = if (colorScheme.surface.luminance() < 0.5f) {
            Color(0xFF16351F)
        } else {
            Color(0xFFE8F5E9)
        },
        warning = Color(0xFFFF9800),
        warningContainer = if (colorScheme.surface.luminance() < 0.5f) {
            Color(0xFF3A2A12)
        } else {
            Color(0xFFFFF3E0)
        },
        danger = colorScheme.error,
        dangerContainer = if (colorScheme.surface.luminance() < 0.5f) {
            Color(0xFF3A1518)
        } else {
            Color(0xFFFFEBEE)
        },
        inverseContent = Color.White,
        staticWhite = Color.White,
        staticBlack = Color.Black,
        scrim = Color.Black,
        primaryBubbleBackground = AppColors.primaryBubbleBackground(colorScheme),
        primaryBubbleContent = AppColors.primaryBubbleContent(colorScheme),
        secondaryBubbleBackground = AppColors.secondaryBubbleBackground(colorScheme),
        secondaryBubbleContent = AppColors.secondaryBubbleContent(colorScheme),
        secondaryBubbleBorder = AppColors.secondaryBubbleBorder(colorScheme),
        metadataContent = AppColors.metadataContent(colorScheme),
        menuBackground = AppColors.menuBackground(colorScheme),
        menuContent = AppColors.menuContent(colorScheme),
        menuIcon = AppColors.menuIcon(colorScheme),
        quotePrimaryBackground = AppColors.quoteBackground(colorScheme, isPrimary = true),
        quoteSecondaryBackground = AppColors.quoteBackground(colorScheme, isPrimary = false),
        quotePrimaryAuthor = AppColors.quoteAuthor(colorScheme, isPrimary = true),
        quoteSecondaryAuthor = AppColors.quoteAuthor(colorScheme, isPrimary = false),
        quotePrimaryPreview = AppColors.quotePreview(colorScheme, isPrimary = true),
        quoteSecondaryPreview = AppColors.quotePreview(colorScheme, isPrimary = false),
        dividerBackground = AppColors.dividerBackground(colorScheme),
        captionContent = AppColors.captionContent(colorScheme)
    )
}