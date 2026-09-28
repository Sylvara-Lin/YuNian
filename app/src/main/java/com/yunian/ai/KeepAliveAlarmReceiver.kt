package com.yunian.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.domain.AutomationTickProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.feature.notification.CompanionKeepAliveService
import com.yunian.ai.feature.notification.CompanionMessageWorker
import com.yunian.ai.feature.qqbot.service.QQBotForegroundService
import com.yunian.ai.feature.qqbot.service.QQBotServiceLocator
import com.yunian.ai.feature.wechat.service.WeChatChannelKeeper
import com.yunian.ai.feature.wechat.service.WeChatChannelRuntime
import com.yunian.ai.feature.wechat.service.WeChatServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class KeepAliveAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAliveAlarmScheduler.ACTION_KEEP_ALIVE) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
        scope.launch {
            var wakeLock: PowerManager.WakeLock? = null
            try {
                wakeLock = acquireWakeLock(context)

                KeepAliveAlarmScheduler.scheduleNext(context)
                withTimeoutOrNull(TimeoutBudgets.BROADCAST_GOASYNC_MS - 500L) {
                    performKeepAliveCheck(context.applicationContext)
                }
            } catch (e: Exception) {
                SecureLog.w(TAG, "keep-alive tick failed: ${e.message}")
            } finally {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                runCatching { pendingResult.finish() }
                scope.cancel()
            }
        }
    }

    private suspend fun performKeepAliveCheck(app: Context) {

        runCatching { WeChatChannelKeeper.healIfNeeded(app) }
            .onFailure { SecureLog.w(TAG, "wechat heal failed: ${it.message}") }

        val repo = WeChatServiceLocator.messageRepository(app)
        if (repo.isLoggedIn() && !WeChatChannelRuntime.shouldSkipFallbackPoll()) {
            val result = withTimeoutOrNull(PROBE_POLL_MS) {
                repo.pollMessages()
            }
            when {
                result == null -> SecureLog.d(TAG, "probe poll timed out (channel idle)")
                result.isFailure -> {
                    WeChatChannelRuntime.onPollFailure(result.exceptionOrNull()?.message)
                    SecureLog.w(TAG, "probe poll failed: ${result.exceptionOrNull()?.message}")
                }
                else -> {
                    WeChatChannelRuntime.onPollSuccess()
                    val count = result.getOrNull()?.messages?.size ?: 0
                    if (count > 0) SecureLog.d(TAG, "probe poll delivered $count messages")
                }
            }
        }

        runCatching { WeChatChannelKeeper.ensureRunning(app) }
            .onFailure { SecureLog.w(TAG, "wechat ensureRunning failed: ${it.message}") }

        runCatching { CompanionKeepAliveService.safeStart(app) }
            .onFailure { SecureLog.w(TAG, "keepalive restart failed: ${it.message}") }
        runCatching { CompanionMessageWorker.schedule(app) }
            .onFailure { SecureLog.w(TAG, "companion worker schedule failed: ${it.message}") }

        runCatching {
            if (QQBotServiceLocator.tokenStore(app).isLoggedIn()) {
                QQBotForegroundService.start(app)
            }
        }.onFailure { SecureLog.w(TAG, "qqbot restart failed: ${it.message}") }

        runCatching {
            ServiceRegistry.get(AutomationTickProvider::class.java)?.onTick()
        }.onFailure { SecureLog.w(TAG, "automation tick failed: ${it.message}") }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${context.packageName}:keepalive-alarm",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "KeepAliveAlarm"

        private const val PROBE_POLL_MS = 5_000L
        private const val WAKE_LOCK_MS = 30_000L
    }
}

object KeepAliveAlarmScheduler {

    const val ACTION_KEEP_ALIVE = "com.yunian.ai.action.KEEP_ALIVE_TICK"

    private const val INTERVAL_MS = 8 * 60 * 1000L
    private const val REQUEST_CODE = 0x5A11
    private const val TAG = "KeepAliveAlarm"

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            Intent(app, KeepAliveAlarmReceiver::class.java).setAction(ACTION_KEEP_ALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        val exactAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        runCatching {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.onFailure { e ->

            SecureLog.w(TAG, "exact alarm rejected (${e.message}), fallback to allowWhileIdle")
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }.onFailure { inner ->
                SecureLog.w(TAG, "allowWhileIdle alarm failed: ${inner.message}")
            }
        }
        SecureLog.d(TAG, "heartbeat scheduled in ${INTERVAL_MS}ms (exact=$exactAllowed)")
    }
}
