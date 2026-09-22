package com.yunian.ai.feature.wechat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WeChatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(AppDispatchers.io).launch {
            try {
                val started = WeChatChannelKeeper.ensureRunning(context.applicationContext)
                SecureLog.i(TAG, "boot/replace action=$action ensureRunning=$started")
            } catch (error: Exception) {
                SecureLog.w(TAG, "boot ensureRunning failed: ${error.message}")
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "WeChatBootReceiver"
    }
}
