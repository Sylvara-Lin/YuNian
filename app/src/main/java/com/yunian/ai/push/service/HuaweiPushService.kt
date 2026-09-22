package com.yunian.ai.push.service

import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.dispatch.PushMessageDispatcher

class HuaweiPushService : HmsMessageService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        SecureLog.d("HuaweiPush", "onNewToken")
        PushMessageDispatcher.onTokenReceived(this, "huawei", token)
    }

    override fun onMessageReceived(message: RemoteMessage?) {
        super.onMessageReceived(message)
        message ?: return
        val title = message.notification?.title
        val body = message.notification?.body
        val data = message.dataOfMap
        PushMessageDispatcher.onMessageReceived(this, title, body, data)
    }
}
