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
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

class OpenAiCompatibleTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val baseClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
        RequestSecurityInterceptor.enforceTls(builder)
        builder.build()
    }

    private fun clientFor(textLength: Int): OkHttpClient {
        val timeoutMs = TimeoutBudgets.ttsSynthTimeoutMs(textLength)
        return baseClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private var config: TtsConfig = TtsConfig()

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = config.customTtsApiKey.trim()
                val speechUrl = normalizeSpeechUrl(config.customTtsUrl)
                val model = config.customTtsModel.trim().ifBlank { DEFAULT_MODEL }
                val voice = when {
                    !voiceId.isNullOrBlank() && voiceId != "__custom__" -> voiceId
                    else -> config.customTtsVoiceId.trim().ifBlank { DEFAULT_VOICE }
                }
                val format = normalizeFormat(config.customTtsResponseFormat)

                if (speechUrl.isNullOrBlank()) {
                    SecureLog.w(TAG, "Custom OpenAI TTS URL not configured")
                    return@withContext null
                }
                if (apiKey.isBlank()) {
                    SecureLog.w(TAG, "Custom OpenAI TTS API key not configured")
                    return@withContext null
                }
                if (text.isBlank()) {
                    SecureLog.w(TAG, "Custom OpenAI TTS text is blank")
                    return@withContext null
                }

                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("input", text)
                    put("voice", voice)
                    put("response_format", format)
                }.toString()

                val request = Request.Builder()
                    .url(speechUrl)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                clientFor(text.length).newCall(request).execute().use { response ->
                    val bodyBytes = response.body?.bytes()
                    if (!response.isSuccessful || bodyBytes == null || bodyBytes.isEmpty()) {
                        SecureLog.e(
                            TAG,
                            "HTTP ${response.code}, bodyBytes=${bodyBytes?.size ?: 0}"
                        )
                        return@withContext null
                    }

                    val contentType = response.header("Content-Type").orEmpty()
                    if (!isLikelyAudioBody(bodyBytes, contentType, format)) {
                        SecureLog.e(
                            TAG,
                            "Response body is not valid audio: code=${response.code}, " +
                                "contentType='$contentType', bytes=${bodyBytes.size}, " +
                                "head=${bodyBytes.toHexPreview()}"
                        )
                        return@withContext null
                    }

                    val outputDir = File(context.cacheDir, "tts/openai_compat").apply { mkdirs() }
                    val outputFile = File(outputDir, "openai_${System.currentTimeMillis()}.$format")
                    outputFile.writeBytes(bodyBytes)
                    if (!outputFile.exists() || outputFile.length() <= 0L) {
                        SecureLog.e(TAG, "Output file empty")
                        return@withContext null
                    }
                    SecureLog.i(TAG, "Synthesis success, bytes=${outputFile.length()}, format=$format")
                    outputFile.absolutePath
                }
            } catch (e: Exception) {
                SecureLog.e(TAG, "Synthesis failed", e)
                null
            }
        }

    override fun getVoices(): List<TtsVoice> = listOf(
        TtsVoice("alloy", "alloy", "中性", "en", "OpenAI 默认"),
        TtsVoice("echo", "echo", "男", "en", ""),
        TtsVoice("fable", "fable", "中性", "en", ""),
        TtsVoice("onyx", "onyx", "男", "en", ""),
        TtsVoice("nova", "nova", "女", "en", ""),
        TtsVoice("shimmer", "shimmer", "女", "en", ""),
        TtsVoice("__custom__", "自定义 voice", "自定义", "zh-CN", "在设置页填写 voice 字段")
    )

    override suspend fun testConnection(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val apiKey = config.customTtsApiKey.trim()
            val speechUrl = normalizeSpeechUrl(config.customTtsUrl) ?: return@withContext false
            if (apiKey.isBlank()) return@withContext false

            val format = normalizeFormat(config.customTtsResponseFormat)
            val jsonBody = JSONObject().apply {
                put("model", config.customTtsModel.trim().ifBlank { DEFAULT_MODEL })
                put("input", "测试")
                put("voice", config.customTtsVoiceId.trim().ifBlank { DEFAULT_VOICE })
                put("response_format", format)
            }.toString()

            val request = Request.Builder()
                .url(speechUrl)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            clientFor(2).newCall(request).execute().use { response ->
                val ok = response.isSuccessful && (response.body?.contentLength() ?: 1L) != 0L
                if (!ok) {
                    SecureLog.e(TAG, "testConnection HTTP ${response.code}")
                }
                ok
            }
        } catch (e: Exception) {
            SecureLog.e(TAG, "testConnection failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatTts"
        private const val DEFAULT_MODEL = "tts-1"
        private const val DEFAULT_VOICE = "alloy"
        private val ALLOWED_FORMATS = setOf("mp3", "opus", "aac", "flac", "wav", "pcm")

        fun normalizeSpeechUrl(raw: String): String? {
            val value = raw.trim().trimEnd('/')
            if (value.isBlank()) return null
            return runCatching {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host?.lowercase(Locale.US)
                if ((scheme != "https" && scheme != "http") || host.isNullOrBlank()) return null
                if (scheme == "http" && !isPrivateHost(host)) return null
                if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null

                val path = uri.path.orEmpty().trimEnd('/')
                val speechPath = when {
                    path.endsWith("/audio/speech") -> path
                    path.endsWith("/v1/audio") -> "$path/speech"
                    path.endsWith("/v1") -> "$path/audio/speech"
                    path.isBlank() -> "/v1/audio/speech"
                    else -> return null
                }
                val portPart = if (uri.port != -1) ":${uri.port}" else ""
                "$scheme://$host$portPart$speechPath"
            }.getOrNull()
        }

        fun isPrivateHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            if (h == "localhost" || h == "::1") return true
            return parseIpv4(h)?.let { ip ->
                ip[0] == 127.toByte() ||
                    ip[0] == 10.toByte() ||
                    (ip[0] == 172.toByte() && ip[1] in 16..31) ||
                    (ip[0] == 192.toByte() && ip[1] == 168.toByte()) ||
                    (ip[0] == 169.toByte() && ip[1] == 254.toByte())
            } ?: false
        }

        private fun parseIpv4(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            return runCatching {
                ByteArray(4) { i -> parts[i].toInt().also { if (it !in 0..255) throw IllegalArgumentException() }.toByte() }
            }.getOrNull()
        }

        fun normalizeFormat(raw: String): String {
            val f = raw.trim().lowercase(Locale.US)
            return if (f in ALLOWED_FORMATS) f else "mp3"
        }

        fun isLikelyAudioBody(
            body: ByteArray,
            contentType: String,
            format: String
        ): Boolean {
            val ct = contentType.lowercase(Locale.US)
            if (ct.isNotBlank()) {
                if (ct.startsWith("audio/")) return true
                if (ct.contains("json") || ct.startsWith("text/") ||
                    ct.contains("html") || ct.contains("xml")
                ) {
                    return false
                }
            }

            if (body.size >= 12 && body[0] == 'R'.code.toByte() && body[1] == 'I'.code.toByte() &&
                body[2] == 'F'.code.toByte() && body[3] == 'F'.code.toByte() &&
                body[8] == 'W'.code.toByte() && body[9] == 'A'.code.toByte() &&
                body[10] == 'V'.code.toByte() && body[11] == 'E'.code.toByte()
            ) {
                return true
            }
            if (body.size >= 3 && body[0] == 'I'.code.toByte() && body[1] == 'D'.code.toByte() &&
                body[2] == '3'.code.toByte()
            ) {
                return true
            }
            if (body.size >= 4 && body[0] == 0xFF.toByte() && (body[1] == 0xFB.toByte() ||
                    body[1] == 0xF3.toByte() || body[1] == 0xF2.toByte())
            ) {
                return true
            }
            if (body.size >= 4 && body[0] == 'f'.code.toByte() && body[1] == 'L'.code.toByte() &&
                body[2] == 'a'.code.toByte() && body[3] == 'C'.code.toByte()
            ) {
                return true
            }
            if (body.size >= 4 && body[0] == 'O'.code.toByte() && body[1] == 'g'.code.toByte() &&
                body[2] == 'g'.code.toByte() && body[3] == 'S'.code.toByte()
            ) {
                return true
            }
            if (body.size >= 4 && body[0] == 0xFF.toByte() && (body[1] == 0xF1.toByte() ||
                    body[1] == 0xF9.toByte())
            ) {
                return true
            }

            return body.size >= 128
        }

        private fun ByteArray.toHexPreview(limit: Int = 32): String =
            take(limit).joinToString("") { b ->
                val v = b.toInt() and 0xFF
                if (v in 0x20..0x7E) v.toChar().toString() else "."
            }
    }
}
