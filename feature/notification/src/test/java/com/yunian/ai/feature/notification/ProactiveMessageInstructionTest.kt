package com.yunian.ai.feature.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息指令多气泡护栏（真机问题：AI 主动找用户时永远只发一句话）。
 *
 * 锁定 [ProactiveMessageInstruction] 的两条不变量：
 * 1. 指令必须包含多气泡引导（「换行即下一条」「条数不限」），且不再含旧的单条硬限制；
 * 2. 保留 [ProactiveMessageInstruction.NO_PROACTIVE_MARKER]「不打扰」语义（决定沉默时只输出 marker）。
 */
class ProactiveMessageInstructionTest {

    @Test
    fun `问候指令包含多气泡引导且无单条硬限制`() {
        val text = ProactiveMessageInstruction.build(
            companionName = "小云",
            allowLateNightMessage = true,
            followUp = false,
        )

        assertTrue("必须含「换行即下一条」", text.contains("换行即下一条"))
        assertTrue("必须含「条数不限」", text.contains("条数不限"))
        assertTrue("必须含角色名", text.contains("以「小云」的身份") || text.contains("以小云的身份"))
        assertTrue("marker 语义保留", text.contains(ProactiveMessageInstruction.NO_PROACTIVE_MARKER))
        assertFalse("不得再写死「优先 1 条」", text.contains("优先 1 条"))
        assertFalse("不得再写死「只发 1 条」", text.contains("只发 1 条"))
        assertFalse("不得再写死「不要拆成很多短句连发」", text.contains("不要拆成很多短句连发"))
    }

    @Test
    fun `追问指令包含多气泡引导且无单条与字数硬限制`() {
        val text = ProactiveMessageInstruction.build(
            companionName = "小云",
            allowLateNightMessage = true,
            followUp = true,
        )

        assertTrue("必须含「换行即下一条」", text.contains("换行即下一条"))
        assertTrue("marker 语义保留", text.contains(ProactiveMessageInstruction.NO_PROACTIVE_MARKER))
        assertFalse("不得再写死「只发 1 条」", text.contains("只发 1 条"))
        assertFalse("不得再写死字数区间「10~30」", text.contains("10~30"))
        assertTrue("不重复纪律保留", text.contains("不要重复上一条消息的内容"))
        assertTrue("不堆叠纪律保留", text.contains("不要堆叠同一句话"))
    }

    @Test
    fun `免打扰时段约束随开关切换`() {
        val dnd = ProactiveMessageInstruction.build("小云", allowLateNightMessage = false, followUp = false)
        val normal = ProactiveMessageInstruction.build("小云", allowLateNightMessage = true, followUp = false)

        assertTrue("免打扰时段应追加约束", dnd.contains("免打扰时段"))
        assertFalse("允许时段不应出现免打扰约束", normal.contains("免打扰时段"))
    }

    @Test
    fun `marker 与旧路径同值`() {
        assertTrue(ProactiveMessageInstruction.NO_PROACTIVE_MARKER == "[NO_PROACTIVE]")
    }
}
