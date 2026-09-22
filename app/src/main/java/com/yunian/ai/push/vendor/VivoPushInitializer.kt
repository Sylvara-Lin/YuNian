package com.yunian.ai.push.vendor

import android.app.Application
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.PushConfig
import com.yunian.ai.push.dispatch.PushMessageDispatcher

object VivoPushInitializer {

    private const val TAG = "VivoPush"

    fun register(application: Application) {
        if (PushConfig.VIVO_APP_ID.isBlank() || PushConfig.VIVO_APP_KEY.isBlank()) {
            SecureLog.w(TAG, "vivo push keys not configured, skip")
            return
        }

        try {

            val pushClientClass = Class.forName("com.vivo.push.PushClient")
            val pushClient = pushClientClass.getMethod("getInstance", android.content.Context::class.java)
                .invoke(null, application)
            pushClientClass.getMethod("initialize").invoke(pushClient)

            val upsManagerClass = Class.forName("com.vivo.push.ups.VUpsManager")
            val callbackClass = Class.forName("com.vivo.push.ups.UPSRegisterCallback")
            val upsManager = upsManagerClass.getMethod("getInstance").invoke(null)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.name == "onResult") {
                    val codeResult = args?.getOrNull(0)
                    val returnCode = codeResult?.javaClass?.getMethod("getReturnCode")?.invoke(codeResult) as? Int ?: -1
                    val msg = codeResult?.javaClass?.getMethod("getMsg")?.invoke(codeResult) as? String
                    SecureLog.d(TAG, "registerToken code=$returnCode regId=$msg")
                    if (returnCode == 0 && !msg.isNullOrBlank()) {
                        PushMessageDispatcher.onTokenReceived(application, "vivo", msg)
                    }
                }
                null
            }
            upsManagerClass.getMethod(
                "registerToken",
                android.content.Context::class.java,
                String::class.java,
                String::class.java,
                callbackClass
            ).invoke(upsManager, application, PushConfig.VIVO_APP_ID, PushConfig.VIVO_APP_KEY, proxy)
            SecureLog.d(TAG, "vivo push registerToken called")
        } catch (e: ClassNotFoundException) {
            SecureLog.w(TAG, "vivo Push SDK not found, please add aar to app/libs")
        } catch (e: Exception) {
            SecureLog.e(TAG, "Failed to register vivo push", e)
        }
    }
}
