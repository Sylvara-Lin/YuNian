package com.yunian.ai.uicommon.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFF2A2440),
    primaryContainer = PastelLavender,
    onPrimaryContainer = Color(0xFF2A2440),
    secondary = PastelSky,
    onSecondary = Color(0xFF2A2440),
    secondaryContainer = PastelMint,
    onSecondaryContainer = Color(0xFF2A2440),
    tertiary = PastelLavender,
    onTertiary = Color(0xFF2A2440),
    tertiaryContainer = PastelSky,
    onTertiaryContainer = Color(0xFF2A2440),
    background = WeChatLightBackground,
    onBackground = WeChatLightTextPrimary,
    surface = WeChatLightSurface,
    onSurface = WeChatLightTextPrimary,
    surfaceVariant = WeChatLightCard,
    onSurfaceVariant = WeChatLightTextSecondary,
    outline = WeChatLightDivider,
    outlineVariant = PastelSky.copy(alpha = 0.55f),
    error = ErrorRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFF2A2440),

    primaryContainer = PinkPrimaryContainerDark,
    onPrimaryContainer = PinkOnPrimaryContainerDark,
    secondary = PastelSky,
    onSecondary = Color(0xFF2A2440),
    secondaryContainer = WeChatDarkElevated,
    onSecondaryContainer = PastelLavender,
    tertiary = PastelLavender,
    onTertiary = Color(0xFF2A2440),
    tertiaryContainer = Color(0xFF2E2840),
    onTertiaryContainer = PastelMint,
    background = WeChatDarkBackground,
    onBackground = WeChatDarkTextPrimary,

    surface = WeChatDarkSurface,
    onSurface = WeChatDarkTextPrimary,
    surfaceVariant = WeChatDarkCard,
    onSurfaceVariant = WeChatDarkTextSecondary,
    outline = WeChatDarkDivider,
    outlineVariant = WeChatDarkElevated,
    error = ErrorRed,
    onError = Color.White,
    scrim = Color(0xFF000000),
    inverseSurface = WeChatLightSurface,
    inverseOnSurface = WeChatLightTextPrimary,
    inversePrimary = PinkDark
)

@Composable
fun YuNianTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDarkTheme = isSystemInDarkTheme()

    val effectiveDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDarkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        effectiveDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(effectiveDarkTheme) {
            val window = (view.context as Activity).window

            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !effectiveDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !effectiveDarkTheme
            onDispose { }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
