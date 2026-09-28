package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.text.BubbleTextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单聊送达查重 / 拆分行为单测。
 *
 * 说明：[AiResponseFinalizer] 依赖 Android `Application` / Room 仓储，无法在 JVM 单测中直接构造，
 * 故这里对其中被抽出的纯逻辑（[BubbleDedupPlanner] 查重规划 + [BubbleTextSplitter] 拆分）做等价覆盖，
 * 三处关键行为与 finalizer 内实际调用路径一一对应。
 */
class AiResponseFinalizerDedupTest {

    @Test
    fun `重复段被丢弃`() {
        val window = mutableListOf("今天天气真的很不错呀我们出去走走吧")
        val segments = listOf("今天天气真的很不错呀", "换个话题我们聊点别的吧")
        val result = BubbleDedupPlanner.plan(segments, window)
        // 第一段与历史近似重复（较短串 >=10 且被包含）→ 丢弃；第二段保留。
        assertEquals(listOf("换个话题我们聊点别的吧"), result)
    }

    @Test
    fun `本批内互相重复只保留一条`() {
        val window = mutableListOf<String>()
        val text = "这是一个足够长的重复内容示例"
        val result = BubbleDedupPlanner.plan(listOf(text, text), window)
        assertEquals(listOf(text), result)
    }

    @Test
    fun `全部重复时走 FALLBACK_ACKS 兜底`() {
        val dup = "很久没见了我们好好聊聊吧"
        val window = mutableListOf(dup)
        val result = BubbleDedupPlanner.plan(listOf(dup), window)
        assertEquals(1, result.size)
        assertTrue(BubbleDedupPlanner.FALLBACK_ACKS.contains(result[0]))
    }

    @Test
    fun `兜底优先选池内未重复项`() {
        val window = mutableListOf("很久没见了我们好好聊聊吧")
        // 预置池首项为重复，兜底应跳过它选择池内未重复项
        val firstAck = com.yunian.ai.common.text.DedupGuard.normalize(BubbleDedupPlanner.FALLBACK_ACKS.first())
        window.add(firstAck)
        val result = BubbleDedupPlanner.plan(listOf("很久没见了我们好好聊聊吧"), window)
        assertEquals(1, result.size)
        assertFalse(result[0] == BubbleDedupPlanner.FALLBACK_ACKS.first())
    }

    @Test
    fun `空白占位段原样保留不触发兜底`() {
        val window = mutableListOf<String>()
        val result = BubbleDedupPlanner.plan(listOf(""), window)
        assertEquals(listOf(""), result)
        assertTrue(window.isEmpty())
    }

    @Test
    fun `allowParagraphSplit 为 false 时整条不拆`() {
        val text = "第一段。\n\n第二段。\n\n第三段。"
        val result = BubbleTextSplitter.splitForDelivery(text, allowParagraphSplit = false)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `非重复正常回复全部保留`() {
        val window = mutableListOf("昨天的对话内容示例文本")
        val result = BubbleDedupPlanner.plan(listOf("好呀没问题", "那我们晚点见"), window)
        assertEquals(listOf("好呀没问题", "那我们晚点见"), result)
    }
}
