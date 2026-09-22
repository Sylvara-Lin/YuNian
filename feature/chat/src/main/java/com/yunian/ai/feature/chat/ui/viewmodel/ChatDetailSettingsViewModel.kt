package com.yunian.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.feature.chat.data.ChatDetailSettingsStore
import com.yunian.ai.feature.chat.data.CompanionChatDetailSettings
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatDetailSettingsViewModel(
    application: Application,
    private val companionId: Long
) : AndroidViewModel(application) {

    private val store = ChatDetailSettingsStore(application)
    private val appSettingsStore = AppSettingsStore(application)

    val settings: StateFlow<CompanionChatDetailSettings> = store.settingsFlow(companionId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CompanionChatDetailSettings()
        )

    val innerThoughtEnabled: StateFlow<Boolean> = appSettingsStore.innerThoughtEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun updateSettings(
        transform: (CompanionChatDetailSettings) -> CompanionChatDetailSettings
    ): Job = viewModelScope.launch {
        store.updateSettings(companionId, transform)
    }

    fun setUseGlobalBackground(checked: Boolean): Job = viewModelScope.launch {
        store.updateSettings(companionId) { current ->
            if (checked) {
                current.copy(useGlobalBackground = true, backgroundKey = null)
            } else {
                val frozenKey = current.backgroundKey
                    ?.takeIf { key -> key.isNotBlank() }
                    ?: getChatBackgroundKey(getApplication())
                current.copy(
                    useGlobalBackground = false,
                    backgroundKey = frozenKey
                )
            }
        }
    }

    fun selectBackground(key: String): Job = viewModelScope.launch {
        store.updateSettings(companionId) { current ->
            if (key.isBlank() || key == "default") {
                current.copy(useGlobalBackground = true, backgroundKey = null)
            } else {
                current.copy(backgroundKey = key, useGlobalBackground = false)
            }
        }
    }

    fun setInnerThoughtEnabled(enabled: Boolean): Job = viewModelScope.launch {
        appSettingsStore.setInnerThoughtEnabled(enabled)
    }

    // ---------------- AI 生图单聊覆盖 ----------------

    /** 全局生图配置，用于界面展示与「开启覆盖」时提供合理的初始值。 */
    val globalImageGenEnabled: StateFlow<Boolean> = appSettingsStore.imageGenEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val globalImageGenProbability: StateFlow<Int> = appSettingsStore.imageGenTriggerProbabilityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val globalImageGenKeywords: StateFlow<List<String>> = appSettingsStore.imageGenKeywordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 开关单聊覆盖。开启时用全局值填充，避免出现「已覆盖但概率为 0、关键词为空」的空配置。
     * 关闭时保留已填内容，方便下次再开启。
     */
    fun setImageGenOverrideEnabled(enabled: Boolean): Job = viewModelScope.launch {
        store.updateSettings(companionId) { current ->
            if (!enabled) {
                current.copy(imageGenOverrideEnabled = false)
            } else {
                current.copy(
                    imageGenOverrideEnabled = true,
                    imageGenTriggerProbability = if (current.imageGenTriggerProbability == 0) {
                        globalImageGenProbability.value
                    } else {
                        current.imageGenTriggerProbability
                    },
                    imageGenKeywords = if (current.imageGenKeywords.isBlank()) {
                        AppSettingsStore.ImageGenDefaults.joinKeywords(globalImageGenKeywords.value)
                    } else {
                        current.imageGenKeywords
                    }
                )
            }
        }
    }

    fun setImageGenProbability(probability: Int): Job = viewModelScope.launch {
        store.updateSettings(companionId) {
            it.copy(imageGenTriggerProbability = probability.coerceIn(0, 100))
        }
    }

    fun setImageGenKeywords(raw: String): Job = viewModelScope.launch {
        store.updateSettings(companionId) { it.copy(imageGenKeywords = raw) }
    }

    fun resetSettings(): Job = viewModelScope.launch {
        store.resetSettings(companionId)
    }
}

class ChatDetailSettingsViewModelFactory(
    private val application: Application,
    private val companionId: Long
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras
    ): T {
        @Suppress("UNCHECKED_CAST")
        return ChatDetailSettingsViewModel(application, companionId) as T
    }
}
