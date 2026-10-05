package com.yunian.ai.feature.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 开机广播接收器：启动 CompanionKeepAliveService + 排程 CompanionMessageWorker。
 *
 * Android 12+ BOOT_COMPLETED 是 FGS 启动豁免场景之一，但 OEM 可能收紧；
 * `CompanionKeepAliveService.start` 内部 catch Exception，外层无法感知失败，
 * 因此总是 enqueue 一次 [BootFallbackWorker] 作为幂等兜底（重复 startForegroundService
 * 系统层去重，无副作用）。
 *
 * goAsync + IO 协程：主线程立即返回，无 ANR；与 WeChatBootReceiver 模式完全对齐。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(AppDispatchers.io).launch {
            try {
                // 直接尝试 FGS（Android 12+ BOOT_COMPLETED 在白名单内；OEM 可能收紧）
                runCatching { CompanionKeepAliveService.start(context) }
                    .onFailure { SecureLog.w(TAG, "FGS start failed: ${it.message}") }

                // 幂等兜底：FGS.start 内部 catch，外层无法感知失败；
                // 让 expedited Worker 在 foreground 上下文二次拉起（重复调用无副作用）。
                runCatching {
                    val request = OneTimeWorkRequestBuilder<BootFallbackWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                        BootFallbackWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                }.onFailure { SecureLog.w(TAG, "fallback worker enqueue failed: ${it.message}") }

                CompanionMessageWorker.schedule(context)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
