package com.yunian.ai.feature.skills.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SkillNames 单测：技能名「规范化而非拒绝」的全部边界。
 *
 * 回归背景（Bug ①）：旧实现对名字做 [A-Za-z0-9_-]{1,64} 硬校验，真实 SKILL.md 的
 * frontmatter name 含中文/空格/点号/冒号时被直接拒绝，导致「联网找到技能却装不上」。
 * 本测试锁定规范化语义，并验证「落盘名 == discover 暴露名 == loadSkill 入参名」的闭环不变量。
 */
class SkillNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- normalize：应被接受的真实技能名 ----------

    @Test
    fun normalize_chineseWithSpace_keptAndSpaceBecomesUnderscore() {
        assertEquals("PDF_处理", SkillNames.normalize("PDF 处理"))
    }

    @Test
    fun normalize_pureChinese_kept() {
        assertEquals("处理", SkillNames.normalize("处理"))
    }

    @Test
    fun normalize_spaceBecomesUnderscore() {
        assertEquals("web_design", SkillNames.normalize("web design"))
    }

    @Test
    fun normalize_colonAndDotBecomeUnderscore() {
        assertEquals("pdf-processing_v2", SkillNames.normalize("pdf-processing:v2"))
        assertEquals("v1_2_3", SkillNames.normalize("v1.2.3"))
    }

    @Test
    fun normalize_underscoreAndHyphenPreserved() {
        assertEquals("my_skill-v2", SkillNames.normalize("my_skill-v2"))
    }

    @Test
    fun normalize_trimsWhitespaceAndLeadingTrailingUnderscores() {
        assertEquals("spaced", SkillNames.normalize("  spaced  "))
        assertEquals("name", SkillNames.normalize("__name__"))
    }

    @Test
    fun normalize_mixedEmojiBecomesUnderscore() {
        assertEquals("技能_v2", SkillNames.normalize("技能🚀v2"))
    }

    @Test
    fun normalize_longNameTruncatedTo64() {
        val raw = "a".repeat(100)
        val normalized = SkillNames.normalize(raw)
        assertEquals(64, normalized!!.length)
        assertEquals("a".repeat(64), normalized)
    }

    @Test
    fun normalize_cjkSurrogateFreeText_kept() {
        // 带横线/下划线的中日韩名
        assertEquals("技能_日语-テスト", SkillNames.normalize("技能 日语-テスト"))
    }

    // ---------- normalize：应被拒绝（返回 null）的输入 ----------

    @Test
    fun normalize_pathTraversalRejected() {
        assertNull(SkillNames.normalize("../../etc/passwd"))
        assertNull(SkillNames.normalize(".."))
        assertNull(SkillNames.normalize("a/b"))
        assertNull(SkillNames.normalize("a\\b"))
        assertNull(SkillNames.normalize("skills/../secret"))
    }

    @Test
    fun normalize_emptyOrBlankRejected() {
        assertNull(SkillNames.normalize(""))
        assertNull(SkillNames.normalize("   "))
        assertNull(SkillNames.normalize(null))
    }

    @Test
    fun normalize_pureSymbolsRejected() {
        assertNull(SkillNames.normalize("!!!***###"))
        assertNull(SkillNames.normalize("🚀🚀🚀"))
        assertNull(SkillNames.normalize(".:;"))
    }

    // ---------- 闭环不变量 ----------

    /**
     * 闭环关键：discoverSkills() 暴露的是落盘 frontmatter 里的 name（= normalize 结果），
     * 模型再用这个 name 调 use_skill → loadSkill(name) → 内部再次 normalize 后拼路径。
     * 因此 normalize 必须幂等，否则「discover 拿到的名字」喂回 loadSkill 会拼到不存在的文件。
     */
    @Test
    fun normalize_isIdempotent_forAllAcceptedInputs() {
        val accepted = listOf(
            "PDF 处理", "处理", "web design", "pdf-processing:v2", "v1.2.3",
            "my_skill-v2", "__name__", "技能🚀v2", "a".repeat(100), "技能 日语-テスト",
        )
        for (raw in accepted) {
            val once = SkillNames.normalize(raw)!!
            val twice = SkillNames.normalize(once)
            assertEquals("normalize 必须幂等：raw='$raw' once='$once'", once, twice)
        }
    }

    // ---------- rewriteFrontmatterName：让落盘内容与文件名同名 ----------

    @Test
    fun rewrite_replacesExistingNameLineKeepsOtherFields() {
        val content = """
            ---
            name: 原始名 处理
            description: 一段描述
            version: 2.0
            ---
            正文内容
        """.trimIndent()
        val out = SkillNames.rewriteFrontmatterName(content, "原始名_处理")
        assertTrue(out.contains("name: 原始名_处理"))
        assertTrue(out.contains("description: 一段描述"))
        assertTrue(out.contains("version: 2.0"))
        assertTrue(out.endsWith("正文内容"))
        assertTrue(!out.contains("name: 原始名 处理"))
    }

    @Test
    fun rewrite_insertsNameWhenFrontmatterHasNoName() {
        val content = "---\ndescription: d\n---\nbody"
        val out = SkillNames.rewriteFrontmatterName(content, "新技能")
        assertTrue(out.contains("name: 新技能"))
        assertTrue(out.contains("description: d"))
        assertTrue(out.endsWith("body"))
    }

    @Test
    fun rewrite_prependsMinimalFrontmatterWhenAbsent() {
        val out = SkillNames.rewriteFrontmatterName("just body text", "skill_x")
        assertEquals("---\nname: skill_x\n---\njust body text", out)
    }

    /**
     * 形状断言：rewrite 产物必须能被 SkillManagerImpl.parseSkillContent 解析出同名 metadata。
     * parseSkillContent 要求首行 trim()=="---"，并在其后寻找下一个 "---" 作为 frontmatter 结束，
     * 再按 "name:" 前缀解析 name（值去引号）。这里直接核对这一形状。
     */
    @Test
    fun rewrite_outputParsesBackToSameName() {
        val normalized = SkillNames.normalize("PDF 处理")!!
        for (original in listOf(
            "---\nname: whatever\n---\nbody",
            "---\ndescription: d\n---\nbody",
            "no frontmatter at all",
        )) {
            val out = SkillNames.rewriteFrontmatterName(original, normalized)
            val lines = out.split("\n")
            assertEquals("首行必须是 frontmatter 起始符", "---", lines[0].trim())
            val end = (1 until lines.size).first { lines[it].trim() == "---" }
            val nameLine = lines.subList(1, end).first { it.trim().startsWith("name:") }
            val parsedName = nameLine.substringAfter(':').trim().trim('"', '\'')
            assertEquals("落盘 frontmatter 的 name 必须等于规范化名", normalized, parsedName)
        }
    }

    /** 文件系统级闭环模拟：安装写的文件名，必须能被按 discover 暴露名重新拼出的路径命中。 */
    @Test
    fun installDiscoverLoad_nameChainIsConsistent() {
        val externalDir = tmp.newFolder("external_skills")
        val rawName = "PDF 处理:v2"
        val normalized = SkillNames.normalize(rawName)!!
        val content = SkillNames.rewriteFrontmatterName("---\nname: x\n---\n# 正文", normalized)

        // 安装：落盘文件名为 normalized
        java.io.File(externalDir, "$normalized.md").writeText(content)

        // discover：暴露的 name 来自 frontmatter（这里断言与文件名一致）
        val lines = content.split("\n")
        val discoverName = lines.first { it.trim().startsWith("name:") }.substringAfter(':').trim()

        // loadSkill(discoverName)：内部再次 normalize 后拼 <name>.md
        val loadPath = java.io.File(externalDir, "${SkillNames.normalize(discoverName)!!}.md")
        assertTrue("loadSkill 必须能命中安装时落盘的文件", loadPath.isFile)

        // loadSkill(rawName)：模型用原始中文名调用也必须命中
        val loadPathRaw = java.io.File(externalDir, "${SkillNames.normalize(rawName)!!}.md")
        assertTrue("模型用原始名调用 use_skill 也必须命中", loadPathRaw.isFile)

        assertEquals(normalized, java.io.File(externalDir, "$normalized.md").nameWithoutExtension)
    }
}
