package com.yunian.ai.database.cleanup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.MessageCache
import java.util.concurrent.TimeUnit

object DataCleanupManager {

    private const val TAG = "DataCleanupManager"
    private const val WORK_NAME = "lianyu_data_cleanup"
    private const val CLEANUP_INTERVAL_HOURS = 24L
    private const val HOT_MESSAGES_PER_CONVERSATION = 5_000

    private const val PREF_NAME = "data_cleanup"
    private const val KEY_LAST_CLEANUP = "last_cleanup_time"

    fun schedulePeriodicCleanup(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DataCleanupWorker>(
            CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        SecureLog.i(TAG, "Periodic data cleanup scheduled every $CLEANUP_INTERVAL_HOURS hours")
    }

    suspend fun cleanupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastCleanup = prefs.getLong(KEY_LAST_CLEANUP, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCleanup < CLEANUP_INTERVAL_HOURS * 60 * 60 * 1000L) return

        SecureLog.i(TAG, "Starting database maintenance (last run was ${now - lastCleanup}ms ago)")
        performMaintenance(context)
        prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
    }

    private suspend fun performMaintenance(context: Context) {
        val db = AppDatabase.getDatabase(context)

        try {
            val messageDao = db.messageDao()
            var archivedCount = 0
            listOf("chat", "group").forEach { type ->
                messageDao.getDistinctConversationIds(type).forEach { conversationId ->
                    val count = messageDao.archiveOldMessages(
                        conversationId,
                        type,
                        HOT_MESSAGES_PER_CONVERSATION
                    )
                    archivedCount += count

                    if (count > 0) {
                        when (type) {
                            "chat" -> MessageCache.evictChat(conversationId)
                            "group" -> MessageCache.evictGroup(conversationId)
                        }
                    }
                }
            }
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("PRAGMA optimize")
            sqlite.query("PRAGMA wal_checkpoint(PASSIVE)").close()
            SecureLog.i(TAG, "Database maintenance completed; archived $archivedCount messages")
        } catch (e: Exception) {
            SecureLog.e(TAG, "Database maintenance failed", e)
        }
    }
}

class DataCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            DataCleanupManager.cleanupIfNeeded(applicationContext)
            Result.success()
        } catch (e: Exception) {
            SecureLog.e("DataCleanupWorker", "Cleanup failed", e)
            Result.retry()
        }
    }
}
