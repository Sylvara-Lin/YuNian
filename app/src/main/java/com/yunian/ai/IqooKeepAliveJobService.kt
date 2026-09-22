package com.yunian.ai

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import com.yunian.ai.common.RomUtils
import com.yunian.ai.common.SecureLog
import com.yunian.ai.feature.notification.CompanionKeepAliveService
import com.yunian.ai.feature.notification.CompanionMessageWorker

class IqooKeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        SecureLog.i("IqooKeepAliveJobService", "Job started on ${RomUtils.getRomDisplayName()}")

        Thread({
            try {
                performKeepAliveCheck()
            } catch (e: Exception) {
                SecureLog.e("IqooKeepAliveJobService", "Keep-alive check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }, "iqoo-keepalive").start()

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        SecureLog.w("IqooKeepAliveJobService", "Job stopped prematurely by system")

        return true
    }

    private fun performKeepAliveCheck() {
        val context = applicationContext

        KeepAliveAlarmScheduler.scheduleNext(context)

        try {
            CompanionKeepAliveService.safeStart(context)
            SecureLog.d("IqooKeepAliveJobService", "Keep-alive service check passed")
        } catch (e: Exception) {
            SecureLog.w("IqooKeepAliveJobService", "Failed to restart keep-alive service: ${e.message}")
        }

        try {
            CompanionMessageWorker.schedule(context)
            SecureLog.d("IqooKeepAliveJobService", "WorkManager check passed")
        } catch (e: Exception) {
            SecureLog.w("IqooKeepAliveJobService", "Failed to schedule WorkManager: ${e.message}")
        }

        runCatching {
            kotlinx.coroutines.runBlocking {
                com.yunian.ai.feature.wechat.service.WeChatChannelKeeper.ensureRunning(context)
            }
        }.onFailure {
            SecureLog.w("IqooKeepAliveJobService", "WeChat ensureRunning failed: ${it.message}")
        }

        runCatching {
            val loggedIn = kotlinx.coroutines.runBlocking {
                com.yunian.ai.feature.qqbot.service.QQBotServiceLocator.tokenStore(context).isLoggedIn()
            }
            if (loggedIn) {
                com.yunian.ai.feature.qqbot.service.QQBotForegroundService.start(context)
            }
        }.onFailure {
            SecureLog.w("IqooKeepAliveJobService", "QQ FGS start failed: ${it.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            SecureLog.i("IqooKeepAliveJobService", "Battery optimization ignored: $isIgnoring")
        }
    }
}
