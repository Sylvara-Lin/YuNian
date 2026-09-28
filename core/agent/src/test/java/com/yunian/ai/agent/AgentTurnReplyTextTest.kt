package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class AgentTurnReplyTextTest {
    @Test fun toolBubbleSurvivesEmptyFinalText() {
        assertEquals("你好", AgentTurnReplyText.resolve(listOf(AgentEvent("bubble", "你好", "")), "", "max_rounds"))
    }
    @Test fun finalTextIsNotDuplicated() {
        assertEquals("你好", AgentTurnReplyText.resolve(listOf(AgentEvent("bubble", "你好", "")), "你好", "completed"))
    }
    @Test fun emptyErrorUsesCallerFallback() {
        assertNull(AgentTurnReplyText.resolve(emptyList(), "", "error"))
    }
    @Test fun confirmationNeverSilentlyDisappears() {
        assertTrue(AgentTurnReplyText.resolve(emptyList(), "", "confirm_pending")!!.contains("确认"))
    }
    @Test fun stickerHasVisibleTextFallback() {
        assertEquals("[开心]", AgentTurnReplyText.resolve(listOf(AgentEvent("sticker", "开心", "")), "", "completed"))
    }
}
