package com.yunian.ai.feature.chat.timeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDeltaThrottleTest {

    @Test
    fun firstDeltaAlwaysEmits() {
        val t = StreamDeltaThrottle(intervalMs = 50L, lengthThreshold = 20)
        assertTrue(t.shouldEmit(nowMs = 1000L, addedChars = 1))
    }

    @Test
    fun emitsWhenLengthThresholdReached() {
        val t = StreamDeltaThrottle(intervalMs = 10_000L, lengthThreshold = 5)
        t.markEmitted(1000L)
        assertFalse(t.shouldEmit(1001L, addedChars = 2))
        assertTrue(t.shouldEmit(1002L, addedChars = 3))
    }

    @Test
    fun emitsWhenIntervalElapsed() {
        val t = StreamDeltaThrottle(intervalMs = 50L, lengthThreshold = 100)
        t.markEmitted(1000L)
        assertFalse(t.shouldEmit(1020L, addedChars = 1))
        assertTrue(t.shouldEmit(1050L, addedChars = 1))
    }

    @Test
    fun forceAlwaysEmits() {
        val t = StreamDeltaThrottle(intervalMs = 10_000L, lengthThreshold = 100)
        t.markEmitted(1000L)
        assertTrue(t.shouldEmit(1001L, addedChars = 0, force = true))
    }
}
