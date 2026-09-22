package com.yunian.ai.network

import com.yunian.ai.network.bubble.BubbleJsonProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA 独立验证：气泡协议模式下，畸形 / 截断 JSON 经 [AiService.streamMessage] 的真实后处理路径
 * 后，[BubbleJsonProtocol.extractTextLenient] 是否仍能正确抠出纯文本、不把 JSON 骨架展示给用户。
 *
 * 缺陷（P1）：a5a7d6b 在 [AiService.streamMessage]（约 2594 行）计算了
 * `bubbleMode = BubbleJsonProtocol.isProtocolEnabled(systemPrompt)`，
 * 但其后的后处理调用（约 2613 行）`applyPersonaPostProcessing(raw, sortedHistory)` **未透传**
 * `preserveRaw = bubbleMode`，该变量成为未使用值。结果：`preserveRaw` 恒为 false，
 * 畸形 JSON 先被 `extractDirectReply` 的引号抽取逻辑打碎成「text / 正文 / continue」多行，
 * 再交给 `extractTextLenient`，导致 2eb0556 的兜底失效、JSON 骨架仍会泄漏给用户。
 *
 * 修复：AiService.kt 后处理调用处补 `preserveRaw = bubbleMode`（一行）。
 * 修复前本类第 1 个用例为红（bug 证据）；修复后应全绿。
 */
class QaBubblePreserveRawTest {

    /** 模型流被截断的典型残片（2eb0556 声称要清洗掉、绝不展示给用户的形态）。 */
    private val truncatedJson = """{"text":"你好呀今天过得怎么样","continue":"""

    @Test
    fun `生产路径 - 截断 JSON 经 streamMessage 后处理仍应提取出纯文本`() {
        // 与 AiService.streamMessage 第 2613 行完全一致的调用签名（不传 preserveRaw）。
        val postProcessed = AiPromptBuilder.applyPersonaPostProcessing(truncatedJson, emptyList())
        val delivered = BubbleJsonProtocol.extractTextLenient(postProcessed)

        assertEquals(
            "截断 JSON 经生产后处理路径后，送达文本应为纯正文，不得含 JSON 骨架",
            "你好呀今天过得怎么样",
            delivered,
        )
    }

    @Test
    fun `preserveRaw=true - 同一残片原样保留且可正确提取纯文本`() {
        val postProcessed = AiPromptBuilder.applyPersonaPostProcessing(
            truncatedJson,
            emptyList(),
            preserveRaw = true,
        )
        assertEquals(truncatedJson, postProcessed)
        assertEquals("你好呀今天过得怎么样", BubbleJsonProtocol.extractTextLenient(postProcessed))
    }

    // ==================================================================
    // P0-1 回归用例（深审 2025-xx 发现，工程师修复前应为红，修复后转绿）
    // 缺陷：extractTextLenient 对「空 text / 纯 JSON 残壳」输入清洗后残留标点
    // 或整串骨架，经 ChatGenerationManager 源文本兜底原样落库，用户看到
    // 「,」「{}」垃圾气泡。期望语义：无任何字母/汉字/数字正文时返回空串，
    // 交给上层 aiContent.isBlank() 分支走「API返回空内容」提示。
    // ==================================================================

    @Test
    fun `P0-1 空text协议JSON 应提取为空而非逗号残渣`() {
        // 当前坏行为：剥 continue→剥括号→剥 text 前缀后剩 `"",`，trim('"') 得 ","
        val result = BubbleJsonProtocol.extractTextLenient("""{"text":"","continue":true}""")
        assertEquals("空 text 骨架不得产生标点残渣气泡", "", result)
    }

    @Test
    fun `P0-1 空JSON骨架 应提取为空而非原样返回`() {
        // 当前坏行为：剥空后落 step3 原样返回 "{}"（BubbleJsonProtocolTest 反向锁定，工程师一并修）
        val result = BubbleJsonProtocol.extractTextLenient("{}")
        assertEquals("纯 JSON 残壳不得展示给用户", "", result)
    }

    @Test
    fun `P0-1 无正文残壳标点残渣 应提取为空`() {
        // 残壳 + 尾逗号：无任何字母/汉字/数字正文 → 视为无正文
        val result = BubbleJsonProtocol.extractTextLenient("""{"text":"","continue":false,}""")
        assertEquals("JSON 残壳与标点残渣不得产生气泡", "", result)
    }

    @Test
    fun `P0-1 有正文骨架不受影响 仍提取正文`() {
        // 正确性兜底：修复「残壳返空」不得误伤有正文的骨架
        val result = BubbleJsonProtocol.extractTextLenient("""{"text":"你好","continue":true}""")
        assertEquals("有正文的骨架应照常提取", "你好", result)
    }
}
