package com.yunian.ai.common.concurrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateSendGuardTest {

    private class FakeClock(var now: Long = 0L) {
        fun tick(ms: Long) { now += ms }
    }

    @Test
    fun `同内容窗口内重复提交被拒绝`() {
        val clock = FakeClock()
        val guard = DuplicateSendGuard(windowMs = 2000L, clock = { clock.now })
        assertFalse(guard.shouldReject("在吗"))
        clock.tick(500)
        assertTrue(guard.shouldReject("在吗")) // 双击第二次 → 拒绝
    }

    @Test
    fun `窗口过后同内容放行 - 窗口锚定首次不接受刷新`() {
        val clock = FakeClock()
        val guard = DuplicateSendGuard(windowMs = 2000L, clock = { clock.now })
        assertFalse(guard.shouldReject("在吗"))
        // 窗口内连续点击不刷新锚点：t=1900 拒绝后，t=2100（距首次 2100ms）应放行
        clock.tick(1900)
        assertTrue(guard.shouldReject("在吗"))
        clock.tick(200)
        assertFalse(guard.shouldReject("在吗"))
    }

    @Test
    fun `不同内容不受窗口限制`() {
        val clock = FakeClock()
        val guard = DuplicateSendGuard(windowMs = 2000L, clock = { clock.now })
        assertFalse(guard.shouldReject("在吗"))
        clock.tick(100)
        assertFalse(guard.shouldReject("干嘛呢")) // 换内容 → 放行
    }

    @Test
    fun `边界 - 恰好等于窗口时长时放行`() {
        val clock = FakeClock()
        val guard = DuplicateSendGuard(windowMs = 2000L, clock = { clock.now })
        assertFalse(guard.shouldReject("在吗"))
        clock.tick(2000)
        assertFalse(guard.shouldReject("在吗")) // now - at == windowMs，不小于 → 放行
    }

    @Test
    fun `被拒绝的提交不刷新窗口锚点`() {
        val clock = FakeClock()
        val guard = DuplicateSendGuard(windowMs = 2000L, clock = { clock.now })
        assertFalse(guard.shouldReject("在吗"))
        // 持续点击 100ms 一次：全部拒绝，且不把窗口往后推
        repeat(10) {
            clock.tick(100)
            assertTrue(guard.shouldReject("在吗"))
        }
        // 距首次 1100ms；若拒绝时刷新了锚点，这里会被误判为拒绝
        clock.tick(1000)
        // 距首次 2100ms > 窗口 → 放行
        assertFalse(guard.shouldReject("在吗"))
    }

    @Test
    fun `空白内容由调用方负责 - 守卫本身不做特殊处理`() {
        val clock = FakeClock()
        val guard = DuplicateSendGuard(windowMs = 2000L, clock = { clock.now })
        assertFalse(guard.shouldReject(""))
        clock.tick(100)
        assertTrue(guard.shouldReject("")) // 空内容同样按内容比对（UI 层应已拦截空白）
    }
}
