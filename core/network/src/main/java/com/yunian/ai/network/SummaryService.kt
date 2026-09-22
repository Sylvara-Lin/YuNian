package com.yunian.ai.network

import android.content.Context
import com.yunian.ai.common.RemoteKeyProvider
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.SummaryProvider
import com.yunian.ai.database.repository.SummaryPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SummaryService(private val context: Context) : SummaryProvider {

    companion object {
        private const val TAG = "SummaryService"

        private val summaryClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        private val summaryPartnerClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
                .certificatePinner(CertificatePins.certificatePinner)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun shouldSignRequest(request: okhttp3.Request): Boolean {
            val host = request.url.host.lowercase()
            return request.header("X-LianYu-Session")?.isNotBlank() == true ||
                host == "api.lianyu.ai" || host.endsWith(".lianyu.ai")
        }

        private const val HISTORY_SYSTEM_ROLE =
            "你是对话叙事摘要助手，擅长把长对话整理成可续写的叙事摘要，而不是机械压缩字数。"

        private const val HISTORY_PROMPT_TEMPLATE = """请将以下对话历史整理为一段叙事摘要。

输出格式（必须按下列五维组织，可多行；信息不足的维度写「未明确」）：
时间：对话发生的时段、先后顺序、间隔与关键时间点
事件：实际发生了什么、谈了什么、达成了什么结果或未决事项
人物：涉及的人及其关系、称呼、身份与角色互动
驱动：各方动机、诉求、承诺、约定、计划与未完成意图
情绪：情绪起伏、氛围变化、关系温度与关键情感时刻

写作要求：
1. 第三人称，按时间线叙事，可连贯成段，也可按五维分行
2. 不设字数硬限制；该长则长、该短则短，以完整保留续聊所需信息为准
3. 优先保留：个人信息、偏好习惯、约定承诺、冲突与和好、关系进展
4. 省略纯寒暄、重复口头禅与无信息闲聊
5. 不要编造对话中未出现的内容
6. 直接输出摘要正文，不要额外总标题，不要用 bullet 列表堆砌

%s=== 对话历史 ===
%s"""

        /**
         * 滚动摘要增量合并模板：输入旧摘要 + 新增内容，要求按时间线 rewrite 为
         * 连续五维叙事，并去重旧摘要已包含的信息（而非简单拼接）。
         */
        private const val ROLLING_MERGE_SYSTEM_ROLE =
            "你是对话叙事摘要助手，擅长把旧摘要与新发生的对话融合为一份连续、无重复的可续写叙事。"

        private const val ROLLING_MERGE_PROMPT_TEMPLATE = """请把下面的「新增对话」合并进「已有摘要」，输出一份整合后的完整摘要。

输出格式（必须按下列五维组织，可多行；信息不足的维度写「未明确」）：
时间：对话发生的时段、先后顺序、间隔与关键时间点
事件：实际发生了什么、谈了什么、达成了什么结果或未决事项
人物：涉及的人及其关系、称呼、身份与角色互动
驱动：各方动机、诉求、承诺、约定、计划与未完成意图
情绪：情绪起伏、氛围变化、关系温度与关键情感时刻

写作要求：
1. 按时间线连续叙事（旧摘要在前、新增对话在后），不要分「旧摘要部分/新增部分」两段拼接
2. 旧摘要中已有的信息不要重复展开；新增对话若与旧摘要矛盾，以新增对话为准
3. 优先保留：个人信息、偏好习惯、约定承诺、冲突与和好、关系进展
4. 省略纯寒暄、重复口头禅与无信息闲聊；不要编造对话中未出现的内容
5. 直接输出摘要正文，不要额外总标题，不要用 bullet 列表堆砌

=== 已有摘要 ===
%s

=== 新增对话 ===
%s"""

        /**
         * 片段摘要模板：HISTORY 五维模板的片段版。用于超长增量内容的分段预摘要，
         * 产物后续会按序合并进滚动摘要，因此要求完整保留本片段内的信息。
         */
        private const val SEGMENT_PROMPT_TEMPLATE = """请将以下对话片段整理为一段叙事摘要（这是长对话的其中一个片段，后续会与其他片段按顺序合并，请完整保留本片段内的信息）。

输出格式（必须按下列五维组织，可多行；信息不足的维度写「未明确」）：
时间：对话发生的时段、先后顺序、间隔与关键时间点
事件：实际发生了什么、谈了什么、达成了什么结果或未决事项
人物：涉及的人及其关系、称呼、身份与角色互动
驱动：各方动机、诉求、承诺、约定、计划与未完成意图
情绪：情绪起伏、氛围变化、关系温度与关键情感时刻

写作要求：
1. 第三人称，按时间线叙事，可连贯成段，也可按五维分行
2. 完整保留本片段内对续聊有意义的信息（后续合并时不会再看到原始对话）
3. 省略纯寒暄、重复口头禅与无信息闲聊；不要编造对话中未出现的内容
4. 直接输出摘要正文，不要额外总标题，不要用 bullet 列表堆砌

%s=== 对话片段 ===
%s"""

        private const val MEMORY_SYSTEM_PROMPT = """你是某个虚拟角色本人，擅长从你自己的第一人称视角，把对话沉淀为可检索、可续写的私人记忆，而不是机械压到固定字数，也不要写成旁观者的旁白。

核心要求：
- 以第一人称「我」来记事——「我」就是你这个角色本人，绝不是正在和你聊天的对象
- 和你聊天的是你的情感对象（用户），一律用「你」或「TA」来指代
- 记的是你亲身经历、心里所想的事，而不是对别人的观察记录
- 当用户提示中未指定具体角色身份时，改用中性、客观的第三人称记录，不要出现「我」"""

        private const val MEMORY_PROMPT_TEMPLATE = """请将以下对话历史整理为可写入长期记忆的叙事摘要。
%s
输出格式（必须按下列五维组织，可多行；信息不足的维度写「未明确」）：
时间：何时发生、先后顺序与关键时间锚点
事件：核心事件、话题与结果
人物：相关人物、关系与称呼
驱动：动机、诉求、约定、计划与未完成事项
情绪：情绪变化与关系氛围

写作要求：
1. 不设字数硬限制；完整保留后续检索与续聊需要的事实
2. 已有记忆中已记录的信息简要带过，重点突出新信息
3. 省略闲聊与重复内容，不要编造
4. 直接输出摘要正文，不要额外总标题%s
5. 摘要正文写完后，另起一行输出「【重要记忆】」段，从对话中识别对方真正重要、值得长期记住的内容：
   - 格式：【重要记忆】类别|内容，每行一条，最多 3 条
   - 类别只能是：事实 / 偏好 / 关系 / 事件
   - 只收录明确、可长期有效的信息（如真实姓名、职业、重要的约定或关系定位）；
     随口一提、临时性情绪、寒暄不要收录
   - 没有值得收录的内容时输出：【重要记忆】无

=== 对话历史 ===
%s"""

        private const val CORE_MEMORY_SYSTEM_PROMPT = """你是某个虚拟角色本人，只从你自己的第一人称视角，识别并输出对话中真正值得长期记住的核心信息（关于对方的事实、偏好、关系与共同经历），不写叙事摘要。

核心要求：
- 内容涉及你自己时用「我」指代你这个角色本人，涉及对方时用「你」或「TA」
- 当用户提示中未指定具体角色身份时，改用中性、客观的第三人称，不要出现「我」"""

        private const val CORE_MEMORY_PROMPT_TEMPLATE = """请从下面的对话中识别值得长期记住的核心信息。
%s
输出要求：
- 每行一条，格式：【重要记忆】类别|内容，最多 3 条
- 类别只能是：事实 / 偏好 / 关系 / 事件
- 只收录明确、可长期有效的信息（如对方真实姓名、职业、喜好、重要的约定或关系定位）；
  随口一提、临时性情绪、寒暄不要收录
- 没有值得收录的内容时输出：【重要记忆】无
%s
=== 对话 ===
%s"""

        /**
         * 构造「记忆叙事摘要」的叙述视角约束段落。
         *
         * @param selfName AI 所扮演角色的名字（如「小梓」）；为 null 表示当前上下文没有单一角色身份
         *   （全局记忆 / 群聊），此时降级为中性第三人称描述。
         */
        private fun buildMemoryViewpoint(selfName: String?): String {
            val name = selfName?.trim().orEmpty()
            return if (name.isNotEmpty()) {
                """叙述视角（必须严格遵守）：
- 你是「$name」，这篇记忆要像你自己（这个角色）亲身经历后记下来的往事
- 「我」永远指代你自己（这个角色本人），绝对不要把「我」写成正在和你对话的那个人
- 和你说话的人是你情感上的对象（用户），一律用「你」或「TA」指代
- 五维中的「人物」维度要写成「我（$name）和他」这类关系描述；严禁出现「用户」「AI 助手」「AI 以 XX 身份代入」等旁观者措辞
- 「事件」「驱动」维度同样用第一人称叙述：写「我做了什么、我为什么这么做」，而不是旁人对你们的观察"""
            } else {
                """叙述视角：请以中性、客观的第三人称记录这段对话，不要使用「我」来指代对话中的任何一方；可用「用户」「AI 助手」或具体角色名等第三人称表述，并保留下面的五维结构。"""
            }
        }

        /**
         * 构造「核心记忆识别」的叙述视角约束段落。
         *
         * @param selfName AI 所扮演角色的名字；为 null 时降级为中性第三人称。
         */
        private fun buildCoreMemoryViewpoint(selfName: String?): String {
            val name = selfName?.trim().orEmpty()
            return if (name.isNotEmpty()) {
                """叙述视角（必须严格遵守）：
- 你是「$name」，每条记忆都以你自己（这个角色）的第一人称书写
- 涉及你自己时用「我」（「我」就是${name}本人），涉及聊天对象时用「你」或「TA」
- 严禁出现「用户」「AI 助手」「AI 以 XX 身份代入」等旁观者措辞；关于对方的信息写成「他/TA……」或「我（$name）和他」"""
            } else {
                """叙述视角：请用中性、客观的第三人称书写，不要使用「我」；可用「用户」「AI 助手」等第三人称表述。"""
            }
        }
    }

    private val apiConfigRepository: ApiConfigRepository

    init {
        val database = AppDatabase.getDatabase(context.applicationContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    }

    override fun isSummarySupported(): Boolean {

        return true
    }

    override suspend fun summarize(
        conversationText: String,
        memoryContext: String,
        purpose: SummaryPurpose,
        selfName: String?
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        val isPartner = config.provider == ApiProvider.PARTNER
        val keys = if (isPartner) emptyList() else config.getAllApiKeys()
        if (!isPartner && keys.isEmpty()) return@withContext null

        val systemRole: String
        val prompt: String
        val maxTokens: Int

        when (purpose) {
            SummaryPurpose.HISTORY -> {
                systemRole = HISTORY_SYSTEM_ROLE

                val memoryHint = if (memoryContext.isNotBlank()) {
                    "已知记忆参考（摘要应与这些记忆一致，不要矛盾）：\n${memoryContext.take(800)}\n"
                } else ""
                prompt = HISTORY_PROMPT_TEMPLATE.format(memoryHint, conversationText)
                maxTokens = 1200
            }
            SummaryPurpose.MEMORY -> {
                systemRole = MEMORY_SYSTEM_PROMPT

                val memoryHint = if (memoryContext.isNotBlank()) {
                    "\n\n=== 已有的长期记忆（以下内容不需要重复提取，只需关注未记录的新信息） ===\n$memoryContext"
                } else ""
                prompt = MEMORY_PROMPT_TEMPLATE.format(
                    buildMemoryViewpoint(selfName),
                    memoryHint,
                    conversationText
                )
                maxTokens = 900
            }
        }

        return@withContext sendCompletion(systemRole, prompt, maxTokens)
    }

    override suspend fun identifyCoreMemories(
        conversationText: String,
        memoryContext: String,
        selfName: String?
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "\n=== 已有的长期记忆（不要重复收录以下内容） ===\n${memoryContext.take(800)}"
        } else ""

        val prompt = CORE_MEMORY_PROMPT_TEMPLATE.format(
            buildCoreMemoryViewpoint(selfName),
            memoryHint,
            conversationText
        )
        sendCompletion(CORE_MEMORY_SYSTEM_PROMPT, prompt, maxTokens = 300)
    }

    /**
     * 滚动摘要增量合并：旧摘要 + 新增内容 → 整合摘要（去重旧摘要已含信息）。
     * 注意：getConfig() 仍走全局激活配置（本期不改，加注释标注）。
     */
    override suspend fun summarizeRollingMerge(
        oldSummary: String,
        newMessagesText: String,
        memoryContext: String,
        selfName: String?
    ): String? = withContext(Dispatchers.IO) {
        if (newMessagesText.isBlank()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "已知记忆参考（摘要应与这些记忆一致，不要矛盾）：\n${memoryContext.take(800)}\n"
        } else ""
        val prompt = "$memoryHint${ROLLING_MERGE_PROMPT_TEMPLATE.format(oldSummary.ifBlank { "（暂无，本次为首次摘要）" }, newMessagesText)}"

        sendCompletion(ROLLING_MERGE_SYSTEM_ROLE, prompt, maxTokens = 1200, temperature = 0.3)
    }

    /**
     * 片段摘要：超长新增内容的单片段预摘要（HISTORY 五维模板片段版）。
     * 注意：getConfig() 仍走全局激活配置（本期不改，加注释标注）。
     */
    override suspend fun summarizeSegment(
        segmentText: String,
        memoryContext: String,
        selfName: String?
    ): String? = withContext(Dispatchers.IO) {
        if (segmentText.isBlank()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "已知记忆参考（摘要应与这些记忆一致，不要矛盾）：\n${memoryContext.take(800)}\n"
        } else ""
        val prompt = SEGMENT_PROMPT_TEMPLATE.format(memoryHint, segmentText)

        sendCompletion(HISTORY_SYSTEM_ROLE, prompt, maxTokens = 900, temperature = 0.3)
    }

    private suspend fun sendCompletion(
        systemRole: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double = 0.3
    ): String? {
        val config = getConfig() ?: return null
        val isPartner = config.provider == ApiProvider.PARTNER
        val keys = if (isPartner) emptyList() else config.getAllApiKeys()
        if (!isPartner && keys.isEmpty()) return null

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val jsonBody = buildString {
            append('{')
            append("\"model\":\"${escapeJson(config.model)}\",")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"${escapeJson(systemRole)}\"},")
            append("{\"role\":\"user\",\"content\":\"${escapeJson(prompt)}\"}")
            append("],")
            append("\"stream\":false,")
            append("\"temperature\":${temperature.toApiTemperature()},")
            append("\"max_tokens\":$maxTokens")
            append('}')
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")

        if (isPartner) {

            val session = RemoteKeyProvider.ensureSession(context, forceRefresh = false)
                ?: return null
            requestBuilder.header("X-LianYu-Session", session.token)
            requestBuilder.header("X-LianYu-Client-Id", session.clientId)
        } else {
            requestBuilder.header("Authorization", "Bearer ${keys.first()}")
        }

        val request = requestBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            (if (isPartner) summaryPartnerClient else summaryClient).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Summary API failed: ${response.code} body=${response.body?.string()?.take(200)}")
                    return@use null
                }

                val body = response.body?.string() ?: return@use null
                val content = parseChatCompletionContent(body)
                if (content.isNullOrBlank()) {
                    SecureLog.w(TAG, "Summary API returned empty content")
                    return@use null
                }

                content.trim()
                    .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                    .replace(Regex("(?is)<thinking[^>]*>[\\s\\S]*?</thinking\\s*>"), "")
                    .trim()
                    .ifBlank { null }
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Summary generation failed: ${e.message}")
            null
        }
    }

    private suspend fun getConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
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
        val text = responseBody.trim()
        val payload = when {

            text.startsWith("{") -> text

            text.contains("\ndata:") || text.startsWith("data:") -> {
                text.lines()
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.startsWith("{") }
                    ?: return null
            }
            else -> return null
        }
        return try {
            val json = org.json.JSONObject(payload)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val message = firstChoice.optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Failed to parse summary response: ${e.message}")
            null
        }
    }
}
