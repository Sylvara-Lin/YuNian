package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.text.BubbleTextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScriptTurnStripper] 单测：
 * - 覆盖用户上报的两个真实缺陷样例（逐字断言）；
 * - 锁死误杀反例（正常引用、引号内复述、AI 名字开头、无标记回复等）。
 */
class ScriptTurnStripperTest {

    private val aiName = "苏晚"

    // ── 真实缺陷样例（用户逐字上报，必须原样通过） ──

    @Test
    fun `real case 1 - multi-turn script inside one message is truncated at user turn`() {
        val text = "再陪我赖一会儿嘛......就一小会儿 ～ ｜ 用户：我有点想你了 ｜ 苏晚：（一听这话，原本还迷糊的脑子瞬间清醒了几分，抬眼望向你，眼睛亮晶晶的）"

        val result = ScriptTurnStripper.strip(text, aiName = aiName)

        assertEquals("再陪我赖一会儿嘛......就一小会儿 ～", result)
    }

    @Test
    fun `real case 2 - reply starting with user turn salvages own turn`() {
        val text = "你：老婆，我有点想你了 ｜ 苏晚：（听到你这句话，心里又软又软地化开……只是轻轻应了一声）"

        val result = ScriptTurnStripper.strip(text, aiName = aiName)

        assertEquals("（听到你这句话，心里又软又软地化开……只是轻轻应了一声）", result)
    }

    @Test
    fun `real case 2 without ai name - whole script is dropped`() {
        val text = "你：老婆，我有点想你了 ｜ 苏晚：（听到你这句话，心里又软又软地化开……只是轻轻应了一声）"

        val result = ScriptTurnStripper.strip(text, aiName = null)

        assertEquals("", result)
    }

    // ── 截断行为补充 ──

    @Test
    fun `multi-line script is truncated at user turn line`() {
        val text = "好呀\n用户：那我先去洗澡啦\n苏晚：好的呀"

        assertEquals("好呀", ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `half-width pipe and half-width colon markers are also truncated`() {
        assertEquals("好呀", ScriptTurnStripper.strip("好呀 | 用户:那我去洗澡了", aiName = aiName))
    }

    @Test
    fun `whitespace between separator and marker is tolerated`() {
        assertEquals("嗯嗯", ScriptTurnStripper.strip("嗯嗯 ｜  用户：睡吧", aiName = aiName))
    }

    @Test
    fun `self name prefix is stripped on its own`() {
        assertEquals("（歪头）怎么啦", ScriptTurnStripper.strip("苏晚：（歪头）怎么啦", aiName = aiName))
    }

    @Test
    fun `self name prefix with half-width colon is stripped`() {
        assertEquals("（笑）来啦", ScriptTurnStripper.strip("苏晚:（笑）来啦", aiName = aiName))
    }

    @Test
    fun `self name prefix is stripped after truncation`() {
        assertEquals("好呀", ScriptTurnStripper.strip("苏晚：好呀 ｜ 用户：抱抱", aiName = aiName))
    }

    @Test
    fun `user marker at start without salvageable own turn returns empty`() {
        assertEquals("", ScriptTurnStripper.strip("用户：老婆我想你了", aiName = aiName))
    }

    @Test
    fun `blank input is returned as is`() {
        assertEquals("", ScriptTurnStripper.strip("", aiName = aiName))
        assertEquals("  ", ScriptTurnStripper.strip("  ", aiName = aiName))
    }

    // ── 误杀反例（正常回复必须原样保留） ──

    @Test
    fun `normal reply without markers is untouched`() {
        val text = "今天也好想你呀，早点回来陪我～"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `normal reference to user words is not truncated`() {
        val text = "你刚说想我了，我这边心跳都快了一拍"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `quoted restatement starting with quote char is not truncated`() {
        val text = "「用户：晚安」——你昨晚就是这样跟我道别的呀"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `colon after other words is not a turn marker`() {
        val text = "做你自己：这才是我最喜欢你的地方"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `in-quote user turn mid-line is not truncated`() {
        val text = "我以前只会干巴巴地说「用户：晚安」，现在学乖啦"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `reply mentioning user colon inside sentence is untouched`() {
        val text = "你说过：「用户永远是第一位」，我一直记着呢"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `line continuation containing ni without marker stays`() {
        val text = "谢谢你\n陪我熬过这么多个深夜"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    @Test
    fun `other name prefix does not trigger self prefix strip`() {
        val text = "小恋：（假装生气）哼"

        assertEquals(text, ScriptTurnStripper.strip(text, aiName = aiName))
    }

    // ── 管线级验证（端到端等价）：复现 AiResponseFinalizer.deliverResponse 的调用序列
    //    （ScriptTurnStripper.strip → trimIdleEmotionOverDelivery(透传) → BubbleTextSplitter
    //    → 落库），证明「模型输出脚本」场景下，落库/显示的文本不含用户回合。 ──

    @Test
    fun `pipeline - real case 1 delivered bubbles contain no user turn`() {
        val aiContent = "再陪我赖一会儿嘛......就一小会儿 ～ ｜ 用户：我有点想你了 ｜ 苏晚：（一听这话，原本还迷糊的脑子瞬间清醒了几分，抬眼望向你，眼睛亮晶晶的）"

        // deliverResponse 内的实际顺序：先剥离，再进分气泡管线，落库的是 segments
        val sanitized = ScriptTurnStripper.strip(aiContent, aiName = aiName)
        val segments = BubbleTextSplitter.splitForDelivery(sanitized, allowParagraphSplit = true)
        val persisted = segments.joinToString("\n")

        assertEquals("再陪我赖一会儿嘛......就一小会儿 ～", persisted)
        assertFalse(persisted.contains("用户："))
        assertFalse(persisted.contains("苏晚："))
    }

    @Test
    fun `pipeline - real case 2 delivered bubble is the salvaged own turn`() {
        val aiContent = "你：老婆，我有点想你了 ｜ 苏晚：（听到你这句话，心里又软又软地化开……只是轻轻应了一声）"

        val sanitized = ScriptTurnStripper.strip(aiContent, aiName = aiName)
        val segments = BubbleTextSplitter.splitForDelivery(sanitized, allowParagraphSplit = true)
        val persisted = segments.joinToString("\n")

        assertEquals("（听到你这句话，心里又软又软地化开……只是轻轻应了一声）", persisted)
        assertFalse(persisted.contains("你："))
    }

    @Test
    fun `pipeline - normal reply passes through unchanged`() {
        val aiContent = "听到你这么说我也很开心，晚安～"

        val sanitized = ScriptTurnStripper.strip(aiContent, aiName = aiName)
        val segments = BubbleTextSplitter.splitForDelivery(sanitized, allowParagraphSplit = true)

        assertTrue(segments.contains(aiContent))
    }
}
