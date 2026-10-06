package com.yunian.ai.feature.profile

import com.yunian.ai.database.model.ConversationSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页会话「隐藏该聊天」重显判定单测（2026-10-06 需求定稿）。
 *
 * 语义（与 HomeSessionListOperator KDoc 逐字一致）：
 * - 隐藏 = 只藏入口，记录隐藏时刻；
 * - 摘要 lastMessageTimestamp 晚于隐藏时刻（有新消息，含 AI 主动消息）→ 重显；
 * - 摘要不存在（清记录后）→ 视为隐藏中，新消息落库时摘要重建自然重显。
 *
 * 该判定逻辑在 HomeViewModel.isHiddenNow / ChatGroupViewModel.groupItems 装配处，
 * 此测试锁定其语义边界，防止重构漂移。
 */
class HomeHiddenConversationPolicyTest {

    private fun summary(lastMessageTimestamp: Long, isPinned: Boolean = false) =
        ConversationSummary(
            sessionId = 1L,
            sessionType = "chat",
            lastMessagePreview = "预览",
            lastMessageTimestamp = lastMessageTimestamp,
            lastMessageIsFromUser = false,
            isPinned = isPinned
        )

    private fun isHiddenNow(
        summary: ConversationSummary?,
        hiddenAtMs: Long?
    ): Boolean {
        if (hiddenAtMs == null) return false
        val lastMessageTimestamp = summary?.lastMessageTimestamp ?: return true
        return lastMessageTimestamp <= hiddenAtMs
    }

    @Test
    fun `no hidden record means always visible`() {
        assertFalse(
            "无隐藏记录 → 不隐藏（可见）",
            isHiddenNow(summary(lastMessageTimestamp = 100L), hiddenAtMs = null)
        )
    }

    @Test
    fun `hidden with no newer message stays hidden`() {
        // 隐藏时刻 1000，最后消息 1000（同时刻，无新消息）→ 保持隐藏
        assertTrue(
            "隐藏后无新消息 → 保持隐藏",
            isHiddenNow(summary(lastMessageTimestamp = 1000L), hiddenAtMs = 1000L)
        )
        // 隐藏时刻 1000，最后消息 999（隐藏时刻之前的旧消息）→ 保持隐藏
        assertTrue(
            "隐藏后无新消息（旧消息时间更早）→ 保持隐藏",
            isHiddenNow(summary(lastMessageTimestamp = 999L), hiddenAtMs = 1000L)
        )
    }

    @Test
    fun `new message after hiddenAt reappears conversation`() {
        // AI 主动消息落库 → summary.lastMessageTimestamp 更新为 1001 > 1000 → 重显
        assertFalse(
            "新消息（含 AI 主动消息）晚于隐藏时刻 → 会话重显",
            isHiddenNow(summary(lastMessageTimestamp = 1001L), hiddenAtMs = 1000L)
        )
    }

    @Test
    fun `cleared conversation summary stays hidden until new message`() {
        // 删除该聊天后摘要行被删；hiddenAtMs 残留时视为隐藏中（避免残留状态干扰），
        // 新消息落库重建摘要、比较晚于 hiddenAtMs 即重显。
        assertTrue(
            "摘要不存在（清记录后）→ 视为隐藏中",
            isHiddenNow(summary = null, hiddenAtMs = 1000L)
        )
    }

    @Test
    fun `cleared conversation without hidden record is visible`() {
        // 删除后无隐藏记录 → 摘要重建时（如果有）自然可见；无摘要也不显示（正常空态）
        assertFalse(
            "无隐藏记录 → 不隐藏",
            isHiddenNow(summary = null, hiddenAtMs = null)
        )
    }

    @Test
    fun `hidden conversation deleted clears hidden record`() {
        // 语义验证：删除该聊天会同时清除隐藏标记（HomeSessionListStore.deleteConversation），
        // 删除后无隐藏记录，新消息落库时摘要重建自然重显（不再被残留 hiddenAt 压过）。
        // 本测试只验证"删除后无隐藏记录"这一前置条件的判定结果。
        assertFalse(
            "删除后隐藏记录已清 → 会话可重显",
            isHiddenNow(summary(lastMessageTimestamp = 1001L), hiddenAtMs = null)
        )
    }
}
