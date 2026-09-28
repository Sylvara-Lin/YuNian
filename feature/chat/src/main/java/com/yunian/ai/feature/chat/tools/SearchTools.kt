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

    /** 搜索结果条目（对象级，便于单测） */
    internal data class SearchResult(val title: String, val url: String, val snippet: String)

    /**
     * 解析必应结果页（纯函数，可单测）。
     *
     * 真机事故（2026-09-28）：搜索工具「调用成功但永远 no results」。
     * 根因是**必应移动版把链接包在标题外面**：
     *   移动版：<a href="真实URL"><h2>标题</h2></a>   ← 旧正则要求 <h2><a ...，恒 0 命中
     *   桌面版：<h2><a href="真实URL">标题</a></h2>   ← 旧正则能命中
     * App 用的是移动 UA，因此线上必然搜不到；本实现同时覆盖两种形态，
     * 并把 /ck/a?...&u=a1<base64url> 形式的内链还原成真实地址。
     */
    internal fun parseBingResults(html: String, maxResults: Int): List<SearchResult> {
        if (html.isBlank()) return emptyList()
        val anchorTag = Regex(
            "<a\\b([^>]*)>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val hrefAttr = Regex("href=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val h2Tag = Regex(
            "<h2\\b[^>]*>(.*?)</h2>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val snippetTag = Regex(
            "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val found = LinkedHashMap<String, SearchResult>()

        fun snippetAfter(fromIndex: Int): String {
            val tail = html.substring(fromIndex.coerceAtLeast(0)).take(2000)
            return stripHtmlTags(snippetTag.find(tail)?.groupValues?.get(1).orEmpty()).trim()
        }

        fun add(title: String, url: String, snippet: String): Boolean {
            if (title.isBlank() || url.isBlank() || isBingInternalUrl(url)) return false
            if (!found.containsKey(url)) found[url] = SearchResult(title, url, snippet)
            return found.size >= maxResults
        }

        // ① 移动版形态（App 实际请求的 UA）：<a href="真实URL"><h2>标题</h2></a>
        for (m in anchorTag.findAll(html)) {
            val inner = m.groupValues[2]
            if (!inner.contains("<h2", ignoreCase = true)) continue
            val url = normalizeBingUrl(hrefAttr.find(m.groupValues[1])?.groupValues?.get(1)) ?: continue
            if (add(stripHtmlTags(inner).trim(), url, snippetAfter(m.range.last + 1))) {
                return found.values.toList()
            }
        }

        // ② 桌面版形态：<h2><a href="真实URL">标题</a></h2>（以及 App 未来若改 UA 的情况）
        for (h in h2Tag.findAll(html)) {
            val a = anchorTag.find(h.groupValues[1]) ?: continue
            val url = normalizeBingUrl(hrefAttr.find(a.groupValues[1])?.groupValues?.get(1)) ?: continue
            if (add(stripHtmlTags(a.groupValues[2]).trim(), url, snippetAfter(h.range.last + 1))) break
        }
        return found.values.toList()
    }

    /**
     * 必应链接归一：绝对地址直接返回；/ck/a?...&u=a1<base64url> 形式还原真实地址；
     * 其余相对内链（/rp/… 静态资源等）返回 null 由调用方丢弃。
     */
    internal fun normalizeBingUrl(raw: String?): String? {
        val href = raw?.trim().orEmpty()
        if (href.isEmpty()) return null
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val encoded = Regex("[?&]u=([^&]+)").find(href)?.groupValues?.get(1) ?: return null
        return runCatching {
            val body = if (encoded.startsWith("a1")) encoded.substring(2) else encoded
            val padded = body.replace('-', '+').replace('_', '/')
                .let { it + "=".repeat((4 - it.length % 4) % 4) }
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
                .takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    /** 必应/微软站内链接过滤（导航、跳转、翻译等非目标结果） */
    internal fun isBingInternalUrl(url: String): Boolean =
        url.contains("bing.com") ||
            url.contains("go.microsoft.com") ||
            url.contains("microsofttranslator.com")

    /** 解析 DuckDuckGo Lite 结果页（纯函数，可单测）。
     *
     * 注意：该页的 a 标签属性顺序不固定（实测 rel/href 在前、class="result-link" 在后），
     * 因此不能写死「class 在 href 之前」的正则，必须按属性集合判断。
     */
    internal fun parseDdgLiteResults(html: String, maxResults: Int): List<SearchResult> {
        if (html.isBlank()) return emptyList()
        val anchorRegex = Regex(
            "<a\\b([^>]*)>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val hrefAttr = Regex("href=\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE)
        val snippetRegex = Regex(
            "<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val snippets = snippetRegex.findAll(html).map { stripHtmlTags(it.groupValues[1]).trim() }.toList()
        val out = ArrayList<SearchResult>(maxResults)
        var index = 0
        for (m in anchorRegex.findAll(html)) {
            val attrs = m.groupValues[1]
            if (!attrs.contains("result-link", ignoreCase = true)) continue
            val url = hrefAttr.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            val title = stripHtmlTags(m.groupValues[2]).trim()
            if (url.isBlank() || title.isBlank()) continue
            out.add(SearchResult(title, url, snippets.getOrElse(index) { "" }))
            index++
            if (out.size >= maxResults) break
        }
        return out
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

            // 兜底链：Brave（配了 key）→ 必应中国 → DuckDuckGo Lite。
            // 每步失败都记录原因：旧实现把所有失败吞成「无结果」，
            // 模型与用户都无从判断是「真没搜到」还是「被拦截 / 解析失败」。
            val apiKey = appSettings.getSearchApiKey()
            val attempts = mutableListOf<String>()
            var usedSource = "none"
            val results = withTimeoutOrNull(25_000L) {
                if (apiKey.isNotBlank()) {
                    val brave = runCatching { braveSearch(apiKey, query, maxResults) }
                        .onFailure { attempts.add("brave: ${it.message}") }
                        .getOrDefault(emptyList())
                    if (brave.isNotEmpty()) {
                        usedSource = "brave"
                        return@withTimeoutOrNull brave
                    }
                    attempts.add("brave: 0 结果")
                }
                val bing = runCatching { bingSearch(query, maxResults) }
                    .onFailure { attempts.add("bing: ${it.message}") }
                    .getOrDefault(emptyList())
                if (bing.isNotEmpty()) {
                    usedSource = "bing"
                    return@withTimeoutOrNull bing
                }
                attempts.add("bing: 0 结果")
                val ddg = runCatching { ddgSearch(query, maxResults) }
                    .onFailure { attempts.add("ddg: ${it.message}") }
                    .getOrDefault(emptyList())
                if (ddg.isNotEmpty()) {
                    usedSource = "ddg"
                    return@withTimeoutOrNull ddg
                }
                attempts.add("ddg: 0 结果")
                emptyList()
            }
            if (results == null) {
                return buildJsonObject {
                    put("ok", false)
                    put("query", query)
                    put("error", "搜索超时（25s）：" + attempts.joinToString("；"))
                }.toString()
            }
            if (results.isEmpty()) {
                return buildJsonObject {
                    put("ok", false)
                    put("query", query)
                    put("error", "搜索未取到结果：" + attempts.joinToString("；"))
                }.toString()
            }

            return buildJsonObject {
                put("ok", true)
                put("query", query)
                put("source", usedSource)
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

        /** 免 key 兜底 1：必应中国网页版（国内直连可达）。失败即抛错，原因交上层汇总。 */
        private fun bingSearch(query: String, maxResults: Int): List<SearchResult> {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://cn.bing.com/search?q=$encodedQuery&mkt=zh-CN&count=$maxResults")
                .apply { browserHeaders(this) }
                .build()
            val html = newHttpClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("bing HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            return parseBingResults(html, maxResults)
        }

        /** 免 key 兜底 2：DuckDuckGo Lite（结构极简稳定；国内可能不可达，原因会回灌） */
        private fun ddgSearch(query: String, maxResults: Int): List<SearchResult> {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/?q=$encodedQuery")
                .apply { browserHeaders(this) }
                .build()
            val html = newHttpClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("ddg HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            return parseDdgLiteResults(html, maxResults)
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
