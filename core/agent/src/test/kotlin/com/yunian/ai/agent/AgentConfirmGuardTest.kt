package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentEvent
import com.yunian.ai.agent.uniffi.AgentTurnResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认门守卫契约测试：[AgentConfirmGuard] 在**无确认界面**的回合上 fail-closed 自动拒绝 + 有限次重跑。
 *
 * 为什么需要它：`AiTool.requiresConfirmation = true` 经 [AgentFacade.deriveToolCategory] 映射为
 * `ToolCategory.COMMERCE`（见 [AgentToolCategoryConfirmationTest]）后，Rust 回合会以
 * `finished_reason = "confirm_pending"` 提前结束，并产出 `kind = "confirm_request"` 的事件。
 * 群聊此前直接把这种结果交给上层 → 回合静默结束、气泡为空（死路）；通道侧的内联循环是本类
 * 抽取出来的语义来源，因此这里逐条钉死那套语义（含上限 3 与异常吞噬）。
 *
 * 两条回归钉子（本轮补测）：
 * - U2：重跑抛 `CancellationException` 必须**向上传播**（取消不得被吞掉，否则已取消的作用域会
 *   继续把陈旧回复投递到 UI），且不得记成 error 级「重跑失败」；
 * - U1：重跑到上限仍为 `confirm_pending` 的终局结果，必须能兜出非空可见文案（群聊侧复用
 *   [AgentTurnReplyText.resolve] 的同一个入口），否则群聊 `firstOrNull()` 取空即静默。
 *
 * 说明：本测试类注入 [RecordingLog] 作为日志出口——生产默认值走的 `SecureLog` 底层是
 * `android.util.Log`，在纯 JVM 单测里是抛 `RuntimeException("Stub!")` 的空壳（本仓库无
 * Robolectric，`:core:agent` 也未开启 `unitTests.isReturnDefaultValues`）。
 */
class AgentConfirmGuardTest {

    /** 日志替身：记录 tag|message，用于断言文案 / tag 与抽取前逐字一致。 */
    private class RecordingLog : AgentConfirmGuardLog {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()

        override fun w(tag: String, message: String) {
            warnings += "$tag|$message"
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            errors += "$tag|$message" to throwable
        }
    }

    private val tag = "GroupChatViewModel"
    private val rerunFailureMessage = "runTurn after auto-reject failed, companion=42"

    private fun agentResult(
        finishedReason: String,
        events: List<AgentEvent> = emptyList(),
        finalText: String = "",
    ): AgentTurnResult = AgentTurnResult(
        events = events,
        finalText = finalText,
        roundsUsed = 1u,
        finishedReason = finishedReason,
        error = null,
    )

    /** Rust 确认门产物：finished_reason = confirm_pending + kind = confirm_request（text = 工具名）。 */
    private fun confirmPending(toolName: String, args: String = "{}"): AgentTurnResult =
        agentResult(
            finishedReason = AgentConfirmGuard.FINISHED_CONFIRM_PENDING,
            events = listOf(AgentEvent(kind = AgentConfirmGuard.EVENT_CONFIRM_REQUEST, text = toolName, extra = args)),
        )

