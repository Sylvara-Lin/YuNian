package com.yunian.ai.uicommon.component

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackgroundSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    private val _mainBgKey = MutableStateFlow(getMainBackgroundKey(appContext))
    val mainBgKey: StateFlow<String> = _mainBgKey.asStateFlow()

    private val _chatBgKey = MutableStateFlow(getChatBackgroundKey(appContext))
    val chatBgKey: StateFlow<String> = _chatBgKey.asStateFlow()

    private val _customImageKeys = MutableStateFlow(listCustomBackgroundKeys(appContext))
    val customImageKeys: StateFlow<List<String>> = _customImageKeys.asStateFlow()

    private val _customSolidColors = MutableStateFlow(listCustomSolidColors(appContext))
    val customSolidColors: StateFlow<List<CustomSolidColor>> = _customSolidColors.asStateFlow()

    // 监听器必须持有强引用：SharedPreferences 对监听器只持弱引用，
    // 若不留字段，GC 后监听静默失效 —— 背景设置页改了背景，其他页面收不到通知（重启才恢复）
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "main_background" -> _mainBgKey.value = getMainBackgroundKey(appContext)
                "chat_background" -> _chatBgKey.value = getChatBackgroundKey(appContext)
            }
        }

    private val prefs = appContext.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    init {
        // 监听 SharedPreferences：任何 ViewModel 实例写背景，所有实例同步
        // （解决 Activity 作用域与 NavBackStackEntry 作用域 ViewModel 实例不同步的问题）
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    fun setMainBackground(key: String) {
        setMainBackgroundKey(appContext, key)
        _mainBgKey.value = key
    }

    fun setChatBackground(key: String) {
        setChatBackgroundKey(appContext, key)
        _chatBgKey.value = key
    }

    fun applyBackground(targetMain: Boolean, key: String) {
        if (targetMain) setMainBackground(key) else setChatBackground(key)
    }

    fun refreshCustomImages() {
        _customImageKeys.value = listCustomBackgroundKeys(appContext)
    }

    /**
     * ON_RESUME 兜底：无论 SP 监听器是否触发过，回到页面时强制从 SharedPreferences 重读。
     * 彻底解决「设置页改背景 → 返回外面不同步」。
     */
    fun refreshFromPrefs() {
        _mainBgKey.value = getMainBackgroundKey(appContext)
        _chatBgKey.value = getChatBackgroundKey(appContext)
        _customImageKeys.value = listCustomBackgroundKeys(appContext)
        _customSolidColors.value = listCustomSolidColors(appContext)
    }

    fun refreshCustomSolids() {
        _customSolidColors.value = listCustomSolidColors(appContext)
    }

    fun saveSolidColor(color: Color, name: String): String {
        val key = saveCustomSolidColor(appContext, color, name)
        refreshCustomSolids()
        return key
    }

    fun updateSolidColor(oldKey: String, color: Color, name: String): String {
        val newKey = updateCustomSolidColor(
            context = appContext,
            oldKey = oldKey,
            color = color,
            name = name
        )

        if (_mainBgKey.value == oldKey) {
            setMainBackground(newKey)
        }
        if (_chatBgKey.value == oldKey) {
            setChatBackground(newKey)
        }
        refreshCustomSolids()
        return newKey
    }

    fun deleteSolidColor(key: String, currentKey: String, targetMain: Boolean) {
        deleteCustomSolidColor(appContext, key)
        if (currentKey == key) {
            applyBackground(targetMain, "default")
        }
        refreshCustomSolids()
    }

    fun saveImageBackground(uri: Uri): String? {
        val key = saveCustomBackground(appContext, uri)
        if (key != null) refreshCustomImages()
        return key
    }

    fun saveImageBackground(bitmap: Bitmap): String? {
        val key = saveCustomBackground(appContext, bitmap)
        if (key != null) refreshCustomImages()
        return key
    }

    fun deleteImageBackground(key: String, currentKey: String, targetMain: Boolean) {
        deleteCustomBackground(appContext, key)
        if (currentKey == key) {
            applyBackground(targetMain, "default")
        }
        refreshCustomImages()
    }
}
