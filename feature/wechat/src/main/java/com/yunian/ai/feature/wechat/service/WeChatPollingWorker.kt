package com.yunian.ai.feature.wechat.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.wechat.ilink.IlinkSessionExpiredException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class WeChatPollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = WeChatServiceLocator.messageRepository(applicationContext)

        if (!repo.isLoggedIn()) {
            return Result.success()
        }

        // 会话过期（errcode=-14）冷却期内跳过 fallback 轮询：不发真实 getUpdates，
        // 避免命中 -14 后 markSessionExpired 续期 1h，导致"冷却自然到期自愈"不可达
        if (WeChatChannelRuntime.isSessionExpired()) {
            SecureLog.d(TAG, "session expired (errcode=-14), skip fallback poll")
            return Result.success()
        }

        runCatching { WeChatChannelKeeper.healIfNeeded(applicationContext) }
            .onFailure { SecureLog.w(TAG, "watchdog heal failed: ${it.message}") }

        if (!WeChatChannelRuntime.isPrimaryPollerActive()) {
            runCatching { WeChatPollingService.start(applicationContext) }
                .onFailure { SecureLog.w(TAG, "restart FGS from worker failed: ${it.message}") }
        }

        if (WeChatChannelRuntime.shouldSkipFallbackPoll()) {
            runCatching {
                val drained = repo.drainOutbox()
                if (drained > 0) {
                    SecureLog.d(TAG, "Primary poller active; drained outbox=$drained")
                }
            }
            return Result.success()
        }

        return try {
            val result = repo.pollMessages(timeoutMs = TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS)
            if (result.isSuccess) {
                WeChatChannelRuntime.onPollSuccess()

                runCatching { repo.drainOutbox() }
                Result.success()
            } else {
                val error = result.exceptionOrNull()
                if (error is IlinkSessionExpiredException) {
                    // iLink 会话过期（errcode=-14）：进入 1 小时冷却并暴露"需重新扫码"，不做无谓重试
                    WeChatChannelRuntime.markSessionExpired(error.message)
                } else {
                    WeChatChannelRuntime.onPollFailure(result.exceptionOrNull()?.message)
                }
                runCatching { repo.drainOutbox() }
                delay(WeChatChannelRuntime.nextBackoffMs())
                Result.retry()
            }
        } catch (e: Exception) {
            WeChatChannelRuntime.onPollFailure(e.message)
            runCatching { repo.drainOutbox() }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WeChatPollingWorker"
        private const val WORK_NAME = "wechat_polling"
        private const val IMMEDIATE_WORK_NAME = "wechat_polling_immediate"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WeChatPollingWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag("wechat_keepalive")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = OneTimeWorkRequestBuilder<WeChatPollingWorker>()
                .setConstraints(constraints)
                .addTag("wechat_keepalive")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
            SecureLog.i(TAG, "immediate poll worker enqueued")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}