    @Test
    fun `首次 confirm_pending + confirm_request 时拒绝该工具并重跑一次`() {
        runBlocking {
            val log = RecordingLog()
            val guard = AgentConfirmGuard(log)
            var runs = 0
            val rejected = mutableListOf<Pair<String, String>>()
            val completed = agentResult("completed", listOf(AgentEvent("bubble", "好的", "")), "好的")

            val final = guard.drive(
                tag = tag,
                initial = confirmPending("screen_click_text", """{"sku":"latte"}"""),
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    completed
                },
                rejectTool = { name, args -> rejected += name to args },
            )

            assertEquals("重跑恰好一次", 1, runs)
            assertEquals(
                "拒绝的参数必须是事件里的 text/extra（名称 + 参数 JSON，逐字一致）",
                listOf("screen_click_text" to """{"sku":"latte"}"""),
                rejected,
            )
            assertSame("返回的是重跑后的终局结果", completed, final)
            assertEquals(
                "自动拒绝的日志 tag / 文案与抽取前逐字一致",
                listOf("$tag|confirm gate on non-interactive channel, auto-reject: screen_click_text"),
                log.warnings,
            )
            assertTrue("成功路径不得记错误日志", log.errors.isEmpty())
        }
    }

    @Test
    fun `连续 confirm_pending 达到上限 3 次后停止、不再无限重跑`() {
        runBlocking {
            val log = RecordingLog()
            val guard = AgentConfirmGuard(log)
            var runs = 0
            var rejects = 0

            val final = guard.drive(
                tag = tag,
                initial = confirmPending("screen_tap"),
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    confirmPending("screen_tap")
                },
                rejectTool = { _, _ -> rejects += 1 },
            )

            assertEquals("上限即 AgentConfirmGuard.MAX_AUTO_REJECT（3）", 3, AgentConfirmGuard.MAX_AUTO_REJECT)
            assertEquals("重跑 3 次后必须停下", 3, runs)
            assertEquals("每次重跑前各拒绝一次", 3, rejects)
            assertEquals("日志条数 = 重跑次数（不再多、不再少）", 3, log.warnings.size)
            assertEquals(
                "到达上限后返回最后一次结果（仍是 confirm_pending，由调用方决定如何收尾）",
                AgentConfirmGuard.FINISHED_CONFIRM_PENDING,
                final.finishedReason,
            )
        }
    }

    @Test
    fun `result 没有 confirm_request 事件时不重跑`() {
        runBlocking {
            val guard = AgentConfirmGuard(RecordingLog())
            var runs = 0
            var rejects = 0
            val noEvent = agentResult(
                finishedReason = AgentConfirmGuard.FINISHED_CONFIRM_PENDING,
                events = listOf(AgentEvent("status", "waiting", "")),
            )

            val final = guard.drive(
                tag = tag,
                initial = noEvent,
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    agentResult("completed")
                },
                rejectTool = { _, _ -> rejects += 1 },
            )

            assertEquals("取不到 confirm_request 事件必须 break（不空转）", 0, runs)
            assertEquals("没有可拒绝的目标，不得调用 rejectTool", 0, rejects)
            assertSame("原样返回入参结果", noEvent, final)
        }
    }

    @Test
    fun `首次就 completed 时一次都不重跑、不调用 reject`() {
        runBlocking {
            val log = RecordingLog()
            val guard = AgentConfirmGuard(log)
            var runs = 0
            var rejects = 0
            val completed = agentResult("completed", listOf(AgentEvent("bubble", "在的", "")), "在的")

            val final = guard.drive(
                tag = tag,
                initial = completed,
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    agentResult("completed")
                },
                rejectTool = { _, _ -> rejects += 1 },
            )

            assertEquals(0, runs)
            assertEquals(0, rejects)
            assertSame(completed, final)
            assertTrue(log.warnings.isEmpty())
            assertTrue(log.errors.isEmpty())
        }
    }

    @Test
    fun `重跑抛异常时被吞掉并 break、不向上传播`() {
        runBlocking {
            val log = RecordingLog()
            val guard = AgentConfirmGuard(log)
            var runs = 0
            var rejects = 0
            val pending = confirmPending("screen_swipe", """{"x":1}""")

            val final = guard.drive(
                tag = tag,
                initial = pending,
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    throw IllegalStateException("boom")
                },
                rejectTool = { _, _ -> rejects += 1 },
            )

            assertEquals("异常发生在重跑，重跑只发起一次", 1, runs)
            assertEquals("拒绝已完成（拒绝在重跑之前）", 1, rejects)
            assertSame("break 保留上一次结果，不向上抛异常", pending, final)
            assertEquals(
                "重跑失败按调用方给的文案记错误日志",
                listOf("$tag|$rerunFailureMessage"),
                log.errors.map { it.first },
            )
            assertEquals("异常本身必须带上（供定位）", "boom", log.errors.single().second?.message)
        }
    }

    @Test
    fun `重跑返回 null 视同失败、break 且不向上传播`() {
        runBlocking {
            val guard = AgentConfirmGuard(RecordingLog())
            var runs = 0
            val pending = confirmPending("automation_create")

            val final = guard.drive(
                tag = tag,
                initial = pending,
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    null
                },
                rejectTool = { _, _ -> },
            )

            assertEquals(1, runs)
            assertSame(pending, final)
        }
    }

    // ── U2：取消必须传播 ────────────────────────────────────────────────────────────────

    @Test
    fun `重跑抛 CancellationException 时必须向上传播、且不记为重跑失败`() {
        runBlocking {
            val log = RecordingLog()
            val guard = AgentConfirmGuard(log)
            var runs = 0
            var rejects = 0
            val pending = confirmPending("screen_click_text")
            val cancellation = CancellationException("scope cancelled")

            var thrown: Throwable? = null
            try {
                guard.drive(
                    tag = tag,
                    initial = pending,
                    rerunFailureMessage = rerunFailureMessage,
                    runTurn = {
                        runs += 1
                        throw cancellation
                    },
                    rejectTool = { _, _ -> rejects += 1 },
                )
            } catch (e: Throwable) {
                thrown = e
            }

            assertEquals("重跑发起一次后被取消", 1, runs)
            assertEquals("拒绝已完成（拒绝在重跑之前）", 1, rejects)
            assertSame(
                "CancellationException 必须原样向上传播（同一个实例），不能被 runCatching 吞掉",
                cancellation,
                thrown,
            )
            assertTrue("必须是 CancellationException", thrown is CancellationException)
            assertEquals("scope cancelled", thrown?.message)
            assertTrue(
                "取消不是失败：不得记 error 级日志（否则正常取消会被当成故障刷噪音）",
                log.errors.isEmpty(),
            )
            assertEquals(
                "自动拒绝的告警日志仍然照旧",
                listOf("$tag|confirm gate on non-interactive channel, auto-reject: screen_click_text"),
                log.warnings,
            )
        }
    }

    // ──（b）既有行为不回退：普通异常仍被吞掉 ──────────────────────────────────────────

    @Test
    fun `重跑抛普通 RuntimeException 时仍吞掉 + 记日志 + break`() {
        runBlocking {
            val log = RecordingLog()
            val guard = AgentConfirmGuard(log)
            var runs = 0
            var rejects = 0
            val pending = confirmPending("screen_tap", """{"x":2}""")
            val failure = RuntimeException("network down")

            val final = guard.drive(
                tag = tag,
                initial = pending,
                rerunFailureMessage = rerunFailureMessage,
                runTurn = {
                    runs += 1
                    throw failure
                },
                rejectTool = { _, _ -> rejects += 1 },
            )

            assertEquals("异常发生在重跑，重跑只发起一次", 1, runs)
            assertEquals("拒绝已完成", 1, rejects)
            assertSame("break 保留上一次结果，不向上抛异常", pending, final)
            assertEquals(
                "非取消异常按调用方给的文案记错误日志",
                listOf("$tag|$rerunFailureMessage"),
                log.errors.map { it.first },
            )
            assertSame("异常实例本身必须带上（供定位）", failure, log.errors.single().second)
        }
    }

    // ── U1：confirm_pending 终局不得静默 ───────────────────────────────────────────────

    @Test
    fun `上限耗尽仍为 confirm_pending 时终局可见文案非空`() {
        runBlocking {
            val guard = AgentConfirmGuard(RecordingLog())

            // 模型在每次重跑后仍坚持调用被拒工具 → 3 次上限耗尽，终局仍是 confirm_pending，
            // 且没有 bubble 事件、没有 finalText（Rust 确认门提前结束的形态）。
            val final = guard.drive(
                tag = tag,
                initial = confirmPending("screen_click_text"),
                rerunFailureMessage = rerunFailureMessage,
                runTurn = { confirmPending("screen_click_text") },
                rejectTool = { _, _ -> },
            )

            assertEquals(
                "上限耗尽后仍是 confirm_pending（由调用方收尾）",
                AgentConfirmGuard.FINISHED_CONFIRM_PENDING,
                final.finishedReason,
            )
            assertTrue("终局不得带 bubble 事件", final.events.none { it.kind == "bubble" })
            assertTrue("终局不得带 finalText", final.finalText.isBlank())

            // 群聊侧（GroupChatViewModel.generateIsolatedAiReplyBubbles）在 confirm_pending 终局
            // 就是复用这个入口兜文案；它必须给出非空可见文案，否则群聊 firstOrNull() 取空直接 return。
            val visible = AgentTurnReplyText.resolve(final.events, final.finalText, final.finishedReason)
            assertNotNull("confirm_pending 终局必须兜出可见文案（不得为空）", visible)
            assertTrue("文案不得为空白", visible!!.isNotBlank())
            assertEquals(
                "群聊与通道侧必须逐字一致",
                "这一步需要你在 App 内确认后才能执行（本次未执行）。",
                visible,
            )
        }
    }
}
