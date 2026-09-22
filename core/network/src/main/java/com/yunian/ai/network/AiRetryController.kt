package com.yunian.ai.network

import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AiRetryController(
    private val failureThreshold: Int = 3,
    private val successThreshold: Int = 2,
    private val cooldownMs: Long = 5_000,
    private val maxCooldownMs: Long = 60_000
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val consecutiveSuccesses = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    private val currentCooldown = AtomicLong(0)

    val isOpen: Boolean get() = state.get() == State.OPEN

    fun recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            val successes = consecutiveSuccesses.incrementAndGet()
            if (successes >= successThreshold) {
                reset()
                SecureLog.i("AiRetryController", "Circuit breaker -> CLOSED after $successes successes")
            }
        }
    }

    fun recordFailure() {
        val now = System.currentTimeMillis()
        lastFailureTime.set(now)
        val failures = consecutiveFailures.incrementAndGet()

        when (state.get()) {
            State.CLOSED -> {
                if (failures >= failureThreshold) {
                    val cd = currentCooldown.get()
                    val newCooldown = if (cd == 0L) cooldownMs else (cd * 2).coerceAtMost(maxCooldownMs)
                    currentCooldown.set(newCooldown)
                    state.set(State.OPEN)
                    SecureLog.w("AiRetryController",
                        "Circuit breaker -> OPEN after $failures failures, cooldown=${newCooldown}ms")
                }
            }
            State.HALF_OPEN -> {
                state.set(State.OPEN)
                val newCd = (currentCooldown.get() * 2).coerceAtMost(maxCooldownMs)
                currentCooldown.set(newCd)
                SecureLog.w("AiRetryController",
                    "Half-open probe failed -> OPEN, cooldown=${newCd}ms")
            }
            State.OPEN -> {  }
        }
    }

    suspend fun tryAcquire(): Boolean {
        while (true) {
            when (state.get()) {
                State.CLOSED -> return true
                State.HALF_OPEN -> {

                    return consecutiveSuccesses.get() < successThreshold
                }
                State.OPEN -> {
                    val elapsed = System.currentTimeMillis() - lastFailureTime.get()
                    val cd = currentCooldown.get()
                    if (elapsed >= cd) {
                        state.set(State.HALF_OPEN)
                        consecutiveSuccesses.set(0)
                        SecureLog.i("AiRetryController", "Cooldown elapsed -> HALF_OPEN")
                        return true
                    }

                    SecureLog.w("AiRetryController",
                        "Request blocked: circuit OPEN, remaining=${cd - elapsed}ms")
                    return false
                }
            }
        }
    }

    private fun reset() {
        state.set(State.CLOSED)
        consecutiveFailures.set(0)
        consecutiveSuccesses.set(0)
        lastFailureTime.set(0)
    }
}

class AiRateLimiter(
    private val maxTokens: Int = 60,
    private val refillTokens: Int = 1,
    private val refillIntervalMs: Long = 1000
) {
    private val tokens = AtomicInteger(maxTokens)
    private val lastRefill = AtomicLong(System.currentTimeMillis())
    private val mutex = Mutex()

    suspend fun tryAcquire(): Boolean = mutex.withLock {
        refill()
        if (tokens.get() > 0) {
            tokens.decrementAndGet()
            true
        } else {
            SecureLog.w("AiRateLimiter", "Rate limit hit: bucket empty")
            false
        }
    }

    val availableTokens: Int
        get() {
            refillSync()
            return tokens.get()
        }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefill.get()
        val refillCount = (elapsed / refillIntervalMs).toInt()
        if (refillCount > 0) {
            val newTokens = (tokens.get() + refillCount * refillTokens).coerceAtMost(maxTokens)
            tokens.set(newTokens)
            lastRefill.set(now)
        }
    }

    private fun refillSync() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefill.get()
        val refillCount = (elapsed / refillIntervalMs).toInt()
        if (refillCount > 0) {
            val newTokens = (tokens.get() + refillCount * refillTokens).coerceAtMost(maxTokens)
            tokens.set(newTokens)
            lastRefill.set(now)
        }
    }
}
