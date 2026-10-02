package com.yunian.ai.agent.activity

import com.yunian.ai.agent.host.ToolCallPhase
import com.yunian.ai.agent.host.ToolCallProgress
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AiActivityBus] 回合状态机单测：覆盖 begin→多工具→end、endTurn 幂等/乱序保护、
 * **异常路径 endTurn 一定被调用**（通过 [AiActivityBus.withTurn] 的 try/finally）、
 * 以及 StateFlow 在「同一毫秒内相位变化」时仍必然发布（去重防护）。
 */
class AiActivityBusTest {

    @Before
    fun setUp() {
        AiActivityBus.clear()
    }

    @After
    fun tearDown() {
        AiActivityBus.clear()
    }

    private fun running(callId: Long, tool: String, startedAt: Long = callId) = ToolCallProgress(
        callId = callId,
        toolName = tool,
        argsSummary = "{}",
        phase = ToolCallPhase.RUNNING,
        resultSummary = null,
        startedAtMs = startedAt,
        elapsedMs = 0L,
    )

    private fun terminal(callId: Long, tool: String, done: Boolean, startedAt: Long = callId) = ToolCallProgress(
        callId = callId,
        toolName = tool,
        argsSummary = "{}",
        phase = if (done) ToolCallPhase.DONE else ToolCallPhase.FAILED,
        resultSummary = "ok",
        startedAtMs = startedAt,
        elapsedMs = 5L,
    )

    @Test
    fun `initial state is idle`() {
        assertNull(AiActivityBus.state.value.turnId)
        assertNull(AiActivityBus.state.value.activity)
    }

    @Test
    fun `beginTurn marks turn without activity`() {
        AiActivityBus.beginTurn(11L)
        assertEquals(11L, AiActivityBus.state.value.turnId)
        assertNull(AiActivityBus.state.value.activity)
    }

    @Test
    fun `progress within turn is stamped with turnId and populates activity`() {
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(running(1, "screen_tap"))
        val s = AiActivityBus.state.value
        assertEquals(11L, s.turnId)
        assertEquals(11L, s.activity?.turnId)
        assertEquals(1L, s.activity?.callId)
        assertEquals(AiActivityPhase.RUNNING, s.activity?.phase)
    }

    @Test
    fun `same callId RUNNING then DONE overwrites to terminal`() {
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(running(1, "screen_tap"))
        AiActivityBus.onProgress(terminal(1, "screen_tap", done = true))
        val s = AiActivityBus.state.value
        assertEquals(1L, s.activity?.callId)
        assertEquals(AiActivityPhase.DONE, s.activity?.phase)
    }

    @Test
    fun `phase change emits even within same millisecond`() {
        // 同一 ms 内 RUNNING→DONE：快照因 phase 不同必然不等 → StateFlow 必发布（不会被去重吞掉）
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(running(7, "screen_swipe"))
        AiActivityBus.onProgress(terminal(7, "screen_swipe", done = true))
        assertEquals(AiActivityPhase.DONE, AiActivityBus.state.value.activity?.phase)
    }

    @Test
    fun `failed terminal maps to FAILED`() {
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(running(3, "screen_read"))
        AiActivityBus.onProgress(terminal(3, "screen_read", done = false))
        assertEquals(AiActivityPhase.FAILED, AiActivityBus.state.value.activity?.phase)
    }

    @Test
    fun `most recent running wins then falls back to latest updated`() {
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(running(1, "a", startedAt = 100))
        AiActivityBus.onProgress(running(2, "b", startedAt = 200))
        // 两个 RUNNING → 取最近开始的（b）
        assertEquals(2L, AiActivityBus.state.value.activity?.callId)

        AiActivityBus.onProgress(terminal(2, "b", done = true, startedAt = 200))
        // b 结束但 a 仍 RUNNING → 仍显示 a
        assertEquals(1L, AiActivityBus.state.value.activity?.callId)
        assertEquals(AiActivityPhase.RUNNING, AiActivityBus.state.value.activity?.phase)

        AiActivityBus.onProgress(terminal(1, "a", done = false, startedAt = 100))
        // 全部终态 → 取最近更新的（a 是最后一次写入）
        assertEquals(1L, AiActivityBus.state.value.activity?.callId)
        assertEquals(AiActivityPhase.FAILED, AiActivityBus.state.value.activity?.phase)
    }

    @Test
    fun `endTurn clears active turn but keeps last activity for linger`() {
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(terminal(1, "screen_tap", done = true))
        AiActivityBus.endTurn(11L)
        val s = AiActivityBus.state.value
        assertNull(s.turnId)
        // 保留最后一个活动，供悬浮窗做「结束后的短暂保留」展示
        assertEquals(1L, s.activity?.callId)
    }

    @Test
    fun `endTurn with mismatched id is ignored`() {
        AiActivityBus.beginTurn(22L)
        AiActivityBus.onProgress(running(1, "screen_tap"))
        // 旧/乱序回合的 endTurn 不得清掉当前回合
        AiActivityBus.endTurn(11L)
        assertEquals(22L, AiActivityBus.state.value.turnId)
        assertEquals(1L, AiActivityBus.state.value.activity?.callId)
    }

    @Test
    fun `beginTurn clears previous turn activity`() {
        AiActivityBus.beginTurn(1L)
        AiActivityBus.onProgress(running(1, "old"))
        AiActivityBus.beginTurn(2L)
        val s = AiActivityBus.state.value
        assertEquals(2L, s.turnId)
        assertNull(s.activity)
    }

    @Test
    fun `withTurn runs block and ends turn on success`() {
        val result = AiActivityBus.withTurn(33L) {
            // 块内应处于回合进行中
            assertEquals(33L, AiActivityBus.state.value.turnId)
            "ok"
        }
        assertEquals("ok", result)
        assertNull("回合结束后 active turn 必须为 null", AiActivityBus.state.value.turnId)
    }

    @Test
    fun `withTurn ends turn even when block throws`() {
        var threw = false
        try {
            AiActivityBus.withTurn(44L) { error("boom") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("块异常必须向外传播", threw)
        assertNull("异常路径也必须 endTurn（否则悬浮窗永久滞留）", AiActivityBus.state.value.turnId)
    }

    @Test
    fun `clear resets turn and activity`() {
        AiActivityBus.beginTurn(11L)
        AiActivityBus.onProgress(running(1, "screen_tap"))
        AiActivityBus.clear()
        assertNull(AiActivityBus.state.value.turnId)
        assertNull(AiActivityBus.state.value.activity)
    }

    @Test
    fun `flow emits distinct snapshots for running then terminal`() = runBlocking {
        val seen = mutableListOf<AiTurnState>()
        val job = launch { AiActivityBus.state.collect { seen.add(it) } }
        yield() // 让收集器先跑到首次挂起

        AiActivityBus.beginTurn(55L)
        yield()
        AiActivityBus.onProgress(running(1, "screen_tap"))
        yield()
        AiActivityBus.onProgress(terminal(1, "screen_tap", done = true))
        yield()
        job.cancel()

        assertTrue(
            "应观察到带 RUNNING 活动的回合快照",
            seen.any { val a = it.activity; a != null && a.phase == AiActivityPhase.RUNNING && a.callId == 1L },
        )
        assertTrue(
            "应观察到带 DONE 活动的回合快照",
            seen.any { val a = it.activity; a != null && a.phase == AiActivityPhase.DONE && a.callId == 1L },
        )
    }
}
