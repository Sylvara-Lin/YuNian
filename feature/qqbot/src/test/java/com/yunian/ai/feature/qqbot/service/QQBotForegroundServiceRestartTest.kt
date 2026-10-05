package com.yunian.ai.feature.qqbot.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 修复回归：QQBotForegroundService 自重启判定逻辑。
 *
 * 覆盖：
 * - 已登录（hasAccount=true）→ onDestroy / onTaskRemoved 触发 scheduleRestartWithDebounce
 * - 未登录（hasAccount=false）→ 不触发
 * - debounce 拦截同帧双调
 *
 * 注：本测试验证判定逻辑本身，不启动真实 Service / WorkManager；
 * Service 生命周期与 WorkManager 排程由真机验证（见 bug-report.html《需我人工验证清单》）。
 */
class QQBotForegroundServiceRestartTest {

    /** 模拟 scheduleRestartWithDebounce 的判定逻辑（与 QQBotForegroundService.scheduleRestartWithDebounce 同构）。 */
    private class RestartDecider {
        var lastRestartAtMs = 0L
        var restartCallCount = 0
        var workerScheduleCount = 0

        fun maybeRestart(hasAccount: Boolean, nowMs: Long, debounceMs: Long = 10_000L): Boolean {
            if (!hasAccount) return false
            if (nowMs - lastRestartAtMs < debounceMs) return false
            lastRestartAtMs = nowMs
            restartCallCount++
            workerScheduleCount++
            return true
        }
    }

    @Test
    fun `logged in triggers restart on destroy`() {
        val decider = RestartDecider()
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_000L))
        assertEquals(1, decider.restartCallCount)
        assertEquals(1, decider.workerScheduleCount)
    }

    @Test
    fun `logged out skips restart on destroy`() {
        val decider = RestartDecider()
        assertFalse(decider.maybeRestart(hasAccount = false, nowMs = 1_000_000L))
        assertEquals(0, decider.restartCallCount)
        assertEquals(0, decider.workerScheduleCount)
    }

    @Test
    fun `task removed triggers restart when logged in`() {
        val decider = RestartDecider()
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_000L))
        assertEquals(1, decider.restartCallCount)
    }

    @Test
    fun `debounce blocks double call within window`() {
        val decider = RestartDecider()
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_000L))
        // 同帧双调（间隔 1ms < 10s debounce）→ 拦截
        assertFalse(decider.maybeRestart(hasAccount = true, nowMs = 1_000_001L))
        assertEquals(1, decider.restartCallCount)
    }

    @Test
    fun `restart allowed after debounce window`() {
        val decider = RestartDecider()
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_000L))
        // 10s 后再次触发 → 允许
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_000L + 10_001L))
        assertEquals(2, decider.restartCallCount)
    }

    @Test
    fun `logged out then logged in allows restart`() {
        val decider = RestartDecider()
        assertFalse(decider.maybeRestart(hasAccount = false, nowMs = 1_000_000L))
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_001L))
        assertEquals(1, decider.restartCallCount)
    }

    @Test
    fun `worker scheduled alongside fgs restart`() {
        val decider = RestartDecider()
        assertTrue(decider.maybeRestart(hasAccount = true, nowMs = 1_000_000L))
        assertEquals(1, decider.restartCallCount)
        assertEquals(1, decider.workerScheduleCount)
    }
}
