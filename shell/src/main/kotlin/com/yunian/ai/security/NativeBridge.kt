package com.yunian.ai.security

import android.content.Context

object NativeBridge {

    @JvmStatic
    external fun verifySignature(context: Context): Boolean

    @JvmStatic
    external fun isSafe(): Boolean

    @JvmStatic
    external fun isDeviceRooted(): Boolean

    @JvmStatic
    external fun isHookDetected(): Boolean

    @JvmStatic
    external fun isDebugged(): Boolean
}
