package com.yunian.ai.feature.qqbot.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatAckTrackerTest {

    @Test
    fun `healthy connection with prompt ack never reconnects`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))

        val ack1 = t0 + 100L

        assertFalse(tracker.onBeforeSend(t0 + period, ack1))

        val ack2 = t0 + period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack2))

        val ack3 = t0 + 2 * period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, ack3))
    }

    @Test
    fun `single missed ack then recovery does not reconnect`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))

        assertFalse(tracker.onBeforeSend(t0 + period, 0L))

        val ack2 = t0 + period + 100L

        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack2))

        val ack3 = t0 + 2 * period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, ack3))
    }

    @Test
    fun `two consecutive missed acks triggers reconnect`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))

        assertFalse(tracker.onBeforeSend(t0 + period, 0L))
        assertTrue(tracker.onBeforeSend(t0 + 2 * period, 0L))
    }

    @Test
    fun `first heartbeat never triggers reconnect even without ack`() {
        val tracker = HeartbeatAckTracker()
        assertFalse(tracker.onBeforeSend(1_000L, 0L))
    }

    @Test
    fun `immediate first heartbeat after hello records baseline without false miss`() {
        // R2 修复回归：Hello 后立即首发心跳（onBeforeSend + send），首发 pendingSentAtMs=0 走 else 分支只记基线；
        // 第一个 period 后再调 onBeforeSend 判断的是首发是否已 ack，不会把首发误判为 miss。
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        // Hello 后立即首发（t0 时刻）：pendingSentAtMs=0 → else 分支 → missedAcks=0，记录基线
        assertFalse(tracker.onBeforeSend(t0, 0L))

        // 首发后立即收到 ack（t0+100ms）：第一个 period 后调 onBeforeSend，ack 时间 > 基线 → 不计 miss
        val ack1 = t0 + 100L
        assertFalse(tracker.onBeforeSend(t0 + period, ack1))

        // 第二个 period 后也收到 ack → 连续健康
        val ack2 = t0 + period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack2))
    }

    @Test
    fun `immediate first heartbeat without ack then one miss does not reconnect`() {
        // R2 修复回归：Hello 后立即首发但未收到 ack，第一个 period 后调 onBeforeSend 计 miss=1（未达上限 2），不重连
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))          // 首发
        assertFalse(tracker.onBeforeSend(t0 + period, 0L)) // 首发未 ack → miss=1（未达上限）
        val ack2 = t0 + period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack2)) // 第二次心跳收到 ack → miss 清零
    }

    @Test
    fun `immediate first heartbeat with two consecutive misses triggers reconnect`() {
        // R2 修复回归：Hello 后立即首发但连续 2 个 period 未收到 ack，达到上限触发重连
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))              // 首发
        assertFalse(tracker.onBeforeSend(t0 + period, 0L))     // miss=1
        assertTrue(tracker.onBeforeSend(t0 + 2 * period, 0L))  // miss=2 → 触发重连
    }

    @Test
    fun `reset clears pending state`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))
        assertFalse(tracker.onBeforeSend(t0 + period, 0L))
        tracker.reset()

        assertFalse(tracker.onBeforeSend(t0 + 2 * period, 0L))
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, 0L))
        assertTrue(tracker.onBeforeSend(t0 + 4 * period, 0L))
    }

    @Test
    fun `stale old ack does not mask new missed heartbeats`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))

        val ack1 = t0 + 50L
        assertFalse(tracker.onBeforeSend(t0 + period, ack1))

        assertFalse(tracker.onBeforeSend(t0 + 2 * period, ack1))
        assertTrue(tracker.onBeforeSend(t0 + 3 * period, ack1))
    }

    @Test
    fun `ack after next send is treated as healthy current activity`() {
        val tracker = HeartbeatAckTracker()
        val t0 = 1_000_000L
        val period = 33_000L

        assertFalse(tracker.onBeforeSend(t0, 0L))

        assertFalse(tracker.onBeforeSend(t0 + period, 0L))

        val lateAck = t0 + period + 200L
        assertFalse(tracker.onBeforeSend(t0 + 2 * period, lateAck))
        val ack3 = t0 + 2 * period + 100L
        assertFalse(tracker.onBeforeSend(t0 + 3 * period, ack3))
    }
}
