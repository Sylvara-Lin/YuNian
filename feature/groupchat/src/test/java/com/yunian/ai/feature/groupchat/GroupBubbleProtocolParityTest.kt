package com.yunian.ai.feature.groupchat

import com.yunian.ai.network.bubble.BubbleJsonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊接入气泡协议后的「首条解析 + continue 门控」一致性测试。
 *
 * 群聊首条与单聊对齐：协议遵守（有效 JSON）→ 取 text；未遵守（非 JSON）→ 回落原文；
 * 仅当 continue=true 才续接后续气泡。
 */
class GroupBubbleProtocolParityTest {

    /** 与 GroupChatViewModel.generateIsolatedAiReplyBubbles 中的门控表达式保持一致。 */
    private fun shouldChain(rawFirst: String): Boolean =
        BubbleJsonProtocol.parseStrict(rawFirst)?.continueChat == true

    @Test
    fun `首条协议 JSON 取 text 字段`() {
        val raw = """{"text":"今晚一起吃饭吗？","continue":true}"""
        val reply = BubbleJsonProtocol.parseStrict(raw)
        assertNotNull(reply)
        assertEquals("今晚一起吃饭吗？", reply!!.text)
        assertTrue(shouldChain(raw))
    }

    @Test
    fun `continue false 不续接后续气泡`() {
        val raw = """{"text":"说完了。","continue":false}"""
        assertFalse(shouldChain(raw))
    }

    @Test
    fun `非 JSON 首条回落原文且不续接`() {
        val raw = "这是一条没有遵守协议的自然语言回复。"
        assertNull(BubbleJsonProtocol.parseStrict(raw))
        assertEquals(raw, BubbleJsonProtocol.parseStrict(raw)?.text ?: raw)
        assertFalse(shouldChain(raw))
    }

    @Test
    fun `代码块包裹的 JSON 仍可解析并续接`() {
        val raw = """
            ```json
            {"text":"第一条","continue":true}
            ```
        """.trimIndent()
        assertEquals("第一条", BubbleJsonProtocol.parseStrict(raw)?.text)
        assertTrue(shouldChain(raw))
    }

    @Test
    fun `系统规则包含协议标记`() {
        assertTrue(BubbleJsonProtocol.isProtocolEnabled(BubbleJsonProtocol.systemRules()))
        assertTrue(BubbleJsonProtocol.systemRules().contains(BubbleJsonProtocol.PROTOCOL_MARKER))
    }
}
