package com.yunian.ai.network.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleJsonProtocolTest {

    @Test
    fun `parse - 严格 JSON`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"好啊，一起吧！","continue":true}""")
        assertNotNull(reply)
        assertEquals("好啊，一起吧！", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - continue false`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"就说到这里。","continue":false}""")
        assertNotNull(reply)
        assertFalse(reply!!.continueChat)
    }

    @Test
    fun `parse - 代码块包裹`() {
        val raw = """
            ```json
            {"text":"第一条","continue":true}
            ```
        """.trimIndent()
        val reply = BubbleJsonProtocol.parse(raw)
        assertNotNull(reply)
        assertEquals("第一条", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - 前后杂文包裹时外围正文并入结果`() {
        // P2-A5：JSON 外围的非空正文不再静默丢弃，与 JSON 的 text 一并保留（外围在前）。
        val raw = "好的，我看看。\n{\"text\":\"被提取的文本\",\"continue\":false}\n以上。"
        val reply = BubbleJsonProtocol.parse(raw)
        assertNotNull(reply)
        assertTrue(reply!!.text.contains("好的，我看看。"))
        assertTrue(reply.text.contains("被提取的文本"))
        assertTrue(reply.text.contains("以上。"))
        assertFalse(reply.continueChat)
    }

    @Test
    fun `parse - JSON 后的尾随正文并入结果`() {
        // P2-A5：text 同时包含 JSON 正文 + 后半句。
        val reply = BubbleJsonProtocol.parse("{\"text\":\"你好\",\"continue\":false}\n其实我想说别的")
        assertNotNull(reply)
        assertTrue(reply!!.text.contains("你好"))
        assertTrue(reply.text.contains("其实我想说别的"))
        assertFalse(reply.continueChat)
    }

    @Test
    fun `parseStrict - 外围正文不并入 JSON 透传不变`() {
        // P2-A5 仅宽容模式生效：strict 模式下即使有外围正文仍只透传 JSON 的 text。
        val reply = BubbleJsonProtocol.parseStrict("{\"text\":\"严格提取\",\"continue\":true}\n尾随正文")
        assertNotNull(reply)
        assertEquals("严格提取", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - 空 text 返回 null`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"","continue":true}""")
        assertNull(reply)
    }

    @Test
    fun `parse - 空白 text 返回 null`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"   ","continue":true}""")
        assertNull(reply)
    }

    @Test
    fun `parse - 无 continue 字段默认为不继续`() {
        val reply = BubbleJsonProtocol.parse("""{"text":"只是陈述。"}""")
        assertNotNull(reply)
        assertFalse(reply!!.continueChat)
    }

    @Test
    fun `parse - 非法 JSON 降级为单条文本`() {
        val reply = BubbleJsonProtocol.parse("这是一条普通的自然语言回复，没有 JSON。")
        assertNotNull(reply)
        assertEquals("这是一条普通的自然语言回复，没有 JSON。", reply!!.text)
        assertFalse(reply.continueChat)
    }

    @Test
    fun `parseStrict - 非法 JSON 返回 null 触发重试`() {

        assertNotNull(BubbleJsonProtocol.parse("这是一条普通的自然语言回复，没有 JSON。"))
        assertNull(BubbleJsonProtocol.parseStrict("这是一条普通的自然语言回复，没有 JSON。"))
    }

    @Test
    fun `parseStrict - 有效 JSON 正常解析`() {
        val reply = BubbleJsonProtocol.parseStrict("""{"text":"严格模式正常","continue":true}""")
        assertNotNull(reply)
        assertEquals("严格模式正常", reply!!.text)
        assertTrue(reply.continueChat)
    }

    @Test
    fun `parse - 纯空白降级 null`() {
        assertNull(BubbleJsonProtocol.parse("   \n\t  "))
    }

    @Test
    fun `parse - 超长非 JSON 返回 null 触发重试`() {
        val long = "长".repeat(5000)
        val reply = BubbleJsonProtocol.parse(long)
        assertNull(reply)
    }

    @Test
    fun `extractTextLenient - 被截断的 JSON 仍能抠出 text`() {
        val raw = """{"text":"你好","continue":"""
        assertEquals("你好", BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 含转义字符正确反转义`() {
        val raw = """{"text":"a\"b\nc","continue":false}"""
        assertEquals("a\"b\nc", BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 纯文本无 JSON 特征原样返回`() {
        val raw = "这是一条正常的自然语言回复，没有任何 JSON 结构。"
        assertEquals(raw, BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 只有空 JSON 无 text 返回空串`() {
        // P0-1：纯 JSON 残壳不含任何字母/数字/CJK 正文 → 归一空串，
        // 交给上层 aiContent.isBlank() 分支提示「API返回空内容」，不得展示残壳。
        val result = BubbleJsonProtocol.extractTextLenient("{}")
        assertEquals("", result)
    }

    @Test
    fun `extractTextLenient - 首行为 text 但末行非 continue 时原样返回不丢首行`() {
        val raw = "text\n哈哈好吧\n今天天气不错"
        assertEquals(raw, BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 两端骨架特征齐备时正确剔骨`() {
        val raw = "text\n你好呀\ncontinue"
        assertEquals("你好呀", BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 纯 emoji 文本是正文不得判空`() {
        // P2-new-1：emoji 是有效正文（astral 平面代理对），骨架残留检测不得误判为空
        val raw = """{"text":"😂😂","continue":true}"""
        assertEquals("😂😂", BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 纯 emoji 截断残片正常提取`() {
        val raw = """{"text":"😂😂","continue":"""
        assertEquals("😂😂", BubbleJsonProtocol.extractTextLenient(raw))
    }

    @Test
    fun `extractTextLenient - 空 text 骨架仍返回空串`() {
        // emoji 放行不影响骨架检测：无任何正文/emoji 的骨架仍归空串
        val raw = """{"text":"","continue":true}"""
        assertEquals("", BubbleJsonProtocol.extractTextLenient(raw))
    }
}
