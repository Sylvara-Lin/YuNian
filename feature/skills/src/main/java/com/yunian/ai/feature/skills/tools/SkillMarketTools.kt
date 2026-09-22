package com.yunian.ai.feature.skills.tools

import com.yunian.ai.common.GitHubMirrors
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.skills.net.SkillHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 技能市场工具：让 AI 自主搜技能并安装。
 *
 * 两个来源，按「快而不易失败」排序：
 * 1. **SkillHub（首选）**：国内直连的技能商店，搜索与下载都在腾讯云上，
 *    无需鉴权、不需翻墙。AI 先 `skillhub_search` 搜到 slug，再 `skill_install` 装。
 * 2. **GitHub（兜底）**：`skill_install` 直接给 SKILL.md 的 URL；
 *    raw.githubusercontent.com 会自动展开 jsDelivr 镜像，避免卡在不可达的原链上。
 *
 * 安装位置统一为用户私有外部技能目录，与内置技能同等可被发现与加载。
 */

/** 单次下载/抓取的失败前缀，供 AiToolLoopRunner 判定工具失败状态 */
private const val TAG = "SkillMarketTools"

private const val DOWNLOAD_TIMEOUT_S = 20L
private const val MAX_SKILL_BYTES = 512 * 1024

class SkillHubSearchTool(
    private val client: SkillHubClient,
) : AiTool {

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "skillhub_search"
    override val description = """
        在 SkillHub 技能商店（国内直连、免翻墙）搜索可安装的技能。
        用法：用中文或英文关键词搜索，拿到结果里的 slug，再用 skill_install 传入 slug 安装。
        这是安装技能的首选方式；只有在商店里搜不到时才回退到 web_fetch 找 GitHub 上的 SKILL.md。
        搜索接口偶发不可用时会自动回落到本地已缓存过的目录。
    """.trimIndent()
    override val parametersJsonSchema = """
        {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词，如 pdf、excel、图表、写作"},"limit":{"type":"integer","description":"返回条数，默认 8，上限 20"}},"required":["query"]}
    """.trimIndent()
    override fun systemPrompt() =
        "skillhub_search: 在国内技能商店搜索技能。参数 {query: string, limit?: int}。返回 slug/名称/描述/下载量，取 slug 交给 skill_install。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return buildError("Invalid arguments")
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isBlank()) return buildError("query 不能为空")
        val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull() ?: 8

        val outcome = client.search(query, limit)

        if (outcome.items.isEmpty()) {
            return if (outcome.fromCache) {
                buildError(outcome.cacheReason ?: "技能商店暂时连不上，本地也没有匹配的缓存；可改用 web_fetch 到 GitHub 找 SKILL.md 后安装")
            } else {
                buildJsonObject {
                    put("ok", true)
                    put("query", query)
                    put("empty", true)
                    put("hint", "商店里没有匹配的技能，换个关键词，或改用 web_fetch 到 GitHub 搜索")
                }.toString()
            }
        }

        return buildJsonObject {
            put("ok", true)
            put("query", query)
            if (outcome.fromCache) put("source", "本地缓存（商店暂时连不上）")
            put("results", buildJsonArray {
                outcome.items.forEach { item ->
                    add(buildJsonObject {
                        put("slug", item.slug)
                        put("name", item.title)
                        put("namespace", item.namespaceHandle)
                        put("version", item.version)
                        put("downloads", item.downloads)
                        put("summary", item.summary.take(160))
                        if (item.requiresApiKey) put("note", "该技能需要自备 API Key")
                    })
                }
            })
            put("hint", "选一个最匹配的，调用 skill_install 并传入 slug 与 version 安装")
        }.toString()
    }

    private fun buildError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()
}

