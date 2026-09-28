package com.yunian.ai.feature.wechat.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunian.ai.common.SecureLog
import com.yunian.ai.feature.wechat.data.WeChatTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WeChatAiReplyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val wechatUserId = inputData.getString(KEY_WECHAT_USER_ID) ?: ""
            val messageText = inputData.getString(KEY_MESSAGE_TEXT) ?: ""

            if (wechatUserId.isBlank() || messageText.isBlank()) {
                return@withContext Result.failure()
            }

            val tokenStore = WeChatTokenStore(applicationContext)

            if (!tokenStore.getAutoReply()) {
                return@withContext Result.success()
            }

            runCatching { WeChatChannelKeeper.ensureRunning(applicationContext) }

            val bridge = WeChatServiceLocator.chatBridge(applicationContext)
            bridge.handleTextMessage(wechatUserId, messageText)

            runCatching {
                WeChatServiceLocator.messageRepository(applicationContext).drainOutbox()
            }

            Result.success()
        } catch (e: Exception) {
            SecureLog.e(TAG, "Error processing message", e)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "WeChatAiReplyWorker"
        private const val WORK_NAME_PREFIX = "wechat_ai_reply_"
        private const val MAX_ATTEMPTS = 5
        const val KEY_WECHAT_USER_ID = "wechat_user_id"
        const val KEY_MESSAGE_TEXT = "message_text"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context, wechatUserId: String, messageText: String) {
            val inputData = Data.Builder()
                .putString(KEY_WECHAT_USER_ID, wechatUserId)
                .putString(KEY_MESSAGE_TEXT, messageText)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WeChatAiReplyWorker>()
                .setInputData(inputData)
                .setConstraints(networkConstraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("wechat_ai_reply")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$wechatUserId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )
            SecureLog.i(TAG, "enqueued ai reply for user=${wechatUserId.take(6)}***")
        }
    }
}
