package com.yunian.ai.feature.qqbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.feature.qqbot.data.network.QQBotWebSocketClient
import com.yunian.ai.feature.qqbot.data.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class QQBotForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null
    private var watchdogJob: Job? = null

    /** 自重启去抖（与微信侧 WeChatPollingService.lastRestartAtMs 同语义）。 */
    @Volatile
    private var lastRestartAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {

            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        renewWakeLock()
        startWakeLockRenewer()
        ensureConnected()
        startConnectionWatchdog()

        return START_STICKY
    }

    private fun ensureConnected() {
        serviceScope.launch {
            try {
                val repository = QQBotServiceLocator.messageRepository(this@QQBotForegroundService)
                Log.i(TAG, "Connecting QQ Bot...")
                repository.connect()
                Log.i(TAG, "QQ Bot WebSocket connect invoked")

                val bridge = QQBotServiceLocator.chatBridge(this@QQBotForegroundService)
                bridge.start()
                Log.i(TAG, "QQ Bot chat bridge started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect QQ Bot", e)
            }
        }
    }

    private fun startConnectionWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isActive) break
                try {
                    val repository = QQBotServiceLocator.messageRepository(this@QQBotForegroundService)
                    if (repository.isLoggedIn()) {
                        val state = repository.connectionState.value
                        // 旧实现无条件跳过 CONNECTING，而 onClosing 又会取消握手看门狗，
                        // 两者叠加让「永久连接中」无人可救。改为：只要状态机判定卡死
                        // （中间态超时且无在途重连）就恢复，CONNECTING 不再被豁免。
                        val stuck = repository.isConnectionStuck()
                        if (state != ConnectionState.CONNECTED && stuck) {
                            Log.w(TAG, "QQ Bot connection stuck in state=$state, restoring channel")
                            repository.disconnect()
                            repository.connect()
                            QQBotServiceLocator.chatBridge(this@QQBotForegroundService).start()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Connection watchdog failed: ${e.message}")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        wakeLockJob?.cancel()
        watchdogJob?.cancel()
        watchdogJob = null
        runCatching {
            QQBotServiceLocator.chatBridge(this).stop()
            QQBotServiceLocator.messageRepository(this).disconnect()
        }
        serviceScope.cancel()
        // 与微信侧 WeChatPollingService.onDestroy 对齐的自重启（AGENTS.md「FGS 被杀后立即自重启」现状声明）：
        // 已登录 → FGS 立即自重启 + Worker 兜底；未登录（用户已登出）→ 不重启。
        runCatching {
            if (QQBotServiceLocator.tokenStore(applicationContext).hasAccount()) {
                scheduleRestartWithDebounce()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 App：与 onDestroy 同一套自重启判定（已登录才重启）。
        // manifest stopWithTask="false" 时 onTaskRemoved 与 onDestroy 互斥触发，不会同帧双调；
        // 但 stopWithTask="true" 的 OEM 变体或测试路径下同帧双调由 debounce 拦截。
        runCatching {
            if (QQBotServiceLocator.tokenStore(applicationContext).hasAccount()) {
                scheduleRestartWithDebounce()
            }
        }
    }

    /** 自重启：FGS 立即自重启 + Worker 兜底（与微信侧 WeChatPollingService.scheduleRestartWithDebounce 同构）。 */
    private fun scheduleRestartWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastRestartAtMs < RESTART_DEBOUNCE_MS) {
            Log.w(TAG, "Restart debounced (last=${now - lastRestartAtMs}ms ago)")
            return
        }
        lastRestartAtMs = now
        start(applicationContext)
        runCatching {
            val request = OneTimeWorkRequestBuilder<QQBotRestartWorker>()
                .setInitialDelay(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                QQBotRestartWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }.onFailure { Log.w(TAG, "schedule restart worker failed: ${it.message}") }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
        start(this)
    }

    private fun renewWakeLock() {
        releaseWakeLock()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:qqbot-websocket",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
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
    }

    private fun startWakeLockRenewer() {
        if (wakeLockJob?.isActive == true) return
        wakeLockJob = serviceScope.launch {
            while (isActive) {
                delay(WAKE_LOCK_RENEW_INTERVAL_MS)
                renewWakeLock()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QQ 机器人",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 QQ 机器人消息通道在线"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QQ 机器人运行中")
            .setContentText("正在接收 QQ 消息")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "QQBotFgService"
        private const val CHANNEL_ID = "qqbot_foreground"
        private const val NOTIFICATION_ID = 0x7162

        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 90 * 1000L
        private const val WATCHDOG_INTERVAL_MS = 30 * 1000L

        /** 自重启去抖间隔（与微信侧 WeChatPollingService.RESTART_DEBOUNCE_MS 同值）。 */
        private const val RESTART_DEBOUNCE_MS = 10_000L

        fun start(context: Context): Boolean {
            val intent = Intent(context, QQBotForegroundService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: IllegalStateException) {
                Log.w(TAG, "start FGS from background rejected: ${error.message}")
                scheduleBackgroundRetry(context)
                false
            } catch (error: Exception) {
                Log.w(TAG, "start FGS failed: ${error.message}")
                false
            }
        }

        private fun scheduleBackgroundRetry(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<QQBotRestartWorker>()
                    .setInitialDelay(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    QQBotRestartWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }.onFailure { Log.w(TAG, "schedule restart retry failed: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QQBotForegroundService::class.java))
        }
    }
}
