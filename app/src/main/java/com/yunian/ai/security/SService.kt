package com.yunian.ai.security

import android.content.Intent
import com.yunian.ai.feature.notification.CompanionKeepAliveService

class SService : CompanionKeepAliveService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OnePieceShellGate.verifyBeforePayload(this)
        return super.onStartCommand(intent, flags, startId)
    }
}
