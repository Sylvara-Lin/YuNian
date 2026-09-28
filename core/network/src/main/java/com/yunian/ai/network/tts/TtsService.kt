package com.yunian.ai.network.tts

import android.content.Context
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TtsService(private val context: Context) {

    private val providers = mutableMapOf<TtsProvider, TtsProviderInterface>()
    private var currentProvider: TtsProvider = TtsProvider.entries.first()
    private val sherpaLocalTts = SherpaLocalTtsProvider()
    private var currentConfig: TtsConfig = TtsConfig.fromSharedPreferences(context)

    init {
        providers[TtsProvider.ALIYUN] = AliyunTtsProvider()
        providers[TtsProvider.BAIDU] = BaiduTtsProvider()
        providers[TtsProvider.XUNFEI] = XunfeiTtsProvider()
        providers[TtsProvider.MICROSOFT] = MicrosoftTtsProvider()
        providers[TtsProvider.VOLCENGINE] = VolcengineTtsProvider()
        providers[TtsProvider.SILICONFLOW] = SiliconFlowTtsProvider()
        providers[TtsProvider.MIMO] = MiMoTtsProvider()
        providers[TtsProvider.OPENAI_COMPAT] = OpenAiCompatibleTtsProvider()
        providers[TtsProvider.SHERPA_LOCAL] = sherpaLocalTts

        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        val providerName = prefs.getString("tts_provider", null)
        currentProvider = TtsProvider.entries.find { it.name == providerName } ?: TtsProvider.entries.first()
        providers.values.filterIsInstance<ConfigurableTtsProvider>().forEach { it.updateConfig(currentConfig) }
        SecureLog.i("TtsService", "Initialized with provider=${currentProvider.displayName}")
    }

    val localTtsManager: LocalTtsModelManager by lazy {
        LocalTtsModelManager.getInstance(context)
    }

    fun setProvider(provider: TtsProvider) {
        currentProvider = provider
        SecureLog.i("TtsService", "Switched TTS provider to ${provider.displayName}")
    }

    fun getCurrentProvider(): TtsProvider = currentProvider

    fun getAvailableProviders(): List<TtsProvider> = TtsProvider.entries.toList()

    fun updateConfig(config: TtsConfig) {
        currentConfig = config
        providers.forEach { (provider, providerInterface) ->
            if (providerInterface is ConfigurableTtsProvider) {
                providerInterface.updateConfig(config)
            }
        }
        SecureLog.i("TtsService", "TTS configuration updated")
    }

    fun getConfig(): TtsConfig = currentConfig

    @Volatile
    var lastSynthesisError: String? = null
        private set

    suspend fun synthesize(text: String, voiceId: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val provider = providers[currentProvider]
                ?: throw IllegalStateException("Provider ${currentProvider.name} not initialized")

            val resolvedVoiceId = voiceId ?: savedVoiceFor(currentProvider)

            SecureLog.d("TtsService", "Synthesizing text with ${currentProvider.displayName}, length=${text.length}")
            lastSynthesisError = null
            val result = provider.synthesize(context, text, resolvedVoiceId)

            if (result != null) {
                SecureLog.i("TtsService", "TTS synthesis successful: $result")
            } else {
                lastSynthesisError = provider.lastError() ?: "合成失败（${currentProvider.displayName}）"
                SecureLog.e("TtsService", "TTS synthesis returned null: $lastSynthesisError")
            }
            result
        } catch (e: Exception) {
            lastSynthesisError = e.message ?: e.javaClass.simpleName
            SecureLog.e("TtsService", "TTS synthesis failed", e)
            null
        }
    }

    private fun savedVoiceFor(provider: TtsProvider): String? {
        return runCatching {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            prefs.getString("tts_voice_${provider.name}", null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun getVoices(provider: TtsProvider = currentProvider): List<TtsVoice> {
        return providers[provider]?.getVoices() ?: emptyList()
    }

    suspend fun testProvider(provider: TtsProvider): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = providers[provider] ?: return@withContext false

            if (p is ConfigurableTtsProvider) {
                p.updateConfig(currentConfig)
            }

            val isConfigured = currentConfig.isProviderConfigured(provider)
            if (!isConfigured) {
                SecureLog.w("TtsService", "Provider ${provider.displayName} not configured")
                return@withContext false
            }

            val ok = p.testConnection(context)
            // 透出 provider 的具体失败原因（如"请先选择音频样本再测试连接"），避免统一显示"请检查配置"造成误导。
            lastSynthesisError = if (ok) {
                null
            } else {
                p.lastError() ?: "连接失败（${provider.displayName}）"
            }
            ok
        } catch (e: Exception) {
            SecureLog.e("TtsService", "Test provider ${provider.displayName} failed", e)
            false
        }
    }

    suspend fun testWithSampleText(
        provider: TtsProvider = currentProvider,
        text: String = "你好，这是一个语音合成测试。",
        voiceId: String? = null
    ): String? {
        return try {
            val p = providers[provider] ?: return null
            if (p is ConfigurableTtsProvider) {
                p.updateConfig(currentConfig)
            }

            val resolvedVoiceId = voiceId ?: savedVoiceFor(provider)
            SecureLog.i("TtsService", "Testing TTS with sample text for ${provider.displayName}, voice=$resolvedVoiceId")
            lastSynthesisError = null
            val result = p.synthesize(context, text, resolvedVoiceId)
            if (result == null) {
                lastSynthesisError = p.lastError() ?: "合成失败（${provider.displayName}）"
                SecureLog.e("TtsService", "Test synthesis failed: $lastSynthesisError")
            }
            result
        } catch (e: Exception) {
            lastSynthesisError = e.message ?: e.javaClass.simpleName
            SecureLog.e("TtsService", "Test synthesis failed", e)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: TtsService? = null

        fun getInstance(context: Context): TtsService {
            return instance ?: synchronized(this) {
                instance ?: TtsService(context.applicationContext).also { instance = it }
            }
        }
    }
}
