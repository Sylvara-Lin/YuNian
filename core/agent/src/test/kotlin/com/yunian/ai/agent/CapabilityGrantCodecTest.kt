package com.yunian.ai.agent

import com.yunian.ai.domain.CapabilityGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权文本编解码契约测试。
 *
 * 核心钉子：**解析失败必须丢弃该行而不是抛异常**。授权表是「放松 / 收紧确认门」的开关，
 * 坏数据只能让它更保守（视为无决定 ⇒ 回到工具自身默认），绝不能把对话 / 启动链路带崩。
 *
 * 存储格式（P2-2d 起，**不含通道维度**）：每条决定一行 `companionId|toolName|0或1`，
 * `*` 表示通配（对所有伴侣生效）。
 */
class CapabilityGrantCodecTest {

    private val tapAllowed = CapabilityGrant(companionId = 42L, toolName = "screen_tap", allowed = true)
    private val clickTextWildcardAllowed =
        CapabilityGrant(companionId = null, toolName = "screen_click_text", allowed = true)
    private val tapDeniedFor7 = CapabilityGrant(companionId = 7L, toolName = "automation_create", allowed = false)

    // ── 编码 ──

    @Test
    fun `编码格式为每条决定一行、竖线分隔三字段`() {
        assertEquals("42|screen_tap|1", CapabilityGrantCodec.encode(listOf(tapAllowed)))
        assertEquals("*|screen_click_text|1", CapabilityGrantCodec.encode(listOf(clickTextWildcardAllowed)))
        assertEquals(
            "42|screen_tap|1\n*|screen_click_text|1",
            CapabilityGrantCodec.encode(listOf(tapAllowed, clickTextWildcardAllowed)),
        )
    }

    @Test
    fun `allowed = false 编码为 0`() {
        assertEquals("7|automation_create|0", CapabilityGrantCodec.encode(listOf(tapDeniedFor7)))
    }

    @Test
    fun `空集合编码为空字符串、无结尾换行`() {
        assertEquals("", CapabilityGrantCodec.encode(emptyList()))
        assertTrue(!CapabilityGrantCodec.encode(listOf(tapAllowed)).endsWith("\n"))
    }

    @Test
    fun `编码跳过无法安全编码的决定（空白工具名与含分隔符）`() {
        val bad = listOf(
            CapabilityGrant(1L, "  ", allowed = true),
            CapabilityGrant(1L, "screen|tap", allowed = true),
            CapabilityGrant(1L, "", allowed = false),
        )
        assertEquals("宁可少写也不写脏数据", "", CapabilityGrantCodec.encode(bad))
        assertEquals(
            "混入坏记录时好记录照常写出",
            "1|screen_tap|1",
            CapabilityGrantCodec.encode(bad + CapabilityGrant(1L, "screen_tap", allowed = true)),
        )
    }

    // ── 解码：正常路径 ──

    @Test
    fun `编解码往返稳定（含通配、allowed = false、保序、去重）`() {
        val decisions = listOf(tapAllowed, clickTextWildcardAllowed, tapDeniedFor7)
        val encoded = CapabilityGrantCodec.encode(decisions)
        val decoded = CapabilityGrantCodec.decode(encoded)
        assertEquals(0, decoded.droppedLineCount)
        assertEquals(decisions, decoded.decisions)

        val duplicated = CapabilityGrantCodec.decode(encoded + "\n" + encoded)
        assertEquals("重复出现只保留一条", decisions, duplicated.decisions)
        assertEquals(0, duplicated.droppedLineCount)
    }

    @Test
    fun `同一键重复出现时后者覆盖前者`() {
        val decoded = CapabilityGrantCodec.decode("42|screen_tap|1\n42|screen_tap|0")
        assertEquals(
            listOf(CapabilityGrant(42L, "screen_tap", allowed = false)),
            decoded.decisions,
        )
        assertEquals(0, decoded.droppedLineCount)
    }

    @Test
    fun `null 与空串视为没有任何决定`() {
        for (raw in listOf(null, "", "   ", "\n\n")) {
            val decoded = CapabilityGrantCodec.decode(raw)
            assertEquals("raw=<$raw>", emptyList<CapabilityGrant>(), decoded.decisions)
            assertEquals("空行不算坏行", 0, decoded.droppedLineCount)
        }
    }

