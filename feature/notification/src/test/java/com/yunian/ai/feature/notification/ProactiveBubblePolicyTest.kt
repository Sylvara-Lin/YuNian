package com.yunian.ai.feature.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主动消息气泡硬上限护栏（真机问题：模型可能失控连发，骚扰强）。
 *
 * 背景：主动消息推送到通知栏/锁屏，与聊天路径（用户正看着屏幕、可「条数不限」）不同，
 * 必须有**有上界**的兜底。软引导见 [ProactiveMessageInstruction]（1~3 条），
 * 本测试锁定代码侧硬上限 [ProactiveBubblePolicy.MAX_BUBBLES] 的有效性。
 */
class ProactiveBubblePolicyTest {

    @Test
    fun `模型输出6行-硬上限生效-实际最多3条且保序`() {
        // 模拟模型失控：一次敲了 6 行（= 6 条气泡信号）
        val sixLines = (1..6).joinToString("\n") { "第${it}条短消息" }

        val bubbles = ProactiveBubblePolicy.splitAndCap(sixLines)

        assertEquals("主动消息气泡数硬上限应为 ${ProactiveBubblePolicy.MAX_BUBBLES}", 3, bubbles.size)
        assertEquals(
            "截断必须保序（保留最前面的 3 条）",
            listOf("第1条短消息", "第2条短消息", "第3条短消息"),
            bubbles,
        )
    }

    @Test
    fun `未超限时原样保留-不截断不丢行`() {
        val threeLines = "第一条\n第二条\n第三条"
        assertEquals(
            "3 行 = 恰好等于上限，必须完整保留",
            listOf("第一条", "第二条", "第三条"),
            ProactiveBubblePolicy.splitAndCap(threeLines),
        )
    }

    @Test
    fun `对已拆好的列表施加cap-保序取前N条`() {
        val many = (1..10).map { "b$it" }
        assertEquals(listOf("b1", "b2", "b3"), ProactiveBubblePolicy.cap(many))
        assertEquals("未超限应原样返回", listOf("x", "y"), ProactiveBubblePolicy.cap(listOf("x", "y")))
    }

    @Test
    fun `空输入与单行输入不报错`() {
        // BubbleTextSplitter 契约：至少返回一个元素（空白输入返回 listOf(text)）。
        assertEquals(listOf("   "), ProactiveBubblePolicy.splitAndCap("   "))
        assertEquals(listOf("只有一条"), ProactiveBubblePolicy.splitAndCap("只有一条"))
    }
}
