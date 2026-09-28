package com.yunian.ai.feature.skills.repository

/**
 * 技能名规范化工具。
 *
 * 背景：真实 SKILL.md 的 frontmatter `name` 大量包含中文、空格、点号、冒号等字符
 * （如 "PDF 处理"、"web design"、"pdf-processing:v2"）。旧实现用
 * `[A-Za-z0-9_-]{1,64}` 硬校验，会把这些真实技能直接拒绝，导致「联网找到技能却永远装不上」。
 *
 * 这里把「拒绝」改为「规范化」：保留中日韩等字母与数字、下划线、连字符，
 * 其余字符（空格/点号/冒号/斜杠等）统一转成下划线，并做长度截断。
 * 仅保留必要的防路径穿越校验：拒绝空名、含 `/`、`\` 或 `..` 的输入。
 *
 * 关键闭环：落盘文件名与写入内容的 frontmatter `name` 必须同为规范化后的名字，
 * 否则 [com.yunian.ai.domain.SkillManager.loadSkill] 按 `externalDir/<name>.md`
 * 拼路径会找不到文件，而 discoverSkills()/use_skill 用的是元数据里的 name，两边必须一致。
 */
internal object SkillNames {

    /** 允许的字符：任意语言文字（含中文）、数字、下划线、连字符；其余转下划线 */
    private val ILLEGAL_CHARS = Regex("[^\\p{L}\\p{N}_-]+")

    private const val MAX_NAME_LENGTH = 64

    /**
     * 规范化技能名。
     *
     * @return 规范化后的安全名字；若名称为空或含路径穿越特征则返回 null（调用方据此拒绝）。
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // 防路径穿越：拒绝任何目录分隔或上跳片段
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) return null
        val slug = ILLEGAL_CHARS.replace(trimmed, "_")
            .trim('_')
            .take(MAX_NAME_LENGTH)
            .trim('_')
        if (slug.isEmpty()) return null
        return slug
    }

    /**
     * 把 SKILL.md 内容里的 frontmatter `name` 改写为规范化后的名字。
     *
     * - 已有 YAML frontmatter：替换其中的 `name:` 行（保留其它字段）。
     * - 没有 frontmatter：在最前面补一个仅含 name 的 frontmatter，
     *   否则 [SkillManagerImpl.parseSkillContent] 会把该文件判为 "unknown" 而被 discoverSkills 过滤掉。
     */
    fun rewriteFrontmatterName(content: String, normalizedName: String): String {
        val lines = content.split("\n")
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                var nameLineIndex = -1
                for (i in 1 until end) {
                    if (lines[i].trim().startsWith("name:")) {
                        nameLineIndex = i
                        break
                    }
                }
                val mutable = lines.toMutableList()
                if (nameLineIndex >= 0) {
                    mutable[nameLineIndex] = "name: $normalizedName"
                } else {
                    mutable.add(1, "name: $normalizedName")
                }
                return mutable.joinToString("\n")
            }
        }
        // 无 frontmatter：补一个最小可用 frontmatter
        return "---\nname: $normalizedName\n---\n$content"
    }
}
