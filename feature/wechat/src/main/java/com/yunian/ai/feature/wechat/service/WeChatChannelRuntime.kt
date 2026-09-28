package com.yunian.ai.feature.wechat.service

import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.wechat.WeChatChannelHealthSnapshot
import com.yunian.ai.domain.wechat.WeChatFailureReason
import com.yunian.ai.domain.wechat.WeChatOutboxFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random

object WeChatChannelRuntime {

    private val primaryPollerActive = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)
    private val lastPollAtMs = AtomicLong(0L)
    private val lastErrorAtMs = AtomicLong(0L)
    private val lastError = AtomicReference<String?>(null)
    private val primaryClaimedAtMs = AtomicLong(0L)
    private val lastHealAtMs = AtomicLong(0L)

    private val lastWatchdogTickAtMs = AtomicLong(0L)
    private val watchdogStallCount = AtomicInteger(0)
    private val lastWatchdogStallMs = AtomicLong(0L)

    private val lastSessionRebuildAtMs = AtomicLong(0L)

    /** 会话过期（errcode=-14）冷却期截止时间；0 = 未过期。对标官方 session-guard 1 小时冷却。 */
    private val sessionExpiredUntilMs = AtomicLong(0L)

    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 60_000L
    const val SUCCESS_IDLE_MS = 0L

    /** 会话过期冷却时长（对标官方 SESSION_PAUSE_DURATION_MS = 1 小时）。 */
    const val SESSION_EXPIRED_COOLDOWN_MS = 60 * 60 * 1000L

    /** 会话过期的用户可见文案（经 lastError 通道透出）。 */
    const val SESSION_EXPIRED_USER_MESSAGE = "微信会话已过期（errcode=-14），请重新扫码登录"

    const val STALE_POLL_MS = 90_000L

    const val WATCHDOG_INTERVAL_MS = 60_000L

    const val WATCHDOG_STALL_THRESHOLD_MS = 150_000L

    const val SESSION_REBUILD_INTERVAL_MS = 20 * 60 * 1000L

    const val HEAL_COOLDOWN_MS = 30_000L

    private const val TAG = "WeChatRuntime"

    fun claimPrimaryPoller(): Boolean {
        val claimed = primaryPollerActive.compareAndSet(false, true)
        if (claimed) {
            primaryClaimedAtMs.set(System.currentTimeMillis())
            SecureLog.i(TAG, "primary_poller claimed")
        }
        return claimed
    }

    fun releasePrimaryPoller() {
        if (primaryPollerActive.getAndSet(false)) {
            primaryClaimedAtMs.set(0L)
            SecureLog.i(TAG, "primary_poller released")
        }
    }

    fun isPrimaryPollerActive(): Boolean = primaryPollerActive.get()

    fun shouldSkipFallbackPoll(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isPrimaryPollerActive() && !isPollActivityStale(nowMs)
    }

    fun onPollSuccess() {
        consecutiveFailures.set(0)
        lastPollAtMs.set(System.currentTimeMillis())
        lastError.set(null)
        // 收发恢复正常说明会话已重建（重新扫码登录），解除过期冷却
        sessionExpiredUntilMs.set(0L)
    }

    /**
     * 标记 iLink 会话过期（errcode=-14）：进入 1 小时冷却，期间轮询循环暂停收发；
     * 用户可见错误经 lastError 通道置为 [SESSION_EXPIRED_USER_MESSAGE]。
     * 重新扫码登录后 onPollSuccess / clearSessionExpired / reset 会解除冷却。
     */
    fun markSessionExpired(detail: String? = null, nowMs: Long = System.currentTimeMillis()) {
        sessionExpiredUntilMs.set(nowMs + SESSION_EXPIRED_COOLDOWN_MS)
        lastPollAtMs.set(nowMs)
        lastErrorAtMs.set(nowMs)
        lastError.set(
            "${com.yunian.ai.domain.wechat.WeChatFailureReason.POLL_AUTH.wireName}: " +
                (detail?.takeIf { it.isNotBlank() } ?: SESSION_EXPIRED_USER_MESSAGE),
        )
        SecureLog.w(TAG, "session_expired errcode=-14 cooldown=${SESSION_EXPIRED_COOLDOWN_MS / 1000}s detail=${detail.orEmpty().take(120)}")
    }

    fun clearSessionExpired() {
        sessionExpiredUntilMs.set(0L)
    }

    /** 会话是否处于过期冷却期（冷却自然到期后自动解除）。 */
    fun isSessionExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = sessionExpiredUntilMs.get()
        if (until <= 0L) return false
        if (nowMs >= until) {
            sessionExpiredUntilMs.set(0L)
            return false
        }
        return true
    }

    /**
     * 刷新轮询活跃时间戳（不发网络请求）：冷却暂停期间防止 watchdog 误判 primary stale
     * 而反复取消/重建 poll job。
     */
    fun touchPollActivity(nowMs: Long = System.currentTimeMillis()) {
        lastPollAtMs.set(nowMs)
    }

    fun onPollFailure(message: String? = null) {
        consecutiveFailures.incrementAndGet()
        lastErrorAtMs.set(System.currentTimeMillis())
        lastPollAtMs.set(System.currentTimeMillis())
        val reason = WeChatFailureReason.fromPollMessage(message)
        val summary = "${reason.wireName}: ${message.orEmpty().take(120)}"
        lastError.set(summary)
        SecureLog.w(TAG, "poll_failure failures=${consecutiveFailures.get()} $summary")
    }

    fun consecutiveFailures(): Int = consecutiveFailures.get()

    fun lastError(): String? = lastError.get()

    fun lastPollAtMs(): Long = lastPollAtMs.get()

    fun lastErrorAtMs(): Long = lastErrorAtMs.get()

    fun primaryClaimedAtMs(): Long = primaryClaimedAtMs.get()

    fun lastHealAtMs(): Long = lastHealAtMs.get()

    fun onWatchdogTick(nowMs: Long = System.currentTimeMillis()): Long {
        val prev = lastWatchdogTickAtMs.getAndSet(nowMs)
        if (prev > 0L) {
            val gap = nowMs - prev
            if (gap > WATCHDOG_STALL_THRESHOLD_MS) {
                watchdogStallCount.incrementAndGet()
                lastWatchdogStallMs.set(gap)
                SecureLog.w(TAG, "watchdog_stall gapMs=$gap count=${watchdogStallCount.get()}")
            }
            return gap
        }
        return 0L
    }

    fun watchdogStallCount(): Int = watchdogStallCount.get()

    fun lastWatchdogStallMs(): Long = lastWatchdogStallMs.get()

    fun shouldRotateSession(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastSessionRebuildAtMs.get()
        return last == 0L || nowMs - last >= SESSION_REBUILD_INTERVAL_MS
    }

    fun markSessionRebuilt(nowMs: Long = System.currentTimeMillis()) {
        lastSessionRebuildAtMs.set(nowMs)
    }

    fun isPollActivityStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastPollAtMs.get()
        if (last > 0L) {
            return nowMs - last >= STALE_POLL_MS
        }
        val claimedAt = primaryClaimedAtMs.get()
        if (claimedAt > 0L && primaryPollerActive.get()) {
            return nowMs - claimedAt >= STALE_POLL_MS
        }
        return false
    }

    fun evaluateWatchdog(nowMs: Long = System.currentTimeMillis()): WatchdogDecision {
        val primary = isPrimaryPollerActive()
        val stale = isPollActivityStale(nowMs)
        return when {
            primary && stale -> WatchdogDecision(
                needsAction = true,
                forceReleasePrimary = true,
                reason = "primary_stale ageMs=${nowMs - (lastPollAtMs.get().takeIf { it > 0 } ?: primaryClaimedAtMs.get())}",
            )
            !primary && stale -> WatchdogDecision(
                needsAction = true,
                forceReleasePrimary = false,
                reason = "fallback_stale ageMs=${nowMs - lastPollAtMs.get()}",
            )
            else -> WatchdogDecision(
                needsAction = false,
                forceReleasePrimary = false,
                reason = if (primary) "healthy_primary" else "healthy_or_idle",
            )
        }
    }

    fun tryBeginHeal(nowMs: Long = System.currentTimeMillis()): Boolean {
        while (true) {
            val last = lastHealAtMs.get()
            if (last > 0L && nowMs - last < HEAL_COOLDOWN_MS) {
                return false
            }
            if (lastHealAtMs.compareAndSet(last, nowMs)) {
                return true
            }
        }
    }

    fun nextBackoffMs(isTimeout: Boolean = false, isConnection: Boolean = false): Long {
        val failures = consecutiveFailures.get().coerceAtLeast(1)
        val base = when {
            isTimeout -> BASE_BACKOFF_MS
            isConnection -> BASE_BACKOFF_MS * 2
            else -> BASE_BACKOFF_MS
        }
        val exp = min(MAX_BACKOFF_MS, base * (1L shl (failures - 1).coerceAtMost(5)))
        val jitter = Random.nextLong(0, (exp / 5).coerceAtLeast(1))
        return min(MAX_BACKOFF_MS, exp + jitter)
    }

    fun healthSnapshot(
        openOutboxCount: Int = 0,
        pendingOutboxCount: Int = 0,
        failedOutboxCount: Int = 0,
        sendingOutboxCount: Int = 0,
        recentFailures: List<WeChatOutboxFailure> = emptyList(),
    ): WeChatChannelHealthSnapshot {
        return WeChatChannelHealthSnapshot(
            primaryPollerActive = isPrimaryPollerActive(),
            consecutiveFailures = consecutiveFailures(),
            lastPollAtMs = lastPollAtMs(),
            lastErrorAtMs = lastErrorAtMs(),
            lastError = lastError(),
            openOutboxCount = openOutboxCount,
            pendingOutboxCount = pendingOutboxCount,
            failedOutboxCount = failedOutboxCount,
            sendingOutboxCount = sendingOutboxCount,
            recentFailures = recentFailures,
            updatedAtMs = System.currentTimeMillis(),
            watchdogStallCount = watchdogStallCount(),
            lastWatchdogStallMs = lastWatchdogStallMs(),
        )
    }

    fun reset() {
        primaryPollerActive.set(false)
        consecutiveFailures.set(0)
        lastPollAtMs.set(0L)
        lastErrorAtMs.set(0L)
        lastError.set(null)
        primaryClaimedAtMs.set(0L)
        lastHealAtMs.set(0L)
        lastWatchdogTickAtMs.set(0L)
        watchdogStallCount.set(0)
        lastWatchdogStallMs.set(0L)
        lastSessionRebuildAtMs.set(0L)
        sessionExpiredUntilMs.set(0L)
    }

    data class WatchdogDecision(
        val needsAction: Boolean,
        val forceReleasePrimary: Boolean,
        val reason: String,
    )
}
