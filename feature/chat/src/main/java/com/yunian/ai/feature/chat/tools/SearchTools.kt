package com.yunian.ai.feature.chat.tools

import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.GitHubMirrors
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SearchTools {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun registerAll(appSettings: AppSettingsStore) {
        ToolRegistry.register(WebSearchTool(appSettings))
        ToolRegistry.register(WebFetchTool())
    }

    /** UA 伪装头：必应/DDG 等对非浏览器 UA 返回精简页或拒绝 */
    private fun browserHeaders(request: Request.Builder) {
        request.header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        )
        request.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    }

    private fun stripHtmlTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // 抓取是「可快速失败」的操作：某个源不可达时立刻换下一个候选，
        // 不要用长超时把整轮对话的时间预算耗光。
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 免 API key 的联网抓取工具（等效 OpenMinis 沙盒内 curl，但无需 Linux）。
     * AI 可用它读网页正文、调公开 GET 接口。GitHub 原始文件会自动走 jsDelivr 镜像，
     * 避免卡在 raw.githubusercontent.com 上；不过**装技能优先用 skillhub_search + skill_install**。
     */
    private class WebFetchTool : AiTool {
        override val name = "web_fetch"
        override val description = """
            抓取任意网页或公开 GET 接口的内容并转为纯文本。典型用途：
            1. 读取任意网页正文、调用公开 GET API 获取数据；
            2. 兜底找技能——只有在 skillhub_search 搜不到时才用：
               访问 https://api.github.com/search/repositories?q=<关键词> 搜技能仓库，
               从返回的 JSON 里读 full_name 与 default_branch，拼出
               https://raw.githubusercontent.com/<full_name>/<default_branch>/SKILL.md 交给 skill_install。
               严禁凭空编造 raw 地址；GitHub raw 链接会自动尝试 jsDelivr 镜像。
            返回去除 HTML 标签后的文本（默认最多 8000 字符）。
        """.trimIndent()
        override val parametersJsonSchema = """
            {"type":"object","properties":{"url":{"type":"string","description":"完整 URL（http/https）"},"max_chars":{"type":"integer","description":"返回文本最大长度，默认 8000，上限 20000"}},"required":["url"]}
        """.trimIndent()
        override fun systemPrompt() = "web_fetch: 抓取网页/GET 接口转文本。参数 {url: string, max_chars?: int}。装技能请优先用 skillhub_search；本工具仅作兜底，GitHub raw 会自动换 jsDelivr 镜像。"
        override val requiresConfirmation = false

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            val url = obj?.get("url")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val maxChars = obj?.get("max_chars")?.jsonPrimitive?.intOrNull?.coerceIn(200, 20_000) ?: 8_000
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return buildJsonObject { put("ok", false); put("error", "url 必须以 http:// 或 https:// 开头") }.toString()
            }

            // GitHub 原始文件在国内常不可达：按「镜像优先」的候选顺序依次尝试，
            // 单个候选失败立刻换下一个，避免把整轮对话的时间预算耗在同一个不可达的源上。
            val candidates = GitHubMirrors.candidates(url)
            var body: String? = null
            var lastError: String? = null
            for (candidate in candidates) {
                val outcome = withTimeoutOrNull(14_000L) {
                    runCatching {
                        val request = Request.Builder().url(candidate).apply { browserHeaders(this) }.build()
                        newHttpClient().newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) error("http ${resp.code}")
                            resp.body?.string().orEmpty()
                        }
                    }
                }
                when {
                    outcome == null -> lastError = "抓取超时"
                    outcome.isFailure -> lastError = outcome.exceptionOrNull()?.message
                    else -> {
                        val text = outcome.getOrDefault("")
                        if (text.isNotBlank()) {
                            body = text
                            break
                        }
                        lastError = "页面无可提取文本"
                    }
                }
            }
            val rawBody = body ?: return buildJsonObject {
                put("ok", false)
                put("error", "抓取失败: ${lastError ?: "所有候选地址都不可用"}")
                if (candidates.size > 1) put("tried", candidates.size)
            }.toString()

            val looksHtml = rawBody.contains("<html", ignoreCase = true) || rawBody.contains("<body", ignoreCase = true)
            val text = if (looksHtml) {
                rawBody
                    .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("(?s)<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .let { stripHtmlTags(it) }
            } else {
                rawBody
            }.trim()

            if (text.isBlank()) {
                return buildJsonObject { put("ok", false); put("error", "页面无可提取文本") }.toString()
            }
            return buildJsonObject {
                put("ok", true)
                put("url", url)
                put("truncated", text.length > maxChars)
                put("content", text.take(maxChars))
            }.toString()
        }
    }

    private class WebSearchTool(private val appSettings: AppSettingsStore) : AiTool {
        override val name = "search_web"
        override val description = """
            搜索网络获取实时信息或验证事实。
            当用户询问最新消息、当前事实、或需要验证时使用。
            生成聚焦的关键词，必要时进行多次搜索。
            结果包含标题、链接和摘要。无需配置即可使用。
        """.trimIndent()
        override val parametersJsonSchema = """
            {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"},"max_results":{"type":"integer","description":"最多返回结果数（默认 5，最大 10）"}},"required":["query"]}
        """.trimIndent()

        override fun systemPrompt() =
            "search_web 结果以 JSON 数组返回，每项含 title/url/snippet。引用时请注明来源链接。"

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            val query = obj?.get("query")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val maxResults = obj?.get("max_results")?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 5

            if (query.isBlank()) {
                return buildJsonObject { put("ok", false); put("error", "query 不能为空") }.toString()
            }

            // Brave（配置了 key 优先）→ 必应中国兜底（免 key 开箱即用）
            val apiKey = appSettings.getSearchApiKey()
            val results = withTimeoutOrNull(20_000L) {
                if (apiKey.isNotBlank()) {
                    runCatching { braveSearch(apiKey, query, maxResults) }.getOrElse { emptyList() }
                        .ifEmpty { runCatching { bingSearch(query, maxResults) }.getOrElse { emptyList() } }
                } else {
                    runCatching { bingSearch(query, maxResults) }.getOrElse { emptyList() }
                }
            } ?: emptyList()

            if (results.isEmpty()) {
                return buildJsonObject {
                    put("ok", true)
                    put("query", query)
                    put("empty", true)
                    put("note", "未找到相关结果，请尝试更换关键词")
                }.toString()
            }

            return buildJsonObject {
                put("ok", true)
                put("query", query)
                put("source", if (apiKey.isNotBlank()) "brave_or_bing" else "bing")
                put("results", buildJsonArray {
                    results.forEach { r ->
                        add(buildJsonObject {
                            put("title", r.title)
                            put("url", r.url)
                            put("snippet", r.snippet)
                        })
                    }
                })
            }.toString()
        }

        private data class SearchResult(val title: String, val url: String, val snippet: String)

        /** 免 key 兜底：必应中国网页版解析（国内直连可达） */
        private fun bingSearch(query: String, maxResults: Int): List<SearchResult> {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://cn.bing.com/search?q=$encodedQuery&mkt=zh-CN&count=$maxResults")
                .apply { browserHeaders(this) }
                .build()

            val html = newHttpClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string().orEmpty()
            }

            val blockRegex = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
            val linkRegex = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

            return blockRegex.findAll(html)
                .take(maxResults)
                .mapNotNull { match ->
                    val block = match.value
                    val link = linkRegex.find(block) ?: return@mapNotNull null
                    val title = stripHtmlTags(link.groupValues[2])
                    val url = link.groupValues[1]
                    val snippet = stripHtmlTags(snippetRegex.find(block)?.groupValues?.get(1).orEmpty())
                    if (title.isBlank() || url.isBlank()) null else SearchResult(title, url, snippet)
                }
                .toList()
        }

        private suspend fun braveSearch(apiKey: String, query: String, maxResults: Int): List<SearchResult> {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$maxResults")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val responseObj = json.parseToJsonElement(body).jsonObject
            val webResults = responseObj["web"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()

            return webResults
                .take(maxResults)
                .mapNotNull { element ->
                    val item = element.jsonObject
                    SearchResult(
                        title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        snippet = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
                .filter { it.title.isNotBlank() || it.snippet.isNotBlank() }
        }
    }
}
