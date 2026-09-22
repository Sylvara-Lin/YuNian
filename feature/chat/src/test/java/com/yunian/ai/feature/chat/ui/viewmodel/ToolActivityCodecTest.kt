package com.yunian.ai.feature.chat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolActivityCodec 单测：工具卡片落库 JSON 的编解码往返一致性。
 *
 * 回归背景（Bug ②）：工具调用过程卡片需要持久化为 [com.yunian.ai.database.model.MessageType.TOOL_ACTIVITY]
 * 消息的 content（JSON 数组）并随消息列表滚动回放，因此编解码必须无损、且对脏数据安全。
 */
class ToolActivityCodecTest {

    @Test
    fun roundTrip_preservesAllFields() {
        val activities = listOf(
            ToolActivity(
                id = 1L,
                toolName = "web_fetch",
                argsSummary = "url=https://raw.githubusercontent.com/a/b/main/SKILL.md",
                status = ToolStatus.DONE,
                resultSummary = "已获取 SKILL.md 正文",
                startedAtMs = 111L,
            ),
            ToolActivity(
                id = 2L,
                toolName = "skill_install",
                argsSummary = "name=\"PDF 处理\"",
                status = ToolStatus.RUNNING,
                resultSummary = null,
                startedAtMs = 222L,
            ),
        )
        val decoded = ToolActivityCodec.decode(ToolActivityCodec.encode(activities))
        assertEquals(activities, decoded)
    }

    @Test
    fun nullResultSummary_isOmittedAndDecodesBackToNull() {
        val activity = ToolActivity(3L, "t", "args", ToolStatus.RUNNING, null, 5L)
        val json = ToolActivityCodec.encode(listOf(activity))
        assertTrue("explicitNulls=false 时不应输出 resultSummary", !json.contains("resultSummary"))
        assertNull(ToolActivityCodec.decode(json).single().resultSummary)
    }

    @Test
    fun argsSummary_withQuotesNewlinesChineseEmoji_survivesRoundTrip() {
        val messy = "参数：\"双引号\" '单引号'\n换行\t制表符 🚀 中文 中文 \\反斜杠\\ [方括号] {花括号}"
        val activity = ToolActivity(
            id = 4L,
            toolName = "t",
            argsSummary = messy,
            status = ToolStatus.FAILED,
            resultSummary = "失败: ${messy.take(20)}",
            startedAtMs = 6L,
        )
        val decoded = ToolActivityCodec.decode(ToolActivityCodec.encode(listOf(activity))).single()
        assertEquals(messy, decoded.argsSummary)
        assertEquals(activity.resultSummary, decoded.resultSummary)
        assertEquals(ToolStatus.FAILED, decoded.status)
    }

    @Test
    fun encode_emptyListProducesJsonArray() {
        assertEquals("[]", ToolActivityCodec.encode(emptyList()))
        assertEquals(emptyList<ToolActivity>(), ToolActivityCodec.decode("[]"))
    }

    @Test
    fun decode_blankReturnsEmptyList() {
        assertEquals(emptyList<ToolActivity>(), ToolActivityCodec.decode(""))
        assertEquals(emptyList<ToolActivity>(), ToolActivityCodec.decode("   "))
    }

    @Test
    fun decode_invalidOrWrongShape_returnsEmptyWithoutThrowing() {
        assertEquals(emptyList<ToolActivity>(), ToolActivityCodec.decode("not json"))
        assertEquals(emptyList<ToolActivity>(), ToolActivityCodec.decode("{\"not\":\"an array\"}"))
        assertEquals(emptyList<ToolActivity>(), ToolActivityCodec.decode("[{\"id\":\"oops\"}]"))
    }

    @Test
    fun encode_usesStableEnumSerialNames() {
        // 落库 status 用的是 @SerialName，稳定性直接决定历史卡片能否正确回放。
        val json = ToolActivityCodec.encode(
            listOf(
                ToolActivity(1L, "a", "x", ToolStatus.RUNNING, null, 1L),
                ToolActivity(2L, "a", "x", ToolStatus.DONE, null, 1L),
                ToolActivity(3L, "a", "x", ToolStatus.FAILED, null, 1L),
            )
        )
        assertTrue(json.contains("\"running\""))
        assertTrue(json.contains("\"done\""))
        assertTrue(json.contains("\"failed\""))
    }

    @Test
    fun decode_ignoresUnknownKeys_forwardCompatible() {
        val forwardCompatible = "[{\"id\":1,\"toolName\":\"t\",\"argsSummary\":\"a\",\"status\":\"done\",\"startedAtMs\":9,\"futureField\":123}]"
        val decoded = ToolActivityCodec.decode(forwardCompatible).single()
        assertEquals("t", decoded.toolName)
        assertEquals(ToolStatus.DONE, decoded.status)
    }
}
