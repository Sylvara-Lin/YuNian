package com.yunian.ai.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillContentParserTest {

    @Test
    fun parses_frontmatter_name_and_description() {
        val content = "---\nname: 咖啡知识\ndescription: 关于咖啡的专业知识\n---\n正文内容在这里"
        val parsed = SkillContentParser.parse(content)
        assertEquals("咖啡知识", parsed.name)
        assertEquals("关于咖啡的专业知识", parsed.description)
        assertEquals("正文内容在这里", parsed.body)
    }

    @Test
    fun no_frontmatter_returns_body_unchanged() {
        val content = "普通技能正文，没有元数据头"
        val parsed = SkillContentParser.parse(content)
        assertNull(parsed.name)
        assertNull(parsed.description)
        assertEquals(content, parsed.body)
    }

    @Test
    fun malformed_frontmatter_falls_back_to_full_body() {
        val content = "---\n没有闭合的 frontmatter"
        val parsed = SkillContentParser.parse(content)
        assertEquals(content, parsed.body)
    }

    @Test
    fun quoted_values_are_stripped() {
        val content = "---\nname: 带引号的名字\n---\n正文"
        val parsed = SkillContentParser.parse(content)
        assertEquals("带引号的名字", parsed.name)
        assertEquals("正文", parsed.body)
    }
}