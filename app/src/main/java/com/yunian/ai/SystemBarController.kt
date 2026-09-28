package com.yunian.ai

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

object SystemBarController {

    fun applyBaseContextLocale(base: android.content.Context): android.content.Context {

        val languageContext = com.yunian.ai.common.LocaleHelper.applyToContext(base)
        val config = Configuration(languageContext.resources.configuration)

        val themePrefs = base.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        when (themePrefs.getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> config.uiMode =
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            "DARK" -> config.uiMode =
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }

        return languageContext.createConfigurationContext(config)
    }

    fun applySystemBars(activity: Activity) {
        val prefs = activity.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        val isDark = when (prefs.getString("theme_mode", "SYSTEM")) {
            "DARK" -> true
            "LIGHT" -> false
            else -> (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        // 状态栏/导航栏透明，让 Compose 的动态背景（PageBackgroundContent）透出
        activity.window.statusBarColor = Color.Transparent.toArgb()
        activity.window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}
