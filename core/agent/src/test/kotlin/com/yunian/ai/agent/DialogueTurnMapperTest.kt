package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentEvent
import com.yunian.ai.agent.uniffi.AgentTurnResult
import com.yunian.ai.domain.DialogueResult
import com.yunian.ai.domain.dialogue.DialogueCompletion
import com.yunian.ai.domain.dialogue.DialogueOutputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 结构化回合投影契约测试（P3-3b）。
 *
 * 钉死的四件事：
 * 1. **不重复**：bubble 与 finalText 相同时只产出一条气泡（与 [AgentTurnReplyText] 的可见性规则一致）；
 * 2. **不外泄**：`reasoning` / `usage` / `confirm_request`（含其工具参数 JSON）以及任何未知 kind
 *    的内容都不进入快照的任何字段——断言用 `snapshot.toString()`，它覆盖 DTO 的**全部**字段；
 * 3. **不携带未过滤文本**：没通过 `sanitizeForDisplay` / `isOutputSafe` 的片段一律丢弃，
 *    连调用方误传的「外发文本」兜底也要过同一道判定；
 * 4. **文本兜底逐字一致**：`confirm_pending` 的提示与 [AgentTurnReplyText.resolve] 的同一句完全相等。
 *
 * 说明：本测试类**显式注入** `isOutputSafe` 替身——真实 `ContentFilter` 底层走 `android.util.Log`，
 * 在纯 JVM 单测里是抛 `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric，`:core:agent`
 * 也未开启 `unitTests.isReturnDefaultValues`），与 [AgentConfirmGuardTest] 注入
 * [AgentConfirmGuardLog] 是同一手法。
 */
class DialogueTurnMapperTest {

    private fun result(
        finishedReason: String = "completed",
        events: List<AgentEvent> = emptyList(),
        finalText: String = "",
    ): AgentTurnResult = AgentTurnResult(
        events = events,
        finalText = finalText,
        roundsUsed = 1u,
        finishedReason = finishedReason,
        error = null,
    )

    private fun bubble(text: String) =
        AgentEvent(kind = DialogueTurnMapper.EVENT_BUBBLE, text = text, extra = "")

    private fun sticker(label: String, extra: String = "") =
        AgentEvent(kind = DialogueTurnMapper.EVENT_STICKER, text = label, extra = extra)

    private val allSafe: (String) -> Boolean = { true }

    private fun project(
        result: AgentTurnResult,
        cleanedReplyText: String,
        isOutputSafe: (String) -> Boolean = allSafe,
    ) = DialogueTurnMapper.project(
        result = result,
        cleanedReplyText = cleanedReplyText,
        isOutputSafe = isOutputSafe,
    )

    // ── 验收标准 1：bubble 与 finalText 相同不重复 ──

    @Test
    fun `bubble 与 finalText 相同时只产出一条气泡`() {
        val snapshot = project(
            result(events = listOf(bubble("你好")), finalText = "你好"),
            cleanedReplyText = "你好",
        )

        assertEquals(listOf<DialogueOutputEvent>(DialogueOutputEvent.Bubble("你好")), snapshot.events)
        assertEquals("你好", snapshot.events.joinToString("\n") { it.text })
        assertEquals(DialogueCompletion.COMPLETED, snapshot.completion)
    }

    // ── 验收标准 2：sticker 有文本兜底表示 ──

    @Test
    fun `sticker 事件的文本兜底表示与改动前逐字一致`() {
        val events = listOf(sticker("开心"))
        val snapshot = project(
            result(events = events, finalText = "[开心]"),
            cleanedReplyText = "[开心]",
        )

        assertEquals(listOf<DialogueOutputEvent>(DialogueOutputEvent.Sticker("开心")), snapshot.events)
        assertEquals("[开心]", snapshot.events.single().text)
        // 通道侧改动前拿到的就是 AgentTurnReplyText 压平后的这一串
        assertEquals(
            AgentTurnReplyText.resolve(events, "", "completed"),
            snapshot.events.joinToString("\n") { it.text },
        )
    }

    // ── 验收标准 3：只有 finalText（无可见事件）时的形状 ──

    @Test
    fun `只有 finalText 时快照为单条气泡`() {
        val snapshot = project(result(finalText = "你好"), cleanedReplyText = "你好")

        assertEquals(listOf<DialogueOutputEvent>(DialogueOutputEvent.Bubble("你好")), snapshot.events)
        assertEquals(DialogueCompletion.COMPLETED, snapshot.completion)
        assertEquals(0, snapshot.droppedInternalEventCount)
    }

    @Test
    fun `完全无输出时快照为空且保留终局原因`() {
        val snapshot = project(
            result(finishedReason = "error", events = emptyList(), finalText = ""),
            cleanedReplyText = "",
        )

        assertEquals(emptyList<DialogueOutputEvent>(), snapshot.events)
        assertEquals(DialogueCompletion.ERROR, snapshot.completion)
    }

    // ── 验收标准 4：未知 kind 不外泄为可见内容 ──

    @Test
    fun `未知 kind 只计数不外泄`() {
        val snapshot = project(
            result(
                events = listOf(
                    bubble("在的"),
                    AgentEvent("status", "waiting", ""),
                    AgentEvent("mystery_kind", "SECRET_UNKNOWN_TEXT", "SECRET_UNKNOWN_EXTRA"),
                ),
                finalText = "在的",
            ),
            cleanedReplyText = "在的",
        )

        assertEquals(listOf<DialogueOutputEvent>(DialogueOutputEvent.Bubble("在的")), snapshot.events)
        assertEquals(2, snapshot.droppedInternalEventCount)

        val all = snapshot.toString()
        assertFalse("未知 kind 的 text 不得进入任何字段", all.contains("SECRET_UNKNOWN_TEXT"))
        assertFalse("未知 kind 的 extra 不得进入任何字段", all.contains("SECRET_UNKNOWN_EXTRA"))
        assertFalse("内部状态文本不得进入任何字段", all.contains("waiting"))
    }

    // ── 验收标准 5：reasoning / usage / confirm_request.extra 不出现 ──

    @Test
    fun `reasoning usage confirm_request 的内容不进入任何 DTO 字段`() {
        val snapshot = project(
            result(
                finishedReason = "confirm_pending",
                events = listOf(
                    bubble("在的"),
                    AgentEvent("reasoning", "SECRET_REASONING", ""),
                    AgentEvent("usage", "{\"prompt_tokens\":123,\"SECRET_USAGE\":\"x\"}", ""),
                    AgentEvent("confirm_request", "automation_create", "{\"SECRET_ARGS\":\"latte\"}"),
                ),
                finalText = "在的",
            ),
            cleanedReplyText = "在的\n" + DialogueTurnMapper.CONFIRM_PENDING_NOTICE,
        )

        assertEquals(
            listOf<DialogueOutputEvent>(
                DialogueOutputEvent.Bubble("在的"),
                DialogueOutputEvent.Notice(DialogueTurnMapper.CONFIRM_PENDING_NOTICE),
            ),
            snapshot.events,
        )
        assertEquals(DialogueCompletion.CONFIRM_PENDING, snapshot.completion)
        assertEquals(3, snapshot.droppedInternalEventCount)

        val all = snapshot.toString()
        for (marker in listOf(
            "SECRET_REASONING",
            "SECRET_USAGE",
            "prompt_tokens",
            "SECRET_ARGS",
            "automation_create",
        )) {
            assertFalse(marker, all.contains(marker))
        }
    }

    @Test
    fun `表情包 extra 不进入快照`() {
        val snapshot = project(
            result(events = listOf(sticker("开心", extra = "entry_id=42;file_name=happy_cat.png")), finalText = ""),
            cleanedReplyText = "[开心]",
        )

        assertEquals(listOf<DialogueOutputEvent>(DialogueOutputEvent.Sticker("开心")), snapshot.events)
        val all = snapshot.toString()
        for (marker in listOf("entry_id", "42", "happy_cat.png")) {
            assertFalse(marker, all.contains(marker))
        }
    }

    // ── 验收标准 6：不携带未过滤文本 ──

    @Test
    fun `未通过输出过滤的片段不进入快照`() {
        val isSafe: (String) -> Boolean = { text -> !text.contains("违规") }

        // ① 事件片段被判定不安全 → 丢弃（只计数）
        val dropped = project(
            result(events = listOf(bubble("违规文本")), finalText = ""),
            cleanedReplyText = "",
            isOutputSafe = isSafe,
        )
        assertEquals(emptyList<DialogueOutputEvent>(), dropped.events)
        assertEquals(1, dropped.droppedInternalEventCount)

        // ② 即使调用方把同一段文本当作「已过滤的外发文本」传进来，也不会进入契约
        val fallback = project(
            result(events = emptyList(), finalText = ""),
            cleanedReplyText = "违规文本",
            isOutputSafe = isSafe,
        )
        assertEquals(emptyList<DialogueOutputEvent>(), fallback.events)
        assertFalse("兜底文本同样必须过输出过滤", fallback.toString().contains("违规文本"))
    }

    @Test
    fun `blocked 结果默认不携带回合快照`() {
        // 协调器的拦截路径（输入违规 / 输出违规 / 封禁 / 回合失败）都不构造快照：
        // DialogueResult.turn 保持默认 null，调用方只能发送写死的安全话术。
        val blocked = DialogueResult(replyText = "抱歉，我无法处理这个话题。", blocked = true)

        assertNull("拦截路径不得携带任何回合快照", blocked.turn)
        assertEquals("抱歉，我无法处理这个话题。", blocked.replyText)
    }

    // ── 清洗（画面描述不得随气泡进契约） ──

    @Test
    fun `气泡逐条清洗画面描述`() {
        val snapshot = project(
            result(
                events = listOf(bubble("好的\n[[生图: 一只猫在窗台上睡觉]]"), bubble("马上")),
                finalText = "",
            ),
            cleanedReplyText = "好的\n马上",
        )

        assertEquals(
            listOf<DialogueOutputEvent>(
                DialogueOutputEvent.Bubble("好的"),
                DialogueOutputEvent.Bubble("马上"),
            ),
            snapshot.events,
        )
        assertFalse("画面描述不得进入任何字段", snapshot.toString().contains("一只猫"))
    }

    @Test
    fun `只有画面描述时快照只含外发占位文案`() {
        // 协调器把「整条回复只有画面描述」的外发文本替换成占位文案
        // （AgentDialogueCoordinator.IMAGE_GEN_ONLY_REPLY_TEXT）；投影按外发文本走，
        // 因此契约里既不出现画面描述原文，也不会凭空多出一条气泡。
        val snapshot = project(
            result(
                events = listOf(bubble("[[生图: 一只猫在窗台上睡觉]]")),
                finalText = "[[生图: 一只猫在窗台上睡觉]]",
            ),
            cleanedReplyText = "（正在为你配图…）",
        )

        assertEquals(listOf<DialogueOutputEvent>(DialogueOutputEvent.Bubble("（正在为你配图…）")), snapshot.events)
        assertEquals(1, snapshot.droppedInternalEventCount)
        assertFalse("画面描述不得进入任何字段", snapshot.toString().contains("一只猫"))
    }

    // ── 验收标准 7：文本兜底行为逐字一致 ──

    @Test
    fun `confirm_pending 提示与 AgentTurnReplyText 逐字一致`() {
        assertEquals(
            AgentTurnReplyText.resolve(emptyList(), "", "confirm_pending"),
            DialogueTurnMapper.CONFIRM_PENDING_NOTICE,
        )
        assertEquals(
            "守卫的 finished_reason 常量必须映射到 CONFIRM_PENDING",
            DialogueCompletion.CONFIRM_PENDING,
            DialogueCompletion.fromFinishedReason(AgentConfirmGuard.FINISHED_CONFIRM_PENDING),
        )
    }

    @Test
    fun `completion 覆盖 Rust 全部 finished_reason`() {
        val expected = mapOf(
            "completed" to DialogueCompletion.COMPLETED,
            "max_rounds" to DialogueCompletion.MAX_ROUNDS,
            "confirm_pending" to DialogueCompletion.CONFIRM_PENDING,
            "state_stop" to DialogueCompletion.STATE_STOP,
            "error" to DialogueCompletion.ERROR,
            "max_tool_calls" to DialogueCompletion.MAX_TOOL_CALLS,
            "max_text" to DialogueCompletion.MAX_TEXT,
            "brand_new_reason" to DialogueCompletion.UNKNOWN,
        )

        for ((reason, completion) in expected) {
            assertEquals(reason, completion, DialogueCompletion.fromFinishedReason(reason))
        }
    }
}
