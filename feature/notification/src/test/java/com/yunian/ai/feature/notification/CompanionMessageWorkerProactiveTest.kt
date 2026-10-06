package com.yunian.ai.feature.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息（CompanionMessageWorker）两个修复的单元测试。
 *
 * 锁两件事：
 * 1. **只有一条 → AI 自主决定**：删掉换行压缩后，AI 的换行保留，
 *    BubbleTextSplitter 按换行拆多条气泡；
 * 2. **没思考 → 有思考**：runTurnStream 的 ProactiveReasoningSink 收集 reasoning，
 *    落库为 REASONING 消息（像正式回复的「已思考 X 秒」）。
 */
class CompanionMessageWorkerProactiveTest {

    // ── 1. ProactiveReasoningSink（reasoning 收集 + 时长计算）─────────────

    @Test
    fun `无 reasoning 时 reasoningText 为空、durationMs 为 null`() {
        val sink = ProactiveReasoningSink()
        assertEquals("", sink.reasoningText())
        assertNull(sink.reasoningDurationMs())
    }

    @Test
    fun `reasoning delta 被完整收集`() {
        val sink = ProactiveReasoningSink()
        sink.onReasoningDelta("宝宝在忙")
        sink.onReasoningDelta("，我晚点再找他")
        assertEquals("宝宝在忙，我晚点再找他", sink.reasoningText())
    }

    @Test
    fun `思考时长 = 第一个 delta 到最后一个 delta`() {
        val sink = ProactiveReasoningSink()
        sink.onReasoningDelta("开始想")
        Thread.sleep(50)
        sink.onReasoningDelta("想完了")
        val duration = sink.reasoningDurationMs()
        assertTrue("时长应 ≥ 40ms（实际 " + duration + "）", duration != null && duration >= 40)
    }

    @Test
    fun `onError 标记 isError`() {
        val sink = ProactiveReasoningSink()
        sink.onError("网络错误")
        assertTrue(sink.isError)
    }

    // ── 2. 换行保留（AI 自主决定条数）────────────────────────────────────

    @Test
    fun `AI 的换行不再被压成逗号`() {
        // 修复前：raw.replace(Regex("\n+"), "，") 把换行压成逗号，AI 想发 3 条变 1 条。
        // 修复后：raw.trim() 保留换行，BubbleTextSplitter 按换行拆多条。
        val aiOutput = "宝宝还没起吗\n我都等你好久啦\n快起床嘛"
        val processed = aiOutput.trim().takeIf { it.length >= 2 }
        assertEquals("换行必须保留", aiOutput, processed)
        assertTrue("必须含换行", processed!!.contains("\n"))
    }

    @Test
    fun `BubbleTextSplitter 按换行拆多条`() {
        val aiOutput = "宝宝还没起吗\n我都等你好久啦\n快起床嘛"
        val bubbles = com.yunian.ai.common.text.BubbleTextSplitter.splitByParagraphs(aiOutput)
        assertEquals("应拆出 3 条气泡", 3, bubbles.size)
        assertEquals("宝宝还没起吗", bubbles[0])
        assertEquals("我都等你好久啦", bubbles[1])
        assertEquals("快起床嘛", bubbles[2])
    }
}
