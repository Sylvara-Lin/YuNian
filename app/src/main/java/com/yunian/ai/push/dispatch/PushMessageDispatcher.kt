package com.yunian.ai.push.dispatch

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.feature.notification.NotificationHelper

object PushMessageDispatcher {

    private const val TAG = "PushDispatcher"
    private const val PREFS_NAME = "vendor_push_tokens"

    fun onTokenReceived(context: Context, vendor: String, token: String) {
        SecureLog.d(TAG, "Token received from $vendor")
        saveToken(context, vendor, token)

    }

    fun onMessageReceived(context: Context, title: String?, content: String?, payload: Map<String, String>?) {
        SecureLog.d(TAG, "Message from vendor: title=$title content=$content")
        val safeTitle = title?.takeIf { it.isNotBlank() } ?: "予念"
        val safeContent = content?.takeIf { it.isNotBlank() } ?: "您有一条新消息"
        NotificationHelper.showCompanionMessageNotification(context, safeTitle, safeContent, companionId = 0L)
    }

    fun saveToken(context: Context, vendor: String, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${vendor}_token", token)
            .apply()
    }

    fun getToken(context: Context, vendor: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("${vendor}_token", null)
    }
}
