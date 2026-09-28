package com.yunian.ai.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return NativePermissionRequester.isIgnoringBatteryOptimizations(context)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) {
            openBatteryOptimizationSettings(context)
            return
        }
        if (RomUtils.isVivo || RomUtils.isHuawei) {

            if (!OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)) {
                NativePermissionRequester.requestIgnoreBatteryOptimizations(context)
            }
            return
        }
        NativePermissionRequester.requestIgnoreBatteryOptimizations(context)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (RomUtils.isVivo || RomUtils.isHuawei) {
            OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)
            return
        }
        if (RomUtils.isOppo) {
            OppoVivoAdaptationHelper.openBatteryOptimizationSettings(context)
            return
        }
        NativePermissionRequester.openBatteryOptimizationSettings(context)
    }

    fun openAutoStartSettings(context: Context) {
        if (RomUtils.isOppoOrVivo()) {
            OppoVivoAdaptationHelper.openAutoStartSettings(context)
            return
        }

        val intent = Intent().apply {
            when {
                RomUtils.isXiaomi -> {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                RomUtils.isHuawei -> {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                }
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> {
                    component = android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                }
                Build.MANUFACTURER.equals("oneplus", ignoreCase = true) -> {
                    component = android.content.ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        safeStartActivity(context, intent)
    }

    fun openBackgroundPowerSettings(context: Context) {
        if (RomUtils.isOppoOrVivo()) {
            OppoVivoAdaptationHelper.openBackgroundPowerSettings(context)
            return
        }
        openAppDetailsSettings(context)
    }

    fun openAppDetailsSettings(context: Context) {
        OppoVivoAdaptationHelper.openAppDetailsSettings(context)
    }

    fun openNotificationSettings(context: Context) {
        OppoVivoAdaptationHelper.openNotificationSettings(context)
    }

    private fun safeStartActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }
}
