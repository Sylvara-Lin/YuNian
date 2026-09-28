package com.yunian.ai.feature.wechat.service

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.feature.wechat.data.WeChatTokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WeChatChannelKeeper {

    private const val TAG = "WeChatChannelKeeper"
    private val mutex = Mutex()

    suspend fun ensureRunning(context: Context): Boolean = mutex.withLock {
        ensureRunningLocked(context.applicationContext)
    }

    suspend fun healIfNeeded(context: Context): Boolean = mutex.withLock {
        val app = context.applicationContext
        val loggedIn = runCatching {
            WeChatTokenStore(app).isLoggedIn()
        }.getOrDefault(false)
        if (!loggedIn) {
            return false
        }

        val decision = WeChatChannelRuntime.evaluateWatchdog()
        if (!decision.needsAction) {
            return false
        }
        if (!WeChatChannelRuntime.tryBeginHeal()) {
            SecureLog.d(TAG, "heal skipped cooldown: ${decision.reason}")
            return false
        }

        if (decision.forceReleasePrimary) {
            WeChatChannelRuntime.releasePrimaryPoller()
            SecureLog.w(TAG, "watchdog released stale primary: ${decision.reason}")
        } else {
            SecureLog.w(TAG, "watchdog heal: ${decision.reason}")
        }
        ensureRunningLocked(app)
        true
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        runCatching { WeChatPollingService.stop(app) }
        runCatching { WeChatPollingWorker.cancel(app) }
        WeChatChannelRuntime.reset()
        SecureLog.i(TAG, "channel stopped")
    }

    private suspend fun ensureRunningLocked(app: Context): Boolean {
        val loggedIn = runCatching {
            WeChatTokenStore(app).isLoggedIn()
        }.getOrDefault(false)
        if (!loggedIn) {
            SecureLog.d(TAG, "ensureRunning skipped: not logged in")
            return false
        }
        runCatching { WeChatPollingService.start(app) }
            .onFailure { SecureLog.w(TAG, "start FGS failed: ${it.message}") }
        runCatching { WeChatPollingWorker.schedule(app) }
            .onFailure { SecureLog.w(TAG, "schedule periodic worker failed: ${it.message}") }
        runCatching { WeChatPollingWorker.scheduleImmediate(app) }
            .onFailure { SecureLog.w(TAG, "schedule immediate worker failed: ${it.message}") }
        SecureLog.i(TAG, "ensureRunning: FGS + WM requested")
        return true
    }
}
