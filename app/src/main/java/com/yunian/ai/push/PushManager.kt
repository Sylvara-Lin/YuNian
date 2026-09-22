package com.yunian.ai.push

import android.app.Application
import com.yunian.ai.common.RomUtils
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.vendor.HuaweiPushInitializer
import com.yunian.ai.push.vendor.OppoPushInitializer
import com.yunian.ai.push.vendor.VivoPushInitializer
import com.yunian.ai.push.vendor.XiaomiPushInitializer

object PushManager {

    private const val TAG = "PushManager"

    fun init(application: Application) {
        SecureLog.d(TAG, "Initializing vendor push on ${RomUtils.getRomDisplayName()}")

        when {
            RomUtils.isOppo -> OppoPushInitializer.register(application)
            RomUtils.isVivo -> VivoPushInitializer.register(application)
            RomUtils.isXiaomi -> XiaomiPushInitializer.register(application)
            RomUtils.isHuawei -> HuaweiPushInitializer.register(application)
            else -> SecureLog.d(TAG, "No vendor push match for this device")
        }
    }
}
