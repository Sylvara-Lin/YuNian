package com.yunian.ai.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yunian.ai.feature.notification.BootReceiver

class SReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OnePieceShellGate.verifyBeforePayload(context)
        BootReceiver().onReceive(context, intent)
    }
}
