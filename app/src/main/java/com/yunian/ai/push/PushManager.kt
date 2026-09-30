package com.yunian.ai.push

import android.app.Application
import com.yunian.ai.common.RomUtils
import com.yunian.ai.common.SecureLog
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
            // 华为/鸿蒙（EMUI / HarmonyOS）：**刻意不初始化**（2026-09-30）
            // HMS Push 在本工程无法工作：仓库内无 agconnect-services.json（HMS 拿不到 app_id），
            // 且 app/src/shell/AndroidManifest.xml 已移除 HMS 核心 Provider（HMSCoreProvider /
            // AGConnectInitializeProvider），初始化链本就断裂。
            // 但在华为设备上初始化仍会去拉起一个注定失败的 HMS 组件 → 安装/启动即闪退。
            // 故此处不再调用 HuaweiPushInitializer.register()。
            // 恢复条件见 app/src/main/AndroidManifest.xml 中 HuaweiPushService 处的说明。
            RomUtils.isHuawei -> SecureLog.w(
                TAG, "Huawei/HarmonyOS push intentionally disabled (HMS not configured); skipped"
            )
            else -> SecureLog.d(TAG, "No vendor push match for this device")
        }
    }
}
