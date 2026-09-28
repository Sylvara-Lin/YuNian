package com.yunian.ai.uicommon.theme

import android.app.Application
import android.content.ComponentCallbacks
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("theme_prefs", Application.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(readThemeMode(prefs))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(resolveIsDarkTheme(_themeMode.value))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme_mode") {
            val newMode = readThemeMode(prefs)
            if (newMode != _themeMode.value) {
                _themeMode.value = newMode
                _isDarkTheme.value = resolveIsDarkTheme(newMode)
            }
        }
    }

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (_themeMode.value == ThemeMode.SYSTEM) {
                _isDarkTheme.value = isNightMode(newConfig)
            }
        }

        override fun onLowMemory() {

        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        application.registerComponentCallbacks(configCallback)

        addCloseable(object : AutoCloseable {
            override fun close() {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
                getApplication<Application>().unregisterComponentCallbacks(configCallback)
            }
        })
    }

    fun setThemeMode(mode: ThemeMode) {
        if (mode == _themeMode.value) return
        _themeMode.value = mode
        _isDarkTheme.value = resolveIsDarkTheme(mode)
        // apply()：内存态同步更新（同进程后续 recreate 读得到），磁盘写入异步，避免主线程 fsync 阻塞。
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun applyTheme(activity: android.app.Activity) {
        activity.recreate()
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        getApplication<Application>().unregisterComponentCallbacks(configCallback)
        super.onCleared()
    }

    private fun resolveIsDarkTheme(mode: ThemeMode): Boolean {
        return when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isNightMode(getApplication<Application>().resources.configuration)
        }
    }

    private fun isNightMode(config: Configuration): Boolean {
        return (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun readThemeMode(prefs: SharedPreferences): ThemeMode {
        val saved = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }
}
