package com.yunian.ai.network.tts

import android.content.Context
import com.yunian.ai.common.SecureLog

data class TtsConfig(
    val aliyunKeyId: String = "",
    val aliyunKeySecret: String = "",
    val aliyunAppKey: String = "",
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",
    val xunfeiAppId: String = "",
    val xunfeiApiKey: String = "",
    val xunfeiApiSecret: String = "",
    val azureSubscriptionKey: String = "",
    val azureRegion: String = "eastasia",
    val volcengineAppId: String = "",
    val volcengineToken: String = "",
    val volcengineCluster: String = "",

    val siliconflowApiKey: String = "",
    val siliconflowCustomVoiceId: String = "",
    val siliconflowUseGlobalKey: Boolean = true,
    val siliconflowTtsModel: String = "FunAudioLLM/CosyVoice2-0.5B",
    val siliconflowSpeed: String = "1.0",
    val siliconflowGain: String = "0",
    val siliconflowSampleRate: Int = 44100,

    val mimoApiKey: String = "",
    val mimoBaseUrl: String = MiMoTtsProvider.defaultBaseUrl(),
    val mimoModel: String = "mimo-v2.5-tts",
    val mimoVoiceId: String = "mimo_default",
    val mimoOutputFormat: String = "wav",
    val mimoVoiceDesignPrompt: String = "",
    val mimoVoiceClonePath: String = "",
    val mimoOptimizeTextPreview: Boolean = false,

    val customTtsUrl: String = "",
    val customTtsApiKey: String = "",
    val customTtsModel: String = "tts-1",
    val customTtsVoiceId: String = "alloy",
    val customTtsResponseFormat: String = "mp3",

    val localTtsSpeed: Float = 1.0f,
    val localTtsSid: Int = 0
) {
    fun isProviderConfigured(provider: TtsProvider): Boolean {
        return when (provider) {
            TtsProvider.ALIYUN -> aliyunKeyId.isNotBlank() && aliyunKeySecret.isNotBlank() && aliyunAppKey.isNotBlank()
            TtsProvider.BAIDU -> baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
            TtsProvider.XUNFEI -> xunfeiAppId.isNotBlank() && xunfeiApiKey.isNotBlank() && xunfeiApiSecret.isNotBlank()
            TtsProvider.MICROSOFT -> azureSubscriptionKey.isNotBlank()
            TtsProvider.VOLCENGINE -> volcengineAppId.isNotBlank() && volcengineToken.isNotBlank()
            TtsProvider.SILICONFLOW -> siliconflowUseGlobalKey || siliconflowApiKey.isNotBlank()
            TtsProvider.MIMO ->
                mimoApiKey.isNotBlank() && MiMoTtsProvider.isAllowedBaseUrl(mimoBaseUrl)
            TtsProvider.OPENAI_COMPAT ->
                customTtsApiKey.isNotBlank() &&
                    OpenAiCompatibleTtsProvider.normalizeSpeechUrl(customTtsUrl) != null
            TtsProvider.SHERPA_LOCAL -> true
        }
    }

    companion object {
        fun fromSharedPreferences(context: Context): TtsConfig {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            return TtsConfig(
                aliyunKeyId = prefs.getString("aliyun_key", "") ?: "",
                aliyunKeySecret = prefs.getString("aliyun_secret", "") ?: "",
                aliyunAppKey = prefs.getString("aliyun_app_key", "") ?: "",
                baiduApiKey = prefs.getString("baidu_key", "") ?: "",
                baiduSecretKey = prefs.getString("baidu_secret", "") ?: "",
                xunfeiAppId = prefs.getString("xunfei_app_id", "") ?: "",
                xunfeiApiKey = prefs.getString("xunfei_key", "") ?: "",
                xunfeiApiSecret = prefs.getString("xunfei_secret", "") ?: "",
                azureSubscriptionKey = prefs.getString("azure_key", "") ?: "",
                azureRegion = prefs.getString("azure_region", "eastasia") ?: "eastasia",
                volcengineAppId = prefs.getString("volcengine_app_id", "") ?: "",
                volcengineToken = prefs.getString("volcengine_token", "") ?: "",
                volcengineCluster = prefs.getString("volcengine_cluster", "") ?: "",
                siliconflowApiKey = prefs.getString("sf_api_key", "") ?: "",
                siliconflowCustomVoiceId = prefs.getString("sf_custom_voice_id", "") ?: "",
                siliconflowUseGlobalKey = prefs.getBoolean("sf_use_global_key", true),
                siliconflowTtsModel = prefs.getString("sf_tts_model", "FunAudioLLM/CosyVoice2-0.5B") ?: "FunAudioLLM/CosyVoice2-0.5B",
                siliconflowSpeed = prefs.getString("sf_speed", "1.0") ?: "1.0",
                siliconflowGain = prefs.getString("sf_gain", "0") ?: "0",
                siliconflowSampleRate = prefs.getInt("sf_sample_rate", 44100),
                mimoApiKey = prefs.getString("mimo_api_key", "") ?: "",
                mimoBaseUrl = MiMoTtsProvider.normalizeBaseUrl(
                    prefs.getString("mimo_base_url", MiMoTtsProvider.defaultBaseUrl())
                        ?: MiMoTtsProvider.defaultBaseUrl()
                ) ?: MiMoTtsProvider.defaultBaseUrl(),
                mimoModel = prefs.getString("mimo_model", "mimo-v2.5-tts") ?: "mimo-v2.5-tts",
                mimoVoiceId = prefs.getString("mimo_voice_id", "mimo_default") ?: "mimo_default",
                mimoOutputFormat = MiMoTtsProvider.normalizeOutputFormat(
                    prefs.getString("mimo_output_format", "wav") ?: "wav"
                ),
                mimoVoiceDesignPrompt = prefs.getString("mimo_voice_design_prompt", "") ?: "",
                mimoVoiceClonePath = prefs.getString("mimo_voice_clone_path", "") ?: "",
                mimoOptimizeTextPreview = prefs.getBoolean("mimo_optimize_text_preview", false),
                customTtsUrl = prefs.getString("custom_tts_url", "") ?: "",
                customTtsApiKey = prefs.getString("custom_tts_api_key", "") ?: "",
                customTtsModel = prefs.getString("custom_tts_model", "tts-1") ?: "tts-1",
                customTtsVoiceId = prefs.getString("custom_tts_voice_id", "alloy") ?: "alloy",
                customTtsResponseFormat = OpenAiCompatibleTtsProvider.normalizeFormat(
                    prefs.getString("custom_tts_response_format", "mp3") ?: "mp3"
                ),
                localTtsSpeed = prefs.getFloat("local_tts_speed", 1.0f),
                localTtsSid = prefs.getInt("local_tts_sid", 0)
            )
        }

        fun saveToSharedPreferences(context: Context, config: TtsConfig) {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("aliyun_key", config.aliyunKeyId)
                putString("aliyun_secret", config.aliyunKeySecret)
                putString("aliyun_app_key", config.aliyunAppKey)
                putString("baidu_key", config.baiduApiKey)
                putString("baidu_secret", config.baiduSecretKey)
                putString("xunfei_app_id", config.xunfeiAppId)
                putString("xunfei_key", config.xunfeiApiKey)
                putString("xunfei_secret", config.xunfeiApiSecret)
                putString("azure_key", config.azureSubscriptionKey)
                putString("azure_region", config.azureRegion)
                putString("volcengine_app_id", config.volcengineAppId)
                putString("volcengine_token", config.volcengineToken)
                putString("volcengine_cluster", config.volcengineCluster)
                putString("sf_api_key", config.siliconflowApiKey)
                putString("sf_custom_voice_id", config.siliconflowCustomVoiceId)
                putBoolean("sf_use_global_key", config.siliconflowUseGlobalKey)
                putString("sf_tts_model", config.siliconflowTtsModel)
                putString("sf_speed", config.siliconflowSpeed)
                putString("sf_gain", config.siliconflowGain)
                putInt("sf_sample_rate", config.siliconflowSampleRate)
                putString("mimo_api_key", config.mimoApiKey)
                putString(
                    "mimo_base_url",
                    MiMoTtsProvider.normalizeBaseUrl(config.mimoBaseUrl) ?: MiMoTtsProvider.defaultBaseUrl()
                )
                putString("mimo_model", config.mimoModel)
                putString("mimo_voice_id", config.mimoVoiceId)
                putString(
                    "mimo_output_format",
                    MiMoTtsProvider.normalizeOutputFormat(config.mimoOutputFormat)
                )
                putString("mimo_voice_design_prompt", config.mimoVoiceDesignPrompt)
                putString("mimo_voice_clone_path", config.mimoVoiceClonePath)
                putBoolean("mimo_optimize_text_preview", config.mimoOptimizeTextPreview)
                putString("custom_tts_url", config.customTtsUrl)
                putString("custom_tts_api_key", config.customTtsApiKey)
                putString("custom_tts_model", config.customTtsModel)
                putString("custom_tts_voice_id", config.customTtsVoiceId)
                putString(
                    "custom_tts_response_format",
                    OpenAiCompatibleTtsProvider.normalizeFormat(config.customTtsResponseFormat)
                )
                putFloat("local_tts_speed", config.localTtsSpeed)
                putInt("local_tts_sid", config.localTtsSid)
                apply()
            }
            SecureLog.i("TtsConfig", "Configuration saved to SharedPreferences")
        }
    }
}
