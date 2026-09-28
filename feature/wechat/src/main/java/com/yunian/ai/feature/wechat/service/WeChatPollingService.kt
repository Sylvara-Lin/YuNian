package com.yunian.ai.feature.wechat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.feature.wechat.R
import com.yunian.ai.feature.wechat.WeChatDebugLog
import com.yunian.ai.wechat.ilink.IlinkSessionExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

open class WeChatPollingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdogStarted = AtomicBoolean(false)
    private val pollingStarted = AtomicBoolean(false)

    private val pollerGeneration = AtomicInteger(0)

    @Volatile
    private var lastRestartAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        WeChatDebugLog.log("[PollingService] onCreate pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        renewWakeLock()

        if (pollJob == null || pollJob?.isActive != true) {
            startPolling()
        }
        startWatchdog()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        runCatching {
            if (WeChatServiceLocator.tokenStore(applicationContext).isLoggedInSync()) {
                scheduleRestartWithDebounce()
            }
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        Log.w(TAG, "Foreground service timeout reached, restarting immediately")
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {

        }
        stopSelf(startId)

        scheduleRestartWithDebounce()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        pollJob?.cancel()
        pollJob = null
        watchdogJob?.cancel()
        WeChatChannelRuntime.releasePrimaryPoller()
        releaseWakeLock()

        runCatching {
            val loggedIn = WeChatServiceLocator.tokenStore(applicationContext).isLoggedInSync()
            if (loggedIn) {
                scheduleRestartWithDebounce()
                WeChatPollingWorker.schedule(applicationContext)
                WeChatPollingWorker.scheduleImmediate(applicationContext)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun renewWakeLock() {
        releaseWakeLock()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:wechat-polling",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeLock = lock
            Log.d(TAG, "Partial wake lock acquired for ${WAKE_LOCK_TIMEOUT_MS}ms")
        } catch (error: Exception) {

            Log.w(TAG, "Wake lock acquire failed: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (_: Exception) {
            }
        }
        wakeLock = null
        Log.i(TAG, "Partial wake lock released")
    }

    private fun startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        watchdogJob = serviceScope.launch {
            try {
                while (isActive) {
                    delay(WeChatChannelRuntime.WATCHDOG_INTERVAL_MS)

                    val gapMs = WeChatChannelRuntime.onWatchdogTick()
                    renewWakeLock()

                    val rebuildReason = when {
                        gapMs > WeChatChannelRuntime.WATCHDOG_STALL_THRESHOLD_MS -> "watchdog-gap gapMs=$gapMs"
                        WeChatChannelRuntime.shouldRotateSession() -> "session-rotate"
                        else -> null
                    }
                    if (rebuildReason != null) {
                        runCatching {
                            WeChatServiceLocator.sdkClientManager(applicationContext)
                                .requestForceRebuild(rebuildReason)
                            WeChatChannelRuntime.markSessionRebuilt()
                            Log.w(TAG, "Session rebuilt: $rebuildReason")
                        }.onFailure { Log.w(TAG, "Session rebuild failed: ${it.message}") }
                    }
                    val decision = WeChatChannelRuntime.evaluateWatchdog()
                    if (!decision.needsAction) {
                        continue
                    }
                    Log.w(TAG, "Watchdog action: ${decision.reason}")
                    WeChatDebugLog.log("[PollingService] Watchdog ACTION: ${decision.reason} forceRelease=${decision.forceReleasePrimary}")
                    if (decision.forceReleasePrimary) {
                        WeChatChannelRuntime.releasePrimaryPoller()
                        restartPolling()
                    }
                    runCatching { WeChatChannelKeeper.healIfNeeded(applicationContext) }
                        .onFailure { Log.w(TAG, "Watchdog heal failed: ${it.message}") }
                }
            } finally {
                watchdogStarted.set(false)
            }
        }
        Log.i(TAG, "Watchdog started interval=${WeChatChannelRuntime.WATCHDOG_INTERVAL_MS}ms")
        WeChatDebugLog.log("[PollingService] Watchdog started")
    }

    private fun restartPolling() {
        pollJob?.cancel()
        pollJob = null
        startPolling()
    }

    private fun startPolling() {
        val generation = pollerGeneration.incrementAndGet()
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            var claimed = false
            try {
                pollingStarted.set(true)
                claimed = WeChatChannelRuntime.claimPrimaryPoller()
                if (!claimed) {
                    Log.w(TAG, "Primary poller already claimed; waiting for watchdog to release it")
                    WeChatDebugLog.log("[PollingService] Primary lease NOT acquired, poller parked")
                    return@launch
                }
                Log.i(TAG, "Primary poller lease acquired")
                WeChatDebugLog.log("[PollingService] Primary lease acquired, starting poll loop")

                val repository = WeChatServiceLocator.messageRepository(applicationContext)
                var pollCount = 0

                while (isActive) {
                    if (!repository.isLoggedIn()) {
                        Log.d(TAG, "Not logged in, stop polling")
                        WeChatDebugLog.log("[PollingService] Not logged in, stopping")
                        stopSelf()
                        return@launch
                    }

                    renewWakeLock()
                    pollCount++

                    // 会话过期（errcode=-14）冷却期：暂停轮询，等待用户重新扫码登录；
                    // 重新登录后首次成功轮询会解除冷却（onPollSuccess）。
                    if (WeChatChannelRuntime.isSessionExpired()) {
                        WeChatDebugLog.log("[PollingService] Session expired (errcode=-14), polling paused")
                        // 刷新活跃时间戳：防止 watchdog 在冷却期内误判 primary stale 反复重建 poll job
                        WeChatChannelRuntime.touchPollActivity()
                        delay(SESSION_EXPIRED_POLL_PAUSE_MS)
                        continue
                    }

                    try {
                        Log.d(TAG, "Polling messages...")
                        val result = repository.pollMessages(timeoutMs = TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS)
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            val msg = error?.message.orEmpty()

                            // iLink 会话过期：置冷却 1 小时并暂停收发，等待用户重新扫码
                            if (error is IlinkSessionExpiredException) {
                                WeChatChannelRuntime.markSessionExpired(msg)
                                WeChatDebugLog.log("[PollingService] Poll#$pollCount SESSION_EXPIRED (errcode=-14), paused ${SESSION_EXPIRED_POLL_PAUSE_MS / 1000}s")
                                delay(SESSION_EXPIRED_POLL_PAUSE_MS)
                                continue
                            }

                            Log.w(TAG, "Poll failed: $msg")
                            WeChatDebugLog.log("[PollingService] Poll#$pollCount FAILED: $msg")
                            WeChatChannelRuntime.onPollFailure(msg)

                            runCatching {
                                val drained = repository.drainOutbox()
                                if (drained > 0) Log.d(TAG, "Outbox drained $drained after poll failure")
                            }
                            val delayMs = WeChatChannelRuntime.nextBackoffMs(
                                isTimeout = msg.contains("timeout", ignoreCase = true),
                                isConnection = msg.contains("connection", ignoreCase = true),
                            )
                            Log.d(TAG, "Backoff ${delayMs}ms (failures=${WeChatChannelRuntime.consecutiveFailures()})")
                            delay(delayMs)
                        } else {
                            WeChatChannelRuntime.onPollSuccess()
                            val messages = result.getOrNull()?.messages.orEmpty()
                            if (messages.isNotEmpty()) {
                                Log.d(TAG, "Received ${messages.size} messages")
                                WeChatDebugLog.log("[PollingService] Poll#$pollCount OK messages=${messages.size}")
                            } else {
                                delay(POLL_IDLE_MS)
                                if (pollCount % 10 == 0) {
                                    WeChatDebugLog.log("[PollingService] Poll#$pollCount OK empty (heartbeat)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Polling error", e)
                        WeChatDebugLog.log("[PollingService] Poll#$pollCount EXCEPTION: ${e.message}")
                        WeChatChannelRuntime.onPollFailure(e.message)
                        runCatching { repository.drainOutbox() }
                        delay(WeChatChannelRuntime.nextBackoffMs())
                    }
                }
            } finally {
                if (claimed && pollerGeneration.get() == generation) {
                    WeChatChannelRuntime.releasePrimaryPoller()
                }
                pollingStarted.set(false)
            }
        }
    }

    private fun scheduleRestartWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastRestartAtMs < RESTART_DEBOUNCE_MS) {
            Log.w(TAG, "Restart debounced (last=${now - lastRestartAtMs}ms ago)")
            return
        }
        lastRestartAtMs = now
        WeChatPollingService.start(applicationContext)
        Log.i(TAG, "Scheduled immediate restart")
    }

    private fun createNotification(): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(Intent.ACTION_MAIN).apply {
            `package` = packageName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("微信通道")
            .setContentText("正在实时接收微信消息...")
            .setSmallIcon(R.drawable.ic_wechat_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "WeChatPollingService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "wechat_polling"
        private const val CHANNEL_NAME = "微信消息轮询"
        private const val CHANNEL_DESCRIPTION = "保持微信消息实时接收"

        private val WAKE_LOCK_TIMEOUT_MS =
            TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS + WeChatChannelRuntime.MAX_BACKOFF_MS + 180_000L

        private const val RESTART_DEBOUNCE_MS = 10_000L

        private const val POLL_IDLE_MS = 1_000L

        /** 会话过期（errcode=-14）冷却期内轮询循环的暂停间隔。 */
        private const val SESSION_EXPIRED_POLL_PAUSE_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {

                Log.w(TAG, "start FGS from background rejected: ${error.message}")
            }

            try {
                WeChatPollingWorker.scheduleImmediate(context)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            context.stopService(intent)
        }

        private const val SHELL_SERVICE_CLASS = "com.yunian.ai.security.SWechatPollingService"
    }
}
