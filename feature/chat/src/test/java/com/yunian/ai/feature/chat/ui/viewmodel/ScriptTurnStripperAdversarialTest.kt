package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.ScriptTurnStripper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA 对抗测试（独立于工程师的 ScriptTurnStripperTest）：
 * 目标是证伪——集中攻击误杀面、挽救逻辑边界、aiName 异常形态与格式变体。
 */
class ScriptTurnStripperAdversarialTest {

    private val aiName = "苏晚"

    // ── A. 正常引用用户的话（行中/行首均不得截断） ──

    @Test
    fun `A1 - quoted user words with shuo-colon is kept`() {
        val t = "你上次说：想吃火锅，我一直记着呢，周末去呀"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    @Test
    fun `A2 - quoted user words inside single quotes mid-line is kept`() {
        val t = "我记得你说'晚安'呀，怎么今天这么早"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    @Test
    fun `A3 - corner-bracket quote at line start is kept`() {
        val t = "「用户：晚安」——你昨晚就是这样跟我道别的"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    @Test
    fun `A4 - greeting with name ending in ni is kept`() {
        val t = "亲爱的你：晚上好呀"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    @Test
    fun `A5 - ni-hao colon is not a marker`() {
        val t = "你好：今天想听你说说话"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    @Test
    fun `A6 - reference without separator before marker word is kept`() {
        val t = "好呀用户：晚安这句我收到了"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    // ── B. 格式变体与边界 ──

    @Test
    fun `B1 - crlf line ending is truncated correctly`() {
        assertEquals("好呀", ScriptTurnStripper.strip("好呀\r\n用户：晚安", aiName))
    }

    @Test
    fun `B2 - full-width space separator and half-width colon is truncated`() {
        assertEquals("嗯嗯", ScriptTurnStripper.strip("嗯嗯　｜　用户:好的", aiName))
    }

    @Test
    fun `B3 - space before colon variant is NOT truncated (documented miss)`() {
        val t = "嗯嗯\n用户 : 晚安"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    @Test
    fun `B4 - ni colon with half-width colon and spaces after is truncated`() {
        assertEquals("嗯嗯", ScriptTurnStripper.strip("嗯嗯\n你:  睡吧", aiName))
    }

    @Test
    fun `B5 - multi-turn nested script truncates at first user turn`() {
        assertEquals(
            "xxx",
            ScriptTurnStripper.strip("苏晚：xxx ｜ 用户：yyy ｜ 苏晚：zzz", aiName)
        )
    }

    @Test
    fun `B6 - newline separated nested script salvages first own turn`() {
        assertEquals(
            "我先说",
            ScriptTurnStripper.strip("你：在吗\n（苏晚想了想）\n苏晚：我先说", aiName)
        )
    }

    @Test
    fun `B7 - text without any boundary before user colon is kept`() {
        val t = "我跟你说：今天天气很好"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }

    // ── C. aiName 异常形态（挽救 / 自报家门剥离的安全性） ──

    @Test
    fun `C1 - null aiName keeps self-prefixed first party text after truncation`() {
        assertEquals(
            "苏晚：好呀",
            ScriptTurnStripper.strip("苏晚：好呀 ｜ 用户：抱抱", null)
        )
    }

    @Test
    fun `C2 - empty aiName behaves like null`() {
        assertEquals(
            "苏晚：好呀",
            ScriptTurnStripper.strip("苏晚：好呀 ｜ 用户：抱抱", "  ")
        )
    }

    @Test
    fun `C3 - aiName with regex metachars does not crash nor false-strip`() {
        val weird = "(小恋)*"
        val t = "$weird：好呀 ｜ 用户：抱抱"
        // Regex.escape 保证按字面匹配：前缀被正确剥掉且不抛正则异常
        val result = ScriptTurnStripper.strip(t, weird)
        assertEquals("好呀", result)
        // 正文里出现同名（非行首前缀形态）不受影响
        val t2 = "今天聊到$weird：这个梗，笑死"
        assertEquals(t2, ScriptTurnStripper.strip(t2, weird))
    }

    @Test
    fun `C4 - aiName with regex metachars salvage does not crash`() {
        val weird = "A.B+C"
        val result = ScriptTurnStripper.strip("你：在吗 ｜ $weird：在", weird)
        assertEquals("在", result)
    }

    @Test
    fun `C5 - blank reply stays blank with aiName`() {
        assertEquals("", ScriptTurnStripper.strip("", aiName))
    }

    // ── D. 已知接受的设计取舍（锁死当前行为，防回归时无感知漂移） ──

    @Test
    fun `D1 - reply starting with ni colon without salvageable turn is dropped (defect sample 2 shape)`() {
        // 这正是缺陷样例2形态：宁可丢弃也不把编造的用户话当 AI 说的
        assertEquals("", ScriptTurnStripper.strip("你：叫我一声我就答应", aiName))
    }

    @Test
    fun `D2 - English script turns are NOT stripped (documented gap)`() {
        val t = "Sure! | User: I miss you | AI: aww"
        assertEquals(t, ScriptTurnStripper.strip(t, aiName))
    }
}
