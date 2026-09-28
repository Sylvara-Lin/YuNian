package com.yunian.ai.push.vendor

import android.app.Application
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.common.ApiException
import com.huawei.hms.push.HmsMessaging
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.dispatch.PushMessageDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object HuaweiPushInitializer {

    private const val TAG = "HuaweiPush"

    fun register(application: Application) {
        try {

            HmsMessaging.getInstance(application).isAutoInitEnabled = true
            requestToken(application)
        } catch (e: Exception) {
            SecureLog.e(TAG, "Failed to register Huawei push", e)
        }
    }

    private fun requestToken(application: Application) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val token = HmsInstanceId.getInstance(application)
                    .getToken(application.packageName, HmsMessaging.DEFAULT_TOKEN_SCOPE)
                if (!token.isNullOrBlank()) {
                    SecureLog.d(TAG, "Huawei token obtained")
                    PushMessageDispatcher.onTokenReceived(application, "huawei", token)
                }
            } catch (e: ApiException) {
                SecureLog.e(TAG, "Huawei getToken failed: ${e.statusCode}", e)
            } catch (e: Exception) {
                SecureLog.e(TAG, "Huawei getToken error", e)
            }
        }
    }
}
