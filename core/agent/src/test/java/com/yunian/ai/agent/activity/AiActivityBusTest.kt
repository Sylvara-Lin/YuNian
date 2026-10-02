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
 * [AiActivityBus] 状态机单测：覆盖 RUNNING→终态覆写、多工具择主、clear、以及
 * StateFlow 在「同一毫秒内相位变化」时仍必然发布（去重防护）。
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
    fun `null before any progress`() {
        assertNull(AiActivityBus.current.value)
    }

    @Test
    fun `same callId RUNNING then DONE overwrites to terminal`() {
        AiActivityBus.onProgress(running(1, "screen_tap"))
        val runningState = AiActivityBus.current.value
        assertEquals(1L, runningState?.callId)
        assertEquals(AiActivityPhase.RUNNING, runningState?.phase)

        AiActivityBus.onProgress(terminal(1, "screen_tap", done = true))
        val doneState = AiActivityBus.current.value
        assertEquals(1L, doneState?.callId)
        assertEquals(AiActivityPhase.DONE, doneState?.phase)
    }

    @Test
    fun `phase change emits even within same millisecond`() {
        // 同一 ms 内 RUNNING→DONE：快照因 phase 不同必然不等 → StateFlow 必发布（不会被去重吞掉）
        AiActivityBus.onProgress(running(7, "screen_swipe"))
        AiActivityBus.onProgress(terminal(7, "screen_swipe", done = true))
        assertEquals(AiActivityPhase.DONE, AiActivityBus.current.value?.phase)
    }

    @Test
    fun `failed terminal maps to FAILED`() {
        AiActivityBus.onProgress(running(3, "screen_read"))
        AiActivityBus.onProgress(terminal(3, "screen_read", done = false))
        assertEquals(AiActivityPhase.FAILED, AiActivityBus.current.value?.phase)
    }

    @Test
    fun `most recent running wins then falls back to latest updated`() {
        AiActivityBus.onProgress(running(1, "a", startedAt = 100))
        AiActivityBus.onProgress(running(2, "b", startedAt = 200))
        // 两个 RUNNING → 取最近开始的（b）
        assertEquals(2L, AiActivityBus.current.value?.callId)

        AiActivityBus.onProgress(terminal(2, "b", done = true, startedAt = 200))
        // b 结束但 a 仍 RUNNING → 仍显示 a
        assertEquals(1L, AiActivityBus.current.value?.callId)
        assertEquals(AiActivityPhase.RUNNING, AiActivityBus.current.value?.phase)

        AiActivityBus.onProgress(terminal(1, "a", done = false, startedAt = 100))
        // 全部终态 → 取最近更新的（a 是最后一次写入）
        assertEquals(1L, AiActivityBus.current.value?.callId)
        assertEquals(AiActivityPhase.FAILED, AiActivityBus.current.value?.phase)
    }

    @Test
    fun `clear resets to null`() {
        AiActivityBus.onProgress(running(1, "screen_tap"))
        AiActivityBus.clear()
        assertNull(AiActivityBus.current.value)
    }

    @Test
    fun `flow emits distinct snapshots for running then terminal`() = runBlocking {
        val seen = mutableListOf<AiActivityState?>()
        val job = launch { AiActivityBus.current.collect { seen.add(it) } }
        yield() // 让收集器先跑到首次挂起

        AiActivityBus.onProgress(running(1, "screen_tap"))
        yield()
        AiActivityBus.onProgress(terminal(1, "screen_tap", done = true))
        yield()
        job.cancel()

        assertTrue("应观察到 RUNNING 快照", seen.any { it?.phase == AiActivityPhase.RUNNING && it.callId == 1L })
        assertTrue("应观察到 DONE 快照", seen.any { it?.phase == AiActivityPhase.DONE && it.callId == 1L })
    }
}
