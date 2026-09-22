package com.yunian.ai.agent.skill

/**
 * SKILL.md frontmatter 解析（②：对齐 Claude Skills 社区规范）。
 *
 * 格式：正文首部 `---` 行包裹的 YAML 子集（key: value 单行），支持 name / description；
 * 无 frontmatter 或格式非法时原样返回正文。纯函数，JVM 可测。
 */
object SkillContentParser {

    /** 解析结果：frontmatter 字段 + 剥离后的正文。 */
    data class ParsedSkill(val name: String?, val description: String?, val body: String)

    fun parse(content: String): ParsedSkill {
        val lines = content.lines()
        if (lines.size < 3) return ParsedSkill(null, null, content)
        if (lines.first().trim() != "---") return ParsedSkill(null, null, content)
        // 找闭合 ---（第二行起）
        var closeIndex = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                closeIndex = i
                break
            }
        }
        if (closeIndex < 0) return ParsedSkill(null, null, content)
        var name: String? = null
        var description: String? = null
        for (i in 1 until closeIndex) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim().trim('"').trim('\'')
            when (key) {
                "name" -> name = value
                "description" -> description = value
            }
        }
        val body = lines.subList(closeIndex + 1, lines.size).joinToString("\n").trim()
        return ParsedSkill(name, description, body)
    }
}