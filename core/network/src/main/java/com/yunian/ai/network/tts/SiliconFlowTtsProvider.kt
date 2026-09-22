package com.yunian.ai.network.tts

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.network.NetworkConstants
import com.yunian.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SiliconFlowTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val client = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        RequestSecurityInterceptor.enforceTls(builder)
        builder.build()
    }

    private fun clientFor(textLength: Int): OkHttpClient {
        val timeoutMs = TimeoutBudgets.ttsSynthTimeoutMs(textLength)
        return client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private var config: TtsConfig = TtsConfig()

    @Volatile
    private var lastErrorMsg: String? = null

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override fun lastError(): String? = lastErrorMsg

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? = withContext(Dispatchers.IO) {
        try {
            val url = NetworkConstants.SILICONFLOW_TTS_URL
            val apiKey = if (config.siliconflowUseGlobalKey) {
                getGlobalApiKey(context) ?: config.siliconflowApiKey
            } else {
                config.siliconflowApiKey
            }
            val model = config.siliconflowTtsModel.ifBlank { "FunAudioLLM/CosyVoice2-0.5B" }

            val rawVoice = when {
                config.siliconflowCustomVoiceId.isNotBlank() -> config.siliconflowCustomVoiceId
                voiceId.isNullOrBlank() || voiceId == "__custom__" ->
                    "FunAudioLLM/CosyVoice2-0.5B:anna"
                else -> voiceId
            }

            SecureLog.d(
                "SiliconFlowTts",
                "synthesize enter: len=${text.length}, useGlobal=${config.siliconflowUseGlobalKey}, " +
                    "customVoice='${config.siliconflowCustomVoiceId}', voiceId='$voiceId', rawVoice='$rawVoice'"
            )

            if (apiKey.isBlank()) {
                SecureLog.w("SiliconFlowTts", "API Key not configured")
                lastErrorMsg = "API Key 未配置"
                return@withContext null
            }

            SecureLog.d(
                "SiliconFlowTts",
                "key source: useGlobal=${config.siliconflowUseGlobalKey}, prefix=${apiKey.take(6)}…"
            )

            val finalVoice = resolveVoiceUri(apiKey, rawVoice)
            SecureLog.d("SiliconFlowTts", "request voice='$finalVoice' (raw='$rawVoice'), model=$model")

            val sampleRate = config.siliconflowSampleRate
            val speed = config.siliconflowSpeed.toDoubleOrNull() ?: 1.0
            val gain = config.siliconflowGain.toDoubleOrNull() ?: 0.0

            val jsonBody = JSONObject().apply {
                if (model.isNotBlank()) put("model", model)
                put("input", text)
                put("voice", finalVoice)
                put("response_format", "mp3")
                put("sample_rate", sampleRate)
                put("stream", false)
                put("speed", speed)
                put("gain", gain)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = clientFor(text.length).newCall(request).execute()
            if (!response.isSuccessful) {

                val errBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                lastErrorMsg = "HTTP ${response.code}: $errBody"
                SecureLog.e("SiliconFlowTts", "HTTP ${response.code}: ${response.message} body=$errBody")
                return@withContext null
            }
            val body = response.body?.bytes()
            if (body == null || body.isEmpty()) {
                lastErrorMsg = "服务端返回空音频（voice='$finalVoice'）"
                SecureLog.e("SiliconFlowTts", "Empty response body (voice='$finalVoice')")
                return@withContext null
            }
            SecureLog.i("SiliconFlowTts", "synthesize OK: code=${response.code}, bytes=${body.size}, voice='$finalVoice'")

            val outputDir = File(context.cacheDir, "tts_audio")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "siliconflow_${System.currentTimeMillis()}.mp3")
            outputFile.writeBytes(body)

            SecureLog.i("SiliconFlowTts", "TTS success: ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: e.javaClass.simpleName
            SecureLog.e("SiliconFlowTts", "Synthesis failed", e)
            null
        }
    }

    private suspend fun resolveVoiceUri(apiKey: String, voice: String): String {
        if (voice.startsWith("speech:") || voice.contains(":")) return voice
        return runCatching {
            val request = Request.Builder()
                .url(NetworkConstants.SILICONFLOW_VOICE_LIST_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    SecureLog.w("SiliconFlowTts", "voice list HTTP ${resp.code}, keep voice=$voice")
                    return@use voice
                }
                val json = resp.body?.string() ?: return@use voice
                resolveVoiceUriFromJson(json, voice) ?: voice
            }
        }.getOrDefault(voice)
    }

    override fun getVoices(): List<TtsVoice> {

        return listOf(

            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:alex", "Alex", "男", "zh-CN", "沉稳男声 (steady male)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:benjamin", "Benjamin", "男", "en-US", "深沉男声 (deep male)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:charles", "Charles", "男", "en-GB", "磁性男声 (magnetic male)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:david", "David", "男", "zh-CN", "阳光开朗男声 (cheerful male)"),

            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:anna", "Anna", "女", "zh-CN", "沉稳女声 (steady female)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:bella", "Bella", "女", "zh-CN", "热情女声 (passionate female)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:claire", "Claire", "女", "zh-CN", "温柔女声 (gentle female)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:diana", "Diana", "女", "en-US", "开朗女声 (cheerful female)"),

            TtsVoice("__custom__", "自定义音色", "自定义", "zh-CN", "使用克隆音色（设置页填入名称或 speech: URI）")
        )
    }

    override suspend fun testConnection(context: Context): Boolean {
        return try {
            val apiKey = if (config.siliconflowUseGlobalKey) {
                getGlobalApiKey(context) ?: config.siliconflowApiKey
            } else {
                config.siliconflowApiKey
            }
            if (apiKey.isBlank()) return false

            val request = Request.Builder()
                .url(NetworkConstants.SILICONFLOW_VOICE_LIST_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            SecureLog.e("SiliconFlowTts", "testConnection failed", e)
            false
        }
    }

    private fun getGlobalApiKey(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)
            prefs.getString("api_key_SILICONFLOW", null)
        } catch (e: Exception) {
            null
        }
    }

    companion object {

        internal fun resolveVoiceUriFromJson(json: String, voice: String): String? {
            return runCatching {
                val resultArray = JSONObject(json).let { obj ->
                    obj.optJSONArray("result") ?: obj.optJSONArray("results")
                } ?: return null
                for (i in 0 until resultArray.length()) {
                    val item = resultArray.optJSONObject(i) ?: continue
                    val name = item.optString("customName")
                    val uri = item.optString("uri")
                    if (name == voice || uri == voice) {
                        return uri.takeIf { it.isNotBlank() }
                    }
                }
                null
            }.getOrNull()
        }
    }
}
