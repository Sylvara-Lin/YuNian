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
