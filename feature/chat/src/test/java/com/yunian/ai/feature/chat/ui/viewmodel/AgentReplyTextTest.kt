package com.yunian.ai.feature.chat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReplyTextTest {
    @Test
    fun `image prompt only native bubble remains visible`() {
        val text = AgentReplyText.forDisplay("[[生图: 雨中的白猫]]", imageGenEnabled = true)

        assertEquals("（正在为你配图…）", text)
        assertFalse(text.contains("白猫"))
    }

    @Test
    fun `native bubble keeps dialogue and strips image description`() {
        val text = AgentReplyText.forDisplay("好呀，马上画给你\n[[生图: 雨中的白猫]]", true)

        assertEquals("好呀，马上画给你", text.trim())
        assertFalse(text.contains("白猫"))
    }

    @Test
    fun `empty reply is not converted into a successful image reply`() {
        assertTrue(AgentReplyText.forDisplay("  ", true).isBlank())
    }

    @Test
    fun `disabled image generation and ordinary text retain original behavior`() {
        assertEquals("[[生图: 白猫]]", AgentReplyText.forDisplay("[[生图: 白猫]]", false))
        assertEquals("我在呢", AgentReplyText.forDisplay("我在呢", true))
    }
}
