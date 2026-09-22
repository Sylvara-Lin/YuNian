package com.yunian.ai.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.domain.AutomationTickProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.feature.notification.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

open class CompanionKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var timedOut = false

    @Volatile private var stopRequested = false
    private val handler = Handler(Looper.getMainLooper())
    private val tickScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private val wakeLockRunnable = object : Runnable {
        override fun run() {
            acquireWakeLock()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {

            CompanionMessageWorker.schedule(applicationContext)
            handler.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    private val automationTickRunnable = object : Runnable {
        override fun run() {
            tickScope.launch {
                runCatching {
                    ServiceRegistry.get(AutomationTickProvider::class.java)?.onTick()
                }.onFailure {
                    com.yunian.ai.common.SecureLog.w(
                        "KeepAliveService",
                        "automation tick failed: ${it.message}"
                    )
                }
            }
            handler.postDelayed(this, 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        handler.postDelayed(wakeLockRunnable, 5 * 60 * 1000L)
        handler.postDelayed(heartbeatRunnable, 60 * 60 * 1000L)
        handler.postDelayed(automationTickRunnable, 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.getBooleanExtra(EXTRA_STOP_REQUESTED, false) == true) {
            stopRequested = true
        }
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        timedOut = true

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {

        }
        stopSelf(startId)

        runCatching { CompanionMessageWorker.schedule(applicationContext) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(wakeLockRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(automationTickRunnable)
        tickScope.cancel()
        releaseWakeLock()
        super.onDestroy()

        if (!timedOut && !stopRequested) {
            start(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        if (!stopRequested) {
            start(applicationContext)
        }
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YuNian::CompanionKeepAlive"
        ).apply {
            setReferenceCounted(false)

            acquire(15 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        val channelId = "keep_alive_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "后台运行服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持虚拟恋人消息推送服务运行"
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
            .setContentTitle("予念")
            .setContentText("虚拟恋人正在守护你~")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {

                com.yunian.ai.common.SecureLog.w("KeepAliveService", "start FGS from background rejected: ${error.message}")
            }
        }

        fun safeStart(context: Context) {
            try {
                start(context)
            } catch (e: Exception) {

            }
        }

        fun stop(context: Context) {

            val intent = Intent().setClassName(context.packageName, SHELL_SERVICE_CLASS)
                .putExtra(EXTRA_STOP_REQUESTED, true)
            context.stopService(intent)
        }

        private const val EXTRA_STOP_REQUESTED = "stop_requested"
        private const val SHELL_SERVICE_CLASS = "com.yunian.ai.security.SService"
    }
}
