package com.yunian.ai.network

import android.content.Context
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.RemoteKeyProvider
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.DiaryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DiaryService(private val context: Context) : DiaryProvider {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "DiaryService"

        private val diaryClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        private val diaryPartnerClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
                .certificatePinner(CertificatePins.certificatePinner)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun shouldSignRequest(request: okhttp3.Request): Boolean {
            val host = request.url.host.lowercase()
            return request.header("X-LianYu-Session")?.isNotBlank() == true ||
                host == "api.lianyu.ai" || host.endsWith(".lianyu.ai")
        }

        private const val DIARY_SYSTEM_PROMPT = """你是虚拟角色本人，要以你自己（这个角色）的第一人称视角，写一篇只属于你自己的私密日记。

核心要求：
- 用第一人称"我"来写——"我"就是你这个角色本身，不是你正在聊天的对象
- 和你聊天的是你的情感对象（用户），在日记里用"你"或"TA"来指代，千万不要把"我"写成对方
- 写的是你自己的情绪、心思、被触动的地方，以及你对对方的在意、想念和内心活动
- 生成依据是一次会话总结，不要逐条复述聊天记录
- 像真人写的，不要像 AI 总结、报告或分析结论
- 字数 180-320 字
- 不要用"今天和XX聊天"这种开头，要像刚聊完后自然写下来的日记
- 可以有碎碎念、迟疑、感叹和小情绪
- 不要分点列表，用自然段落"""

        private const val DIARY_PROMPT_TEMPLATE = """你是「%s」，%s。请以你自己（这个角色）的第一人称视角，写一篇这次聊天后属于你自己的私密日记。

重要：日记里的"我"必须指代你（这个角色）本人；和你聊天的那个人是与你互动的对方，请在日记里用"你"或"TA"来指代，绝对不要写成交代对方视角的日记。

%s
以下是这次会话的内容：

%s

请写一篇日记，要求：
1. 全程用"我"的第一人称，"我"就是你（这个角色）本人
2. 重点写你自己的情绪、感受、心里的起伏、被触动的地方，以及你对对方的在意和心思
3. 自然、真实，像你刚结束聊天后自己写下来的
4. 把会话中的重点互动和你的情感变化融入进去
5. 不要直接复述对话内容，要消化成日记的语气
6. 开头不要用"今天和%s聊天"之类的套话

日记："""
    }

    private val apiConfigRepository: ApiConfigRepository

    init {
        val database = AppDatabase.getDatabase(context.applicationContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao(), database.apiProviderPresetDao())
    }

    override suspend fun generateDiary(
        companion: CompanionEntity,
        conversationText: String,
        memoryContext: String
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        val isPartner = config.provider == ApiProvider.PARTNER
        val keys = if (isPartner) emptyList() else resolveKeys(config)
        if (!isPartner && keys.isEmpty()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "\n关于我和TA的一些背景记忆：\n$memoryContext\n"
        } else ""

        val prompt = DIARY_PROMPT_TEMPLATE.format(
            companion.name,
            companion.personality.take(200),
            memoryHint,
            conversationText,
            companion.name
        )

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val jsonBody = buildString {
            append('{')
            append("\"model\":\"${escapeJson(config.model)}\",")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"${escapeJson(DIARY_SYSTEM_PROMPT)}\"},")
            append("{\"role\":\"user\",\"content\":\"${escapeJson(prompt)}\"}")
            append("],")
            append("\"temperature\":0.85,")
            append("\"max_tokens\":800")
            append('}')
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")

        if (isPartner) {

            val session = RemoteKeyProvider.ensureSession(context, forceRefresh = false)
                ?: return@withContext null
            requestBuilder.header("X-LianYu-Session", session.token)
            requestBuilder.header("X-LianYu-Client-Id", session.clientId)
        } else {
            requestBuilder.header("Authorization", "Bearer ${keys.first()}")
        }

        val request = requestBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            (if (isPartner) diaryPartnerClient else diaryClient).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Diary API failed: ${response.code} body=${response.body?.string()?.take(200)}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val content = parseChatCompletionContent(body)
                if (content.isNullOrBlank()) {
                    SecureLog.w(TAG, "Diary API returned empty content")
                    return@withContext null
                }

                val cleaned = content.trim()
                    .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                    .trim()

                return@withContext cleaned.ifBlank { null }
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Diary generation failed: ${e.message}")
            return@withContext null
        }
    }

    private suspend fun getConfig(): ApiConfig? {

        val store = AppSettingsStore(appContext)
        if (store.getDiaryEnabled()) {
            val baseUrl = store.getDiaryBaseUrl().trim().trimEnd('/')
            val model = store.getDiaryModel().trim()
            if (baseUrl.isNotBlank() && model.isNotBlank()) {
                return ApiConfig(
                    provider = ApiProvider.CUSTOM,
                    name = "日记专用",
                    apiKey = store.getDiaryApiKey().trim(),
                    baseUrl = baseUrl,
                    model = model
                )
            }
            SecureLog.w(TAG, "Diary custom API enabled but baseUrl/model blank, fallback to main config")
        }
        return apiConfigRepository.getActiveEnabledConfig()
    }

    private suspend fun resolveKeys(config: ApiConfig): List<String> {
        val keys = config.getAllApiKeys()
        if (keys.isNotEmpty()) return keys

        if (config.provider == ApiProvider.PARTNER) {
            val remote = runCatching {
                RemoteKeyProvider.fetchKeysAsync(appContext)
            }.getOrDefault(emptyList())
            if (remote.isNotEmpty()) {
                SecureLog.d(TAG, "Diary: resolved ${remote.size} remote partner key(s)")
                return remote
            }
            SecureLog.w(TAG, "Diary: PARTNER config but remote key fetch failed")
        }
        return emptyList()
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun parseChatCompletionContent(responseBody: String): String? {
        return try {
            val json = org.json.JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val message = firstChoice.optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Failed to parse diary response: ${e.message}")
            null
        }
    }
}
