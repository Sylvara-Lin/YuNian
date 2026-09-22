package com.yunian.ai.feature.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutomationFireWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_AUTOMATION_ID) ?: return@withContext Result.success()
        val store = AutomationStore(context)
        val automation = store.list().firstOrNull { it.id == id }
            ?: return@withContext Result.success()
        if (!automation.enabled) return@withContext Result.success()

        if (!AutomationSchedulePolicy.shouldFire(automation, System.currentTimeMillis())) {
            return@withContext Result.success()
        }

        AutomationScheduler.fireDue(context, automation)
        Result.success()
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"
    }
}
