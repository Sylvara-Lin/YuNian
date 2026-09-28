package com.yunian.ai.feature.qqbot.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class QQBotRestartWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        QQBotForegroundService.start(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "qqbot_restart_retry"
    }
}
