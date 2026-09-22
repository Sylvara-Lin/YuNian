package com.yunian.ai.feature.settings.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorldbookTransfer 纯逻辑单测（无 Android 依赖）。
 * 覆盖：序列化/反序列化往返、导入校验、未知字段容忍、导出文件名净化。
 */
class WorldbookTransferTest {

    private fun sampleDto() = WorldbookExportDto(
        name = "测试世界书",
        description = "smoke",
        entries = listOf(
            WorldbookEntryDto(keywords = listOf("关键词"), content = "正文", priority = 3),
            WorldbookEntryDto(
                keywords = emptyList(),
                content = "常驻条目",
                constantActive = true,
                useRegex = true,
                injectDepth = 4
            )
        )
    )

    @Test
    fun `serialize then parse roundtrips all fields`() {
        val dto = sampleDto()
        val text = WorldbookTransfer.serialize(dto)
        val parsed = WorldbookTransfer.parse(text).getOrThrow()
        assertEquals(dto, parsed)
    }

    @Test
    fun `parse tolerates unknown fields`() {
        val text = """
            {"format":"lianyu-worldbook","version":1,"name":"手写文件","entries":[
              {"keywords":["k"],"content":"c","someFutureField":123}
            ]}
        """.trimIndent()
        val dto = WorldbookTransfer.parse(text).getOrThrow()
        assertEquals("手写文件", dto.name)
        assertEquals(1, dto.entries.size)
        assertEquals("c", dto.entries[0].content)
    }

    @Test
    fun `parse fails on blank name`() {
        val text = WorldbookTransfer.serialize(sampleDto().copy(name = "  "))
        assertTrue(WorldbookTransfer.parse(text).isFailure)
    }

    @Test
    fun `parse fails when entries are empty`() {
        val text = WorldbookTransfer.serialize(sampleDto().copy(entries = emptyList()))
        assertTrue(WorldbookTransfer.parse(text).isFailure)
    }

    @Test
    fun `parse fails on malformed json`() {
        assertTrue(WorldbookTransfer.parse("not a json").isFailure)
    }

    @Test
    fun `defaultFileName sanitizes illegal characters`() {
        assertEquals(
            "lianyu_worldbook_a_b_c.json",
            WorldbookTransfer.defaultFileName("a/b\\c")
        )
        assertEquals(
            "lianyu_worldbook_name.json",
            WorldbookTransfer.defaultFileName("name")
        )
    }

    @Test
    fun `defaultFileName caps the name portion and keeps extension`() {
        val name = "很长的世界书名字".repeat(20)
        val fileName = WorldbookTransfer.defaultFileName(name)
        assertTrue(fileName.endsWith(".json"))
        assertTrue(
            "name portion must be capped at 40 chars: $fileName",
            fileName.removePrefix("lianyu_worldbook_").removeSuffix(".json").length <= 40
        )
    }
}
