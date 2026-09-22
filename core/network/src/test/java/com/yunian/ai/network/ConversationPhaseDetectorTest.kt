package com.yunian.ai.network

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.database.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ConversationPhaseDetectorTest {

    private fun msg(
        content: String,
        isFromUser: Boolean,
        timestamp: Long,
        id: Long = timestamp,
    ) = ChatMessage(
        id = id,
        companionId = 1L,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp,
    )

    @Test
    fun emptyHistory_isOpening() {
        assertEquals(
            ConversationPhase.OPENING,
            ConversationPhaseDetector.detect(emptyList(), nowMs = 1_000_000L),
        )
    }

    @Test
    fun firstUserMessageNoAi_isOpening() {
        val now = 1_700_000_000_000L
        val history = listOf(msg("在吗", true, now - 5_000L))
        assertEquals(
            ConversationPhase.OPENING,
            ConversationPhaseDetector.detect(history, nowMs = now),
        )
    }

    @Test
    fun midSessionAfterAiReply_isTopic() {
        val now = 1_700_000_000_000L
        val history = listOf(
            msg("在吗", true, now - 60_000L),
            msg("在的呀", false, now - 50_000L),
            msg("今天好累", true, now - 10_000L),
        )
        assertEquals(
            ConversationPhase.TOPIC,
            ConversationPhaseDetector.detect(history, nowMs = now),
        )
    }

    @Test
    fun userSaysGoodNight_isClosing() {
        val now = 1_700_000_000_000L
        val history = listOf(
            msg("在吗", true, now - 120_000L),
            msg("在的", false, now - 100_000L),
            msg("晚安，我先睡了", true, now - 5_000L),
        )
        assertEquals(
            ConversationPhase.CLOSING,
            ConversationPhaseDetector.detect(history, nowMs = now),
        )
    }

    @Test
    fun weakAckAlone_isNotClosing() {
        assertFalse(ConversationPhaseDetector.looksLikeClosingIntent("嗯"))
        assertFalse(ConversationPhaseDetector.looksLikeClosingIntent("好"))
        assertFalse(ConversationPhaseDetector.looksLikeClosingIntent("知道了"))
        assertTrue(ConversationPhaseDetector.looksLikeClosingIntent("晚安"))
        assertTrue(ConversationPhaseDetector.looksLikeClosingIntent("我先忙了哈"))
    }

    @Test
    fun longGap_reopensAsOpening() {
        val now = 1_700_000_000_000L
        val gap = ChatConstants.CONVERSATION_REOPEN_GAP_MS + 60_000L
        val history = listOf(
            msg("昨天聊的", true, now - gap - 10_000L),
            msg("嗯嗯", false, now - gap),
            msg("我回来了", true, now - 3_000L),
        )
        assertEquals(
            ConversationPhase.OPENING,
            ConversationPhaseDetector.detect(history, nowMs = now),
        )
    }

    @Test
    fun crossDay_reopensAsOpening() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 28, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.timeInMillis
        val history = listOf(
            msg("昨天说的", true, yesterday),
            msg("好呀", false, yesterday + 60_000L),
            msg("早", true, now - 2_000L),
        )
        assertEquals(
            ConversationPhase.OPENING,
            ConversationPhaseDetector.detect(history, nowMs = now),
        )
    }

    @Test
    fun timeContext_includesPhaseUsage() {
        val opening = AiContextTools.buildCurrentTimeContext(false, ConversationPhase.OPENING)
        val topic = AiContextTools.buildCurrentTimeContext(false, ConversationPhase.TOPIC)
        val closing = AiContextTools.buildCurrentTimeContext(false, ConversationPhase.CLOSING)
        assertTrue(opening.contains("OPENING"))
        assertTrue(topic.contains("TOPIC"))
        assertTrue(closing.contains("CLOSING"))
        assertTrue(topic.contains("禁止") || topic.contains("不要主动"))
        val section = AiContextTools.buildConversationPhaseSection(ConversationPhase.TOPIC)
        assertTrue(section.contains("TOPIC"))
        assertTrue(section.contains("动作限制") || section.contains("单次单动作") || section.contains("单动作"))
        assertTrue(AiContextTools.buildConversationTimingRules().contains("对话时序"))
        assertTrue(
            AiContextTools.buildConversationTimingRules().contains("单动作") ||
                AiContextTools.buildConversationTimingRules().contains("动作"),
        )
        val budget = AiContextTools.buildDeliveryBudgetRules()
        assertTrue(budget.contains("单次单动作") || budget.contains("一个核心社交"))
        assertTrue(budget.contains("镜像") || budget.contains("表层"))
        assertTrue(budget.contains("完整") || budget.contains("残句"))
        assertTrue(budget.contains("过度交付") || budget.contains("打包") || budget.contains("动作") || budget.contains("意图"))
        val openingSection = AiContextTools.buildConversationPhaseSection(ConversationPhase.OPENING)
        val closingSection = AiContextTools.buildConversationPhaseSection(ConversationPhase.CLOSING)
        assertTrue(openingSection.contains("动作限制") || openingSection.contains("1 个"))
        assertTrue(closingSection.contains("道别") || closingSection.contains("收束"))
        assertTrue(AiContextTools.isIdleEmotionVent("今天好累"))
        assertTrue(AiContextTools.isIdleEmotionVent("熬夜使我快乐"))
        assertFalse(AiContextTools.isIdleEmotionVent("那我该怎么办"))
        assertTrue(AiContextTools.isAdviceSeeking("那我该怎么办"))
        val idlePriority = AiContextTools.buildDeliveryBudgetPriority("今天好累")
        assertTrue(idlePriority.contains("单次单动作") || idlePriority.contains("硬约束"))
        assertTrue(idlePriority.contains("禁止") || idlePriority.contains("护理"))
        assertTrue(idlePriority.contains("完整") || idlePriority.contains("残句") || idlePriority.contains("动作"))
        val advicePriority = AiContextTools.buildDeliveryBudgetPriority("那怎么办")
        assertTrue(advicePriority.contains("求方案"))
        val idleEndCap = AiContextTools.buildDeliveryBudgetEndCap("今天好累")
        assertTrue(idleEndCap.contains("动作收束"))
        assertTrue(idleEndCap.contains("完整") || idleEndCap.contains("残句"))
    }
}
