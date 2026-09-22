package com.yunian.ai.feature.qqbot.data.network

internal class HeartbeatAckTracker(
    private val maxMissedAcks: Int = 2,
) {
    private var missedAcks = 0
    private var pendingSentAtMs = 0L

    fun onBeforeSend(nowMs: Long, lastAckAtMs: Long): Boolean {
        if (pendingSentAtMs > 0L && lastAckAtMs < pendingSentAtMs) {
            missedAcks++
            if (missedAcks >= maxMissedAcks) {
                reset()
                return true
            }
        } else {
            missedAcks = 0
        }
        pendingSentAtMs = nowMs
        return false
    }

    fun reset() {
        missedAcks = 0
        pendingSentAtMs = 0L
    }
}
