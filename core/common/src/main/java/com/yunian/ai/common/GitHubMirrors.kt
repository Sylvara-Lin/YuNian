package com.yunian.ai.common

/**
 * GitHub 原始文件链接的国内可达镜像展开。
 *
 * 背景：`raw.githubusercontent.com` 在国内经常极慢或被阻断，AI 安装技能时
 * 会在它上面反复重试，把整轮的时间预算耗光（表现为「网络连接超时」）。
 * jsDelivr 提供 GitHub 内容的全球 CDN 镜像（`cdn.jsdelivr.net/gh/<owner>/<repo>@<ref>/<path>`），
 * 国内通常可直连且更快，因此优先尝试镜像、原链作为兜底。
 */
object GitHubMirrors {

    private val RAW_HOST = Regex(
        "^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$",
        RegexOption.IGNORE_CASE,
    )

    private val BLOB_OR_RAW_PAGE = Regex(
        "^https?://github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/([^/]+)/(.+)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 返回按「优先尝试顺序」排列的候选 URL 列表。
     * 非 GitHub 链接原样返回单元素列表，调用方无需分支。
     */
    fun candidates(url: String): List<String> {
        val trimmed = url.trim()
        val match = RAW_HOST.find(trimmed) ?: BLOB_OR_RAW_PAGE.find(trimmed)
            ?: return listOf(trimmed)

        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val ref = match.groupValues[3]
        val path = match.groupValues[4]
        val jsdelivrPath = "$owner/$repo@$ref/$path"

        return listOf(
            // 镜像优先：国内直连快，且 404 时能快速回落原链
            "https://cdn.jsdelivr.net/gh/$jsdelivrPath",
            "https://fastly.jsdelivr.net/gh/$jsdelivrPath",
            trimmed,
        ).distinct()
    }

    /** 该链接是否属于「原始 GitHub 文件」这一类（可被镜像替换） */
    fun isRawGithub(url: String): Boolean {
        val trimmed = url.trim()
        return RAW_HOST.containsMatchIn(trimmed) || BLOB_OR_RAW_PAGE.containsMatchIn(trimmed)
    }
}
