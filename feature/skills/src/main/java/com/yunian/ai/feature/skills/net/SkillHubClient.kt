package com.yunian.ai.feature.skills.net

import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * SkillHub 技能商店客户端（国内多源，自动故障转移）。
 *
 * 实测结论（2026-09-10，同一网络下）：
 * - 下载走腾讯云 COS `skills/<slug>/<version>.zip`：**稳定且快**（约 0.2~0.3s）；
 * - 搜索接口 `api.skillhub.cn/api/v1/search`：**可用但不稳定**（会出现整段时间连不上）。
 *
 * 因此按「可靠性排序、逐个快速失败」：
 * - 搜索：在线接口（短超时）→ 失败则回落**本地目录缓存**（搜过的技能仍可搜到）；
 * - 下载：COS 直连（需 version）→ API 跳转下载 → COS 加速域名。
 *
 * 只要曾经搜到过某个技能，即使两个外部源轮流不可用也仍能装成功；
 * 且每个候选都有短超时，不会把整轮对话的时间预算耗在不可达的源上
 * —— 那正是之前表现为「网络连接超时」的根因。
 */
class SkillHubClient(
    cacheDir: File? = null,
) {

    private val cacheFile: File? = cacheDir?.let { File(it, CACHE_FILE_NAME) }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 搜索接口熔断时刻（毫秒时间戳）。
     *
     * 实测部分网络下 `api.skillhub.cn` 整段不可达（而 COS 下载仍然可达）。
     * 不做熔断的话，每一轮对话都会先在这里白等一个连接超时，把整轮时间预算耗掉。
     * 一旦失败就静默一段时间，期间直接走本地缓存 / 让模型转向其他源。
     */
    @Volatile
    private var apiCoolDownUntilMs: Long = 0L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Serializable
    data class Item(
        val slug: String = "",
        val name: String = "",
        val displayName: String = "",
        val description: String = "",
        @SerialName("description_zh") val descriptionZh: String = "",
        val version: String = "",
        val source: String = "",
        val downloads: Long = 0,
        val stars: Long = 0,
        @SerialName("owner_name") val ownerName: String = "",
        val namespace: Namespace? = null,
        val labels: Map<String, String> = emptyMap(),
    ) {
        @Serializable
        data class Namespace(
            val handle: String = "",
            @SerialName("publicSlug") val publicSlug: String = "",
            @SerialName("canonicalName") val canonicalName: String = "",
        )

        val title: String get() = displayName.ifBlank { name }.ifBlank { slug }

        val summary: String get() = descriptionZh.ifBlank { description }

        val namespaceHandle: String get() = namespace?.handle.orEmpty()

        /** 该技能需要自备 API Key（装了也用不了，提示模型避开） */
        val requiresApiKey: Boolean
            get() = labels["requires_api_key"]?.equals("true", ignoreCase = true) == true

        /** 本地缓存检索用：关键词是否命中该条目的任一可读字段 */
        fun matches(query: String): Boolean {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return true
            return slug.lowercase().contains(q) ||
                name.lowercase().contains(q) ||
                displayName.lowercase().contains(q) ||
                description.lowercase().contains(q) ||
                descriptionZh.lowercase().contains(q)
        }
    }

    /** 搜索结果：附带来源信息，便于提示模型当前是「在线结果」还是「本地缓存」 */
    data class SearchOutcome(
        val items: List<Item>,
        val fromCache: Boolean,
        val cacheReason: String? = null,
    )

    @Serializable
    private data class CacheFile(val skills: List<Item> = emptyList())

    @Serializable
    private data class SearchResponse(val results: List<Item> = emptyList())

    /**
     * 搜索技能：先试在线接口，失败则回落到本地缓存。
     * 在线成功时把结果写入缓存（含 version / namespace，供离线安装用）。
     */
    suspend fun search(query: String, limit: Int): SearchOutcome = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchOutcome(emptyList(), fromCache = false)

        val online = searchOnline(q, limit)
        if (online != null) {
            if (online.isNotEmpty()) mergeIntoCache(online)
            return@withContext SearchOutcome(online, fromCache = false)
        }

        val cached = readCache().filter { it.matches(q) }.take(limit)
        SearchOutcome(
            items = cached,
            fromCache = true,
            cacheReason = if (cached.isEmpty()) "技能商店暂时连不上，本地也没有匹配的缓存" else null,
        )
    }

    /**
     * 拉取技能正文（SKILL.md + 附录文档）。
     * 依次尝试：COS 直连（需 version）→ API 下载（自行解析版本）→ COS 加速域名。
     * 全部失败返回 null。
     */
    suspend fun fetchSkillMarkdown(slug: String, namespace: String, version: String): String? =
        withContext(Dispatchers.IO) {
            val cleanSlug = slug.trim().removePrefix("/").substringAfterLast('/').trim()
            if (cleanSlug.isEmpty()) return@withContext null

            val candidates = buildList {
                if (version.isNotBlank()) {
                    add("$COS_HOST/skills/${enc(cleanSlug)}/${enc(version)}.zip")
                }
                add("$API_HOST/api/v1/download?slug=${enc(cleanSlug)}&namespace=${enc(namespace)}&version=${enc(version)}")
                if (version.isNotBlank()) {
                    add("$COS_ACCELERATE_HOST/skills/${enc(cleanSlug)}/${enc(version)}.zip")
                }
            }

            for ((index, url) in candidates.withIndex()) {
                val bytes = getBytes(url)
                if (bytes == null || bytes.isEmpty()) {
                    SecureLog.w(TAG, "download miss (${index + 1}/${candidates.size}): $url")
                    continue
                }
                val markdown = runCatching { extractMarkdown(bytes) }
                    .onFailure { SecureLog.w(TAG, "unzip failed: ${it.message}") }
                    .getOrNull()
                if (!markdown.isNullOrBlank()) {
                    SecureLog.i(TAG, "SkillHub skill fetched: $cleanSlug@$version (${markdown.length} chars)")
                    return@withContext markdown
                }
            }
            null
        }

    /** 从本地缓存按 slug 找出已知的 version / namespace（离线安装用） */
    suspend fun lookup(slug: String): Item? = withContext(Dispatchers.IO) {
        val clean = slug.trim().removePrefix("/").substringAfterLast('/').trim()
        if (clean.isEmpty()) return@withContext null
        readCache().firstOrNull { it.slug.equals(clean, ignoreCase = true) }
    }

    private fun searchOnline(query: String, limit: Int): List<Item>? {
        // 熔断期内直接放弃在线搜索，避免每次都要白等一个连接超时
        if (System.currentTimeMillis() < apiCoolDownUntilMs) return null

        val url = "$API_HOST/api/v1/search?q=${enc(query)}&pageSize=${limit.coerceIn(1, 20)}"
        val body = getText(url)
        if (body == null) {
            apiCoolDownUntilMs = System.currentTimeMillis() + API_COOL_DOWN_MS
            SecureLog.w(TAG, "search API unreachable, cooling down ${API_COOL_DOWN_MS / 1000}s")
            return null
        }
        return runCatching { json.decodeFromString<SearchResponse>(body).results }
            .onFailure { SecureLog.w(TAG, "parse search failed: ${it.message}") }
            .getOrNull()
    }

    private fun readCache(): List<Item> {
        val file = cacheFile ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<CacheFile>(file.readText()).skills }
            .onFailure { SecureLog.w(TAG, "read cache failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun mergeIntoCache(items: List<Item>) {
        val file = cacheFile ?: return
        runCatching {
            val existing = readCache().associateBy { it.slug }
            val merged = LinkedHashMap<String, Item>()
            items.forEach { merged[it.slug] = it } // 新结果优先（版本可能更新）
            existing.forEach { (slug, item) -> merged.putIfAbsent(slug, item) }
            val trimmed = merged.values.toList().takeLast(MAX_CACHE_ITEMS)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(CacheFile(trimmed)))
        }.onFailure { SecureLog.w(TAG, "write cache failed: ${it.message}") }
    }

    /** 从技能 ZIP 中取出 SKILL.md，并把其余说明文档作为附录并入 */
    private fun extractMarkdown(archive: ByteArray): String? {
        val texts = linkedMapOf<String, String>()
        var skillMd: String? = null
        var skillMdDepth = Int.MAX_VALUE
        var budget = MAX_APPENDIX_CHARS

        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    val lower = name.lowercase()
                    if (lower.endsWith(".md") || lower.endsWith(".txt")) {
                        val content = zip.readTextCapped(budget)
                        if (content.isNotBlank()) {
                            budget -= content.length
                            val base = name.substringAfterLast('/')
                            if (base.equals("skill.md", ignoreCase = true)) {
                                // 取层级最浅的 SKILL.md（技能包根目录）
                                val depth = name.count { it == '/' }
                                if (depth < skillMdDepth) {
                                    skillMd = content
                                    skillMdDepth = depth
                                }
                            } else {
                                texts[base] = content
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val body = skillMd ?: return null
        if (texts.isEmpty()) return body

        val appendix = buildString {
            for ((name, content) in texts) {
                if (length + content.length > MAX_APPENDIX_CHARS) break
                append("\n\n## 附带文档：").append(name).append("\n\n").append(content.trim())
            }
        }
        return body + appendix
    }

    /** 读取 ZIP 条目内容，最多 [limit] 个字符，避免超大文件拖慢解析 */
    private fun ZipInputStream.readTextCapped(limit: Int): String {
        if (limit <= 0) return ""
        val out = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buf = ByteArray(8 * 1024)
        // UTF-8 中文一字最多 3 字节，按字符上限折算字节上限
        var remaining = limit.toLong() * 3
        while (remaining > 0) {
            val read = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun getText(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                SecureLog.w(TAG, "http ${resp.code} for $url")
                return@use null
            }
            val body = resp.body ?: return@use null
            val declared = body.contentLength()
            if (declared > MAX_ARCHIVE_BYTES) {
                SecureLog.w(TAG, "payload too large: $declared")
                return@use null
            }
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (read < MAX_ARCHIVE_BYTES) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    read += n
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        }
    }.onFailure { SecureLog.w(TAG, "request failed for $url: ${it.message}") }.getOrNull()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "SkillHubClient"

        const val CACHE_FILE_NAME = "skillhub_catalog.json"

        /** 搜索接口：可用但不稳定，短超时快速失败 */
        const val API_HOST = "https://api.skillhub.cn"

        /** 技能包静态托管：实测稳定、约 0.2s，下载首选 */
        const val COS_HOST = "https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com"
        const val COS_ACCELERATE_HOST = "https://skillhub-1388575217.cos.accelerate.myqcloud.com"

        const val CONNECT_TIMEOUT_S = 6L
        const val READ_TIMEOUT_S = 15L

        /** 搜索接口失败后的静默期：期间不再尝试在线搜索，直接用缓存并提示模型换源 */
        const val API_COOL_DOWN_MS = 5 * 60_000L
        const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
        const val MAX_APPENDIX_CHARS = 60_000
        const val MAX_CACHE_ITEMS = 300

        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
