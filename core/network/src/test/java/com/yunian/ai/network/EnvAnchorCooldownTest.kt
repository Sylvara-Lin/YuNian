package com.yunian.ai.network

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.EnvAnchorCooldown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvAnchorCooldownTest {

    @Test
    fun neverAnchored_isNotCoolingDown() {
        assertFalse(EnvAnchorCooldown.isCoolingDown(lastAtMs = 0L, nowMs = 1_000_000L))
        assertFalse(EnvAnchorCooldown.isCoolingDown(lastAtMs = -1L, nowMs = 1_000_000L))
    }

    @Test
    fun withinCooldownWindow_isCoolingDown() {
        val now = 1_700_000_000_000L
        val last = now - (ChatConstants.ENV_ANCHOR_COOLDOWN_MS / 2)
        assertTrue(EnvAnchorCooldown.isCoolingDown(last, now))
    }

    @Test
    fun afterCooldownWindow_isNotCoolingDown() {
        val now = 1_700_000_000_000L
        val last = now - ChatConstants.ENV_ANCHOR_COOLDOWN_MS - 1L
        assertFalse(EnvAnchorCooldown.isCoolingDown(last, now))
    }

    @Test
    fun looksLikeEnvCare_detectsSleepEatHome() {
        assertTrue(EnvAnchorCooldown.looksLikeEnvCare("这么晚了还不睡？"))
        assertTrue(EnvAnchorCooldown.looksLikeEnvCare("吃饭了没呀"))
        assertTrue(EnvAnchorCooldown.looksLikeEnvCare("到家了吗"))
        assertTrue(EnvAnchorCooldown.looksLikeEnvCare("今天降温记得加衣服"))
        assertFalse(EnvAnchorCooldown.looksLikeEnvCare("今天那部剧好看吗"))
        assertFalse(EnvAnchorCooldown.looksLikeEnvCare(""))
        assertFalse(EnvAnchorCooldown.looksLikeEnvCare("   "))
    }

    @Test
    fun recentAiHasEnvCare_respectsLookback() {
        val texts = listOf(
            "今天那部剧好看吗",
            "嗯嗯",
            "这么晚了还不睡？",
            "哈哈",
        )
        assertTrue(EnvAnchorCooldown.recentAiHasEnvCare(texts, lookback = 4))
        assertFalse(EnvAnchorCooldown.recentAiHasEnvCare(texts.take(2), lookback = 2))
    }

    @Test
    fun allowEnvAnchor_falseWhenCoolingOrRecentCare() {
        val now = 1_700_000_000_000L
        assertTrue(
            EnvAnchorCooldown.allowEnvAnchor(
                lastAtMs = 0L,
                recentAiTexts = listOf("今天那部剧好看吗"),
                nowMs = now,
            ),
        )
        assertFalse(
            EnvAnchorCooldown.allowEnvAnchor(
                lastAtMs = now - 1_000L,
                recentAiTexts = listOf("今天那部剧好看吗"),
                nowMs = now,
            ),
        )
        assertFalse(
            EnvAnchorCooldown.allowEnvAnchor(
                lastAtMs = 0L,
                recentAiTexts = listOf("记得吃饭哦"),
                nowMs = now,
            ),
        )
    }

    @Test
    fun cooldownDirective_emptyWhenAllowed() {
        assertEquals("", EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor = true))
        val ban = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor = false)
        assertTrue(ban.contains("环境关心冷却中"))
        assertTrue(ban.contains("禁止主动提时间"))
    }

    @Test
    fun proactiveEnvPolicy_switchesByAllowFlag() {
        val allowed = EnvAnchorCooldown.buildProactiveEnvPolicy(true)
        val banned = EnvAnchorCooldown.buildProactiveEnvPolicy(false)
        assertTrue(allowed.contains("最多轻提一次"))
        assertTrue(banned.contains("冷却中") || banned.contains("禁止再提"))
    }
}
