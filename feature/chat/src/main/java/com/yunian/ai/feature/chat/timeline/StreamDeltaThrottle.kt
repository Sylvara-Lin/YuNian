package com.yunian.ai.feature.chat.timeline

class StreamDeltaThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val lengthThreshold: Int = DEFAULT_LENGTH_THRESHOLD,
) {
    private var lastEmitAtMs: Long = 0L
    private var pendingChars: Int = 0

    fun shouldEmit(nowMs: Long, addedChars: Int, force: Boolean = false): Boolean {
        if (force) return true
        if (addedChars > 0) pendingChars += addedChars
        if (pendingChars >= lengthThreshold) return true
        if (lastEmitAtMs == 0L) return true
        if (nowMs - lastEmitAtMs >= intervalMs) return true
        return false
    }

    fun markEmitted(nowMs: Long) {
        lastEmitAtMs = nowMs
        pendingChars = 0
    }

    fun reset() {
        lastEmitAtMs = 0L
        pendingChars = 0
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 50L
        const val DEFAULT_LENGTH_THRESHOLD = 20
    }
}