class SkillInstallTool(
    private val skillManager: SkillManager,
    private val client: SkillHubClient,
) : AiTool {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val name = "skill_install"
    override val description = """
        安装技能。两种用法：
        1) **首选**：先 skillhub_search 搜到技能，把结果里的 slug 与 version 传进来安装（SkillHub 下载走国内直连，最稳）。
        2) 兜底：传 url（SKILL.md 的原始文件链接），GitHub raw 会自动尝试 jsDelivr 镜像。
        安装后该技能立即生效，Agent 下一回合即可通过 load_skill 加载。
    """.trimIndent()
    override val parametersJsonSchema = """
        {"type":"object","properties":{"slug":{"type":"string","description":"SkillHub 技能 slug（推荐，来自 skillhub_search）"},"version":{"type":"string","description":"推荐与 slug 一起传：skillhub_search 结果里的 version，缺省时用本地缓存补齐"},"namespace":{"type":"string","description":"可选：SkillHub 命名空间 handle"},"url":{"type":"string","description":"可选：SKILL.md 原始内容 URL（http/https）"},"name":{"type":"string","description":"可选：技能名，默认取 frontmatter 或 slug"}},"required":[]}
    """.trimIndent()
    override fun systemPrompt() =
        "skill_install: 安装技能。参数 {slug?: string, version?: string, namespace?: string, url?: string, name?: string}。优先用 skillhub_search 得到的 slug + version。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return@withContext buildError("Invalid arguments")

        val requested = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val slug = args["slug"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val namespace = args["namespace"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val version = args["version"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val (content, fallbackName) = when {
            slug.isNotBlank() -> {
                // version/namespace 没给全时用本地目录缓存补齐：即使商店接口此刻不可用，
                // 只要之前搜到过该技能，仍能走 COS 直连把包下下来。
                val known = if (version.isBlank() || namespace.isBlank()) client.lookup(slug) else null
                val resolvedVersion = version.ifBlank { known?.version.orEmpty() }
                val resolvedNamespace = namespace.ifBlank { known?.namespaceHandle.orEmpty() }
                val md = client.fetchSkillMarkdown(slug, resolvedNamespace, resolvedVersion)
                    ?: return@withContext buildError(
                        "从技能商店下载失败（slug=$slug）。请先用 skillhub_search 搜索该技能拿到 slug 与 version 再试，或用 url 传 SKILL.md 原始链接。"
                    )
                md to slug
            }

            url.startsWith("http://") || url.startsWith("https://") -> {
                val fetched = downloadFirstAvailable(url)
                    ?: return@withContext buildError(
                        "下载失败：所有候选地址都不可用。请用 web_fetch 确认 SKILL.md 的真实链接后再试。"
                    )
                fetched to url.substringBefore('?').substringAfterLast('/')
                    .removeSuffix(".md").removeSuffix(".skill")
            }

            else -> return@withContext buildError(
                "请提供 slug（推荐，来自 skillhub_search）或 SKILL.md 的 url。"
            )
        }

        if (content.length > MAX_SKILL_BYTES) {
            return@withContext buildError("技能文件过大（${content.length} 字节，上限 $MAX_SKILL_BYTES）")
        }
        if (content.isBlank()) {
            return@withContext buildError("技能内容为空，未安装")
        }

        val skillName = requested.ifBlank {
            parseFrontmatterName(content) ?: fallbackName
        }
        if (skillName.isBlank()) {
            return@withContext buildError("无法确定技能名，请在参数中指定 name")
        }

        val installed = skillManager.installSkill(skillName, content)
        if (!installed) {
            return@withContext buildError("安装失败：技能名无效或写入失败")
        }

        // Q6：技能索引缓存已退役（SkillIndexState 删除），技能目录改由 Rust
        // `SkillSelector` 每回合从 SkillStore 现读，故此处无需刷新索引。
        SecureLog.i(TAG, "Skill installed via tool: $skillName (source=${if (slug.isNotBlank()) "skillhub" else "url"})")
        buildJsonObject {
            put("ok", true)
            put("name", skillName)
            put("bytes", content.length)
            put("hint", "已安装，Agent 下一回合即可通过 load_skill 加载执行")
        }.toString()
    }

    /**
     * 依次尝试候选地址（GitHub raw 会先试 jsDelivr 镜像），
     * 任一成功即返回；全部失败返回 null。避免卡在不可达的原链上把整轮预算耗光。
     */
    private fun downloadFirstAvailable(url: String): String? {
        val candidates = GitHubMirrors.candidates(url)
        for ((index, candidate) in candidates.withIndex()) {
            val text = runCatching {
                http.newCall(Request.Builder().url(candidate).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        SecureLog.w(TAG, "download http ${resp.code}: $candidate")
                        return@use null
                    }
                    resp.body?.string()
                }
            }.onFailure { SecureLog.w(TAG, "download failed: $candidate (${it.message})") }.getOrNull()
            if (!text.isNullOrBlank()) return text
            SecureLog.w(TAG, "candidate ${index + 1}/${candidates.size} unavailable: $candidate")
        }
        return null
    }

    /** 从 frontmatter 提取 name: 字段 */
    private fun parseFrontmatterName(content: String): String? {
        val lines = content.split("\n")
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end <= 0) return null
        return lines.subList(1, end + 1)
            .firstOrNull { it.trim().startsWith("name:") }
            ?.substringAfter(':')?.trim()?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()
}

class SkillUninstallTool(
    private val skillManager: SkillManager,
) : AiTool {

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "skill_uninstall"
    override val description = "卸载外部安装的技能（内置技能不可卸载）。参数 {name: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"name":{"type":"string","description":"技能名称"}},"required":["name"]}
    """.trimIndent()
    override fun systemPrompt() = "skill_uninstall: 卸载外部技能。参数 {name: string}。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return buildError("Invalid arguments")
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return buildError("name 不能为空")

        val removed = skillManager.uninstallSkill(name)
        if (!removed) {
            return buildError("未找到外部技能 $name（内置技能不可卸载）")
        }
        // Q6：技能索引缓存已退役，卸载后无需刷新（Rust 侧每回合现读）。
        return buildJsonObject {
            put("ok", true)
            put("removed", name)
        }.toString()
    }

    private fun buildError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()
}

/** 注册技能市场工具（技能本体存储与 registerSkillTools 共享同一 SkillManager）。 */
fun registerSkillMarketTools(context: android.content.Context, skillManager: SkillManager) {
    // 目录缓存放在应用私有目录：商店搜索接口不稳定时，搜过的技能仍可离线安装
    val client = SkillHubClient(cacheDir = context.filesDir)
    ToolRegistry.register(SkillHubSearchTool(client))
    ToolRegistry.register(SkillInstallTool(skillManager, client))
    ToolRegistry.register(SkillUninstallTool(skillManager))
}