    @Test
    fun `容忍 CRLF、行首尾空白与只有部分行合法`() {
        val raw = " 42|screen_tap|1 \r\n\r\n\t*|screen_click_text|1\r\n"
        val decoded = CapabilityGrantCodec.decode(raw)
        assertEquals(listOf(tapAllowed, clickTextWildcardAllowed), decoded.decisions)
        assertEquals(0, decoded.droppedLineCount)
    }

    // ── 解码：坏行丢弃（本任务硬约束） ──

    @Test
    fun `坏行逐条丢弃且绝不抛异常`() {
        val badLines = listOf(
            "abc",                          // 字段个数不足
            "1|screen_tap",                 // 字段个数不足
            "1|screen_tap|1|extra",         // 字段个数过多
            "not-a-long|screen_tap|1",      // companionId 既不是 * 也不是 Long
            "1||1",                         // 工具名为空
            "|screen_tap|1",                // companionId 为空
            "*|screen_tap",                 // 只有通配符 + 工具名
            "1.5|screen_tap|1",             // 非整数 companionId
            "1|screen_tap|2",               // 第三字段不是 0 / 1
            "1|screen_tap|true",            // 第三字段是布尔字面量而非 0 / 1
            "1|screen_tap|",                // 第三字段为空
        )
        for (line in badLines) {
            val decoded = CapabilityGrantCodec.decode(line)
            assertEquals("坏行必须被丢弃：<$line>", emptyList<CapabilityGrant>(), decoded.decisions)
            assertEquals("坏行必须计数：<$line>", 1, decoded.droppedLineCount)
        }
    }

    @Test
    fun `旧的三段式行（含通道维度）整体按坏行丢弃 ⇒ 等同无决定`() {
        val legacy = listOf(
            "42|qqbot|screen_tap",
            "*|wechat|screen_click_text",
            "1|app.chat|automation_create",
        )
        for (line in legacy) {
            val decoded = CapabilityGrantCodec.decode(line)
            assertEquals(
                "通道维度版本从未发布，旧行不得被误解成新格式：<$line>",
                emptyList<CapabilityGrant>(),
                decoded.decisions,
            )
            assertEquals(1, decoded.droppedLineCount)
        }
    }

    @Test
    fun `混入坏行时好行照常返回、坏行只被丢弃`() {
        val raw = listOf(
            "42|screen_tap|1",
            "THIS IS GARBAGE",
            "1||broken_tool",
            "*|screen_click_text|1",
            "1|screen_tap|1|with|pipes",
        ).joinToString("\n")
        val decoded = CapabilityGrantCodec.decode(raw)
        assertEquals(listOf(tapAllowed, clickTextWildcardAllowed), decoded.decisions)
        assertEquals("3 条坏行全部计数", 3, decoded.droppedLineCount)
    }

    @Test
    fun `任意垃圾输入都不抛异常（fuzz 兜底）`() {
        val pool = listOf("|", "||", "||||", "\u0000", "🙂", "-1|t|1", "+1|t|1", "9999999999999999999999|t|1", "1|t|1\u0000", " \t ")
        for (a in pool) {
            for (b in pool) {
                val raw = a + b
                val decoded = CapabilityGrantCodec.decode(raw)
                assertTrue("结果必须非空判断不炸：<$raw>", decoded.droppedLineCount >= 0)
            }
        }
    }

    @Test
    fun `负号与超长数字按契约处理`() {
        assertEquals(
            "负 companionId 是可解析的 Long（不视为坏行）",
            listOf(CapabilityGrant(-1L, "t", allowed = true)),
            CapabilityGrantCodec.decode("-1|t|1").decisions,
        )
        val overflow = CapabilityGrantCodec.decode("9999999999999999999999|t|1")
        assertEquals("溢出 Long 视为坏行", emptyList<CapabilityGrant>(), overflow.decisions)
        assertEquals(1, overflow.droppedLineCount)
    }

    @Test
    fun `通配符常量与字段常量对外可见（供存储与测试共用）`() {
        assertEquals("*", CapabilityGrantCodec.WILDCARD_COMPANION)
        assertEquals("1", CapabilityGrantCodec.ALLOWED_TRUE)
        assertEquals("0", CapabilityGrantCodec.ALLOWED_FALSE)
        assertEquals('|', CapabilityGrantCodec.FIELD_SEPARATOR)
    }
}
