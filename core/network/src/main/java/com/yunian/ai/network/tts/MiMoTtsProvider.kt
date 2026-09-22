package com.yunian.ai.network.tts

import android.content.Context
import android.util.Base64
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

class MiMoTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

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

    @Volatile
    private var lastError: String? = null

    override fun lastError(): String? = lastError

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = config.mimoApiKey.trim()
                val baseUrl = normalizeBaseUrl(config.mimoBaseUrl)
                val model = normalizeModel(config.mimoModel)
                val outputFormat = normalizeOutputFormat(config.mimoOutputFormat)

                if (apiKey.isBlank() || baseUrl.isNullOrBlank()) {
                    lastError = "MiMo TTS 未配置（缺少 API Key 或 Base URL）"
                    SecureLog.w(TAG, "MiMo TTS not configured")
                    return@withContext null
                }
                if (text.isBlank()) {
                    lastError = "合成文本为空"
                    SecureLog.w(TAG, "MiMo TTS text is blank")
                    return@withContext null
                }

                val requestBody = buildRequestBody(model, text, voiceId, outputFormat)
                    ?: return@withContext null

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("api-key", apiKey)
                    .build()

                clientFor(text.length).newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastError = buildHttpError(response.code, extractServerMessage(body))
                        SecureLog.e(TAG, "HTTP ${response.code}, bodyBytes=${body.length}")
                        return@withContext null
                    }
                    val audioData = extractAudioData(body)
                    if (audioData.isBlank()) {
                        lastError = "响应缺少 audio.data 字段"
                        SecureLog.e(TAG, "response missing audio data")
                        return@withContext null
                    }
                    val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                    if (audioBytes.isEmpty()) {
                        lastError = "解码后音频为空"
                        SecureLog.e(TAG, "decoded empty audio")
                        return@withContext null
                    }
                    val outputDir = File(context.cacheDir, "tts/mimo").apply { mkdirs() }
                    val outputFile = File(outputDir, "mimo_${System.currentTimeMillis()}.$outputFormat")
                    outputFile.writeBytes(audioBytes)
                    if (!outputFile.exists() || outputFile.length() <= 0L) {
                        lastError = "输出音频文件为空"
                        SecureLog.e(TAG, "output file empty")
                        return@withContext null
                    }
                    lastError = null
                    SecureLog.i(TAG, "success bytes=${outputFile.length()} format=$outputFormat model=$model")
                    outputFile.absolutePath
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                SecureLog.e(TAG, "synthesis failed", e)
                null
            }
        }

    override fun getVoices(): List<TtsVoice> = listOf(
        TtsVoice("mimo_default", "MiMo-默认", "默认", "zh-CN", "中国集群为冰糖，其他集群为 Mia"),
        TtsVoice("冰糖", "冰糖", "女", "zh-CN", "预置精品音色"),
        TtsVoice("茉莉", "茉莉", "女", "zh-CN", "预置精品音色"),
        TtsVoice("苏打", "苏打", "男", "zh-CN", "预置精品音色"),
        TtsVoice("白桦", "白桦", "男", "zh-CN", "预置精品音色"),
        TtsVoice("Mia", "Mia", "女", "en", "预置精品音色"),
        TtsVoice("Chloe", "Chloe", "女", "en", "预置精品音色"),
        TtsVoice("Milo", "Milo", "男", "en", "预置精品音色"),
        TtsVoice("Dean", "Dean", "男", "en", "预置精品音色"),
        TtsVoice("__custom__", "自定义", "自定义", "自定义", "在下方配置卡片填写 voice")
    )

    override suspend fun testConnection(context: Context): Boolean = withContext(Dispatchers.IO) {
        val apiKey = config.mimoApiKey.trim()
        val baseUrl = normalizeBaseUrl(config.mimoBaseUrl) ?: return@withContext false
        if (apiKey.isBlank()) return@withContext false
        // 音色复刻模型缺样本时，直接给出可区分的提示，避免用户误判为 Key/网络问题。
        if (normalizeModel(config.mimoModel) == MODEL_TTS_VOICECLONE &&
            config.mimoVoiceClonePath.trim().isBlank()
        ) {
            lastError = "请先选择音频样本再测试连接"
            SecureLog.w(TAG, "voiceclone test connection without sample")
            return@withContext false
        }
        probeSpeech(apiKey, baseUrl)
    }

    private fun probeSpeech(apiKey: String, baseUrl: String): Boolean {
        return try {
            val model = normalizeModel(config.mimoModel)
            val requestBody = buildRequestBody(
                model = model,
                text = "测试",
                voiceId = config.mimoVoiceId.ifBlank { DEFAULT_VOICE },
                outputFormat = "wav",

                designPromptOverride = DEFAULT_VOICE_DESIGN_PROMPT
            ) ?: return false
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("api-key", apiKey)
                .build()

            clientFor(2).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastError = buildHttpError(response.code, extractServerMessage(body))
                    return false
                }
                val ok = extractAudioData(body).isNotBlank()
                if (ok) lastError = null
                ok
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            SecureLog.e(TAG, "testConnection failed", e)
            false
        }
    }

    private fun buildRequestBody(
        model: String,
        text: String,
        voiceId: String?,
        outputFormat: String,
        designPromptOverride: String? = null
    ): JSONObject? {
        return JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                when (model) {
                    MODEL_TTS_VOICEDESIGN -> {
                        val prompt = (designPromptOverride ?: config.mimoVoiceDesignPrompt.trim()).trim()
                        if (prompt.isBlank()) {
                            lastError = "音色设计模型需要先在设置页填写音色描述"
                            return null
                        }
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    }
                    MODEL_TTS_VOICECLONE -> {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "")
                        })
                    }
                    else -> {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "请将下一条 assistant 消息合成为自然中文语音。")
                        })
                    }
                }
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", JSONObject().apply {
                put("format", outputFormat)
                when (model) {
                    MODEL_TTS_VOICEDESIGN -> {
                        put("optimize_text_preview", config.mimoOptimizeTextPreview)
                    }
                    MODEL_TTS_VOICECLONE -> {
                        val dataUri = buildCloneVoiceDataUri() ?: return null
                        put("voice", dataUri)
                    }
                    else -> {
                        val resolvedVoiceId = when {
                            !voiceId.isNullOrBlank() && voiceId != "__custom__" -> voiceId
                            else -> config.mimoVoiceId.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
                        }
                        put("voice", resolvedVoiceId)
                    }
                }
            })
        }
    }

    private fun buildCloneVoiceDataUri(): String? {
        val path = config.mimoVoiceClonePath.trim()
        if (path.isBlank()) {
            lastError = "音色复刻模型需要先在设置页选择音频样本"
            return null
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            lastError = "音频样本文件不存在，请重新选择"
            return null
        }
        val length = file.length()
        if (length <= 0L) {
            lastError = "音频样本为空"
            return null
        }
        // 官方限制的是 base64 编码后的字符串长度（10MB）；按原始长度统一判据早退（与编码后判据恒等价）。
        if (MimoVoiceSampleFormat.exceedsCloneLimit(length)) {
            lastError = MimoVoiceSampleFormat.tooLargeMessage(length)
            return null
        }
        // 以文件头魔数为准判定真实格式（不轻信扩展名/MIME 声明）。
        val header = try {
            file.inputStream().use { readHeaderBytes(it, MimoVoiceSampleFormat.SNIFF_BYTES) }
        } catch (e: Exception) {
            lastError = "读取音频样本失败：${e.message ?: e.javaClass.simpleName}"
            SecureLog.e(TAG, "read clone sample header failed", e)
            return null
        }
        val format = MimoVoiceSampleFormat.detect(header, declaredMime = null, fileName = file.name)
            ?: run {
                lastError = MimoVoiceSampleFormat.unsupportedMessage(
                    declaredMime = null,
                    fileName = file.name
                )
                return null
            }
        val raw = try {
            file.readBytes()
        } catch (e: Exception) {
            lastError = "读取音频样本失败：${e.message ?: e.javaClass.simpleName}"
            SecureLog.e(TAG, "read clone sample failed", e)
            return null
        }
        if (raw.isEmpty()) {
            lastError = "音频样本为空"
            return null
        }
        val base64Str = Base64.encodeToString(raw, Base64.NO_WRAP)
        return "data:${format.apiMime};base64,$base64Str"
    }

    private fun readHeaderBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var read = 0
        while (read < maxBytes) {
            val n = input.read(buffer, read, maxBytes - read)
            if (n <= 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    /** 解析服务端错误响应体中的 message，供设置页展示（不含任何本地敏感信息）。 */
    private fun extractServerMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val root = JSONObject(body)
            val error = root.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: error?.optString("type")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 组装 HTTP 错误文案：`HTTP 400：<服务端 message（截断）>`。 */
    private fun buildHttpError(code: Int, serverMessage: String?): String {
        val head = "HTTP $code"
        if (serverMessage.isNullOrBlank()) return head
        val trimmed = if (serverMessage.length > MAX_ERROR_MESSAGE_CHARS) {
            serverMessage.take(MAX_ERROR_MESSAGE_CHARS) + "…"
        } else {
            serverMessage
        }
        return "$head：$trimmed"
    }

    private fun extractAudioData(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return message.optJSONObject("audio")?.optString("data").orEmpty()
    }

    companion object {
        private const val TAG = "MiMoTts"
        const val MODEL_TTS = "mimo-v2.5-tts"
        const val MODEL_TTS_VOICEDESIGN = "mimo-v2.5-tts-voicedesign"
        const val MODEL_TTS_VOICECLONE = "mimo-v2.5-tts-voiceclone"
        private const val DEFAULT_MODEL = MODEL_TTS
        private const val DEFAULT_VOICE = "mimo_default"
        private const val DEFAULT_VOICE_DESIGN_PROMPT = "清亮自然的中文女声，温柔亲切，语速适中，发音清晰。"
        private const val MAX_ERROR_MESSAGE_CHARS = 200
        private val ALLOWED_HOSTS = setOf(
            "api.xiaomimimo.com",
            "token-plan-cn.xiaomimimo.com",
            "token-plan-sgp.xiaomimimo.com",
            "token-plan-ams.xiaomimimo.com"
        )

        fun normalizeModel(value: String): String {
            val m = value.trim().lowercase(Locale.US)
            return when (m) {
                MODEL_TTS_VOICEDESIGN -> MODEL_TTS_VOICEDESIGN
                MODEL_TTS_VOICECLONE -> MODEL_TTS_VOICECLONE
                else -> MODEL_TTS
            }
        }

        fun normalizeBaseUrl(value: String): String? {
            val raw = value.trim().trimEnd('/')
            if (raw.isBlank()) return null
            return runCatching {
                val uri = URI(raw)
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host?.lowercase(Locale.US)
                val path = uri.path.orEmpty().trimEnd('/')
                if (scheme != "https" || host !in ALLOWED_HOSTS) return null
                if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
                if (uri.port != -1) return null
                if (path.isNotBlank() && path != "/v1") return null
                "https://$host/v1"
            }.getOrNull()
        }

        fun isAllowedBaseUrl(value: String): Boolean = normalizeBaseUrl(value) != null

        fun normalizeOutputFormat(value: String): String {
            val f = value.trim().lowercase(Locale.US)
            return if (f == "pcm" || f == "pcm16") "pcm" else "wav"
        }

        fun defaultBaseUrl(): String = "https://api.xiaomimimo.com/v1"
    }
}
