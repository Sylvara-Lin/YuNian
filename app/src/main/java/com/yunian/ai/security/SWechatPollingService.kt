package com.yunian.ai.security

import android.content.Intent
import com.yunian.ai.feature.wechat.service.WeChatPollingService

class SWechatPollingService : WeChatPollingService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OnePieceShellGate.verifyBeforePayload(this)
        return super.onStartCommand(intent, flags, startId)
    }
}
