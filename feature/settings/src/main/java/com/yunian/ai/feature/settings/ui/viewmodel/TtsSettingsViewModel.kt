package com.yunian.ai.feature.settings.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.network.tts.ChatTtsConfig
import com.yunian.ai.network.tts.TtsConfig
import com.yunian.ai.network.tts.TtsProvider
import com.yunian.ai.network.tts.TtsService
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TtsSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val ttsService = TtsService.getInstance(appContext)

    fun saveSettings(
        config: TtsConfig,
        ttsEnabled: Boolean,
        provider: TtsProvider,
        voiceId: String
    ): Job {

        ttsService.updateConfig(config)
        ttsService.setProvider(provider)

        return viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TtsConfig.saveToSharedPreferences(appContext, config)
                appContext.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("tts_enabled", ttsEnabled)
                    .putString("tts_provider", provider.name)
                    .putString("tts_voice_${provider.name}", voiceId)
                    .apply()
            }
        }
    }

    fun saveChatTtsSettings(config: ChatTtsConfig): Job = viewModelScope.launch(Dispatchers.IO) {
        ChatTtsConfig.saveToSharedPreferences(appContext, config)
    }

    fun getTtsService(): TtsService = ttsService
}
