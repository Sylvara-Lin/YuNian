package com.yunian.ai.push.vendor

import android.app.Application
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.PushConfig

object XiaomiPushInitializer {

    private const val TAG = "XiaomiPush"

    fun register(application: Application) {
        if (PushConfig.XIAOMI_APP_ID.isBlank() || PushConfig.XIAOMI_APP_KEY.isBlank()) {
            SecureLog.w(TAG, "Xiaomi push keys not configured, skip")
            return
        }

        try {
            val clazz = Class.forName("com.xiaomi.mipush.sdk.MiPushClient")
            clazz.getMethod(
                "registerPush",
                android.content.Context::class.java,
                String::class.java,
                String::class.java
            ).invoke(null, application, PushConfig.XIAOMI_APP_ID, PushConfig.XIAOMI_APP_KEY)
            SecureLog.d(TAG, "Xiaomi push registerPush called")
        } catch (e: ClassNotFoundException) {
            SecureLog.w(TAG, "MiPush SDK not found, please add aar to app/libs")
        } catch (e: Exception) {
            SecureLog.e(TAG, "Failed to register Xiaomi push", e)
        }
    }
}
