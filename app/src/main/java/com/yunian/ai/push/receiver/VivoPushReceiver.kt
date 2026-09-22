package com.yunian.ai.push.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.dispatch.PushMessageDispatcher

class VivoPushReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VivoPush"

        private val CONTENT_KEYS = listOf("content", "msg", "message", "payload", "data")
        private val REG_ID_KEYS = listOf("regId", "reg_id", "clientId", "token")
        private val TITLE_KEYS = listOf("title", "notificationTitle", "notifyTitle")
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return

        val action = intent.action
        SecureLog.d(TAG, "onReceive action=$action")

        val regId = findFirstStringExtra(intent, REG_ID_KEYS)
        if (!regId.isNullOrBlank()) {
            PushMessageDispatcher.onTokenReceived(context, "vivo", regId)
        }

        val title = findFirstStringExtra(intent, TITLE_KEYS)
        val content = findFirstStringExtra(intent, CONTENT_KEYS)
        if (!content.isNullOrBlank()) {
            PushMessageDispatcher.onMessageReceived(context, title, content, intent.extras?.toPayloadMap())
        }
    }

    private fun findFirstStringExtra(intent: Intent, keys: List<String>): String? {
        for (key in keys) {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun android.os.Bundle?.toPayloadMap(): Map<String, String>? {
        this ?: return null
        return keySet()
            .filterNotNull()
            .mapNotNull { key ->
                getString(key)?.takeIf { it.isNotBlank() }?.let { key to it }
            }
            .toMap()
            .takeIf { it.isNotEmpty() }
    }
}
