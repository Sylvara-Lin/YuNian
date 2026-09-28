package com.yunian.ai.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yunian.ai.feature.wechat.service.WeChatBootReceiver

class SWechatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OnePieceShellGate.verifyBeforePayload(context)
        WeChatBootReceiver().onReceive(context, intent)
    }
}
