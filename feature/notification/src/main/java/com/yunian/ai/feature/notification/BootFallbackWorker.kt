package com.yunian.ai.feature.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * BOOT_COMPLETED 后 CompanionKeepAliveService 启动失败的幂等兜底 Worker。
 *
 * Android 12+ BOOT_COMPLETED 是 FGS 启动豁免场景之一（官方文档），但 OEM（小米/OPPO/vivo/荣耀）
 * 可能进一步收紧；`CompanionKeepAliveService.start` 内部 catch Exception，外层无法感知失败。
 * 本 Worker 由 BootReceiver 总是 enqueue 一次（expedited OneTimeWorkRequest），在 foreground
 * 上下文再次调 `CompanionKeepAliveService.start`（幂等，重复 startForegroundService 系统层去重）。
 *
 * 与 WeChatAiReplyWorker 的 expedited 用法一致（WeChatAiReplyWorker.kt:78）。
 */
class BootFallbackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        CompanionKeepAliveService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "boot_fallback_keepalive"
    }
}
