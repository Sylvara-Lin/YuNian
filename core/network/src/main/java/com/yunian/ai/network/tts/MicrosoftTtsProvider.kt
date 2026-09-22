package com.yunian.ai.network.tts

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class MicrosoftTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

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

    private var accessToken: String? = null
    private var tokenExpireTime: Long = 0
    private var config: TtsConfig = TtsConfig()

    override fun updateConfig(config: TtsConfig) {
        this.config = config
        this.accessToken = null
        this.tokenExpireTime = 0
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? = withContext(Dispatchers.IO) {
        try {
            val subscriptionKey = config.azureSubscriptionKey
            val region = config.azureRegion

            if (subscriptionKey.isBlank()) {
                SecureLog.w("MicrosoftTts", "Azure TTS not configured")
                return@withContext null
            }

            val token = getAccessToken(subscriptionKey, region) ?: return@withContext null
            val voice = voiceId ?: "zh-CN-XiaoxiaoNeural"

            val escapedText = escapeXml(text)
            val ssml = """
                <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="zh-CN">
                    <voice name="$voice">
                        $escapedText
                    </voice>
                </speak>
            """.trimIndent()

            val request = Request.Builder()
                .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/ssml+xml")
                .addHeader("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                .build()

            val response = clientFor(text.length).newCall(request).execute()
            if (!response.isSuccessful) {

                val errBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                SecureLog.e("MicrosoftTts", "HTTP ${response.code}: ${response.message} body=$errBody")
                return@withContext null
            }
            val body = response.body?.bytes()
            if (body == null || body.isEmpty()) {
                SecureLog.e("MicrosoftTts", "Empty response body")
                return@withContext null
            }

            val outputDir = File(context.cacheDir, "tts_audio")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "microsoft_${System.currentTimeMillis()}.mp3")
            outputFile.writeBytes(body)

            outputFile.absolutePath
        } catch (e: Exception) {
            SecureLog.e("MicrosoftTts", "Synthesis failed", e)
            null
        }
    }

    override fun getVoices(): List<TtsVoice> {
        return listOf(
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓", "女", "zh-CN", "温柔甜美的女声"),
            TtsVoice("zh-CN-YunxiNeural", "云希", "男", "zh-CN", "阳光帅气的男声"),
            TtsVoice("zh-CN-XiaoyiNeural", "晓伊", "女", "zh-CN", "亲切温柔的女声"),
            TtsVoice("zh-CN-YunjianNeural", "云健", "男", "zh-CN", "成熟稳重的男声"),
            TtsVoice("zh-CN-XiaochenNeural", "晓辰", "女", "zh-CN", "活泼开朗的女声"),
            TtsVoice("zh-CN-YunzeNeural", "云泽", "男", "zh-CN", "成熟稳重的男声"),
            TtsVoice("zh-CN-XiaoyanNeural", "晓颜", "女", "zh-CN", "标准女声"),
            TtsVoice("zh-CN-XiaoyiNeural", "晓伊", "女", "zh-CN", "甜美可爱的女声"),
            TtsVoice("zh-CN-XiaozhenNeural", "晓甄", "女", "zh-CN", "成熟知性的女声"),
            TtsVoice("zh-CN-YunjieNeural", "云杰", "男", "zh-CN", "成熟专业的男声")
        )
    }

    override suspend fun testConnection(context: Context): Boolean {
        return try {
            if (config.azureSubscriptionKey.isBlank() || config.azureRegion.isBlank()) return false

            getAccessToken(config.azureSubscriptionKey, config.azureRegion) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private suspend fun getAccessToken(subscriptionKey: String, region: String): String? = withContext(Dispatchers.IO) {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return@withContext accessToken
        }

        try {
            val url = "https://$region.api.cognitive.microsoft.com/sts/v1.0/issueToken"

            val request = Request.Builder()
                .url(url)
                .post("".toRequestBody("application/json".toMediaType()))
                .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
                .addHeader("Content-Length", "0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                SecureLog.e("MicrosoftTts", "Token request failed: HTTP ${response.code}")
                return@withContext null
            }

            accessToken = body
            tokenExpireTime = System.currentTimeMillis() + 9 * 60 * 1000

            accessToken
        } catch (e: Exception) {
            SecureLog.e("MicrosoftTts", "Get access token failed", e)
            null
        }
    }
}
