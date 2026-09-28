package com.yunian.ai.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object OriginOSBatteryOptimizer {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return NativePermissionRequester.isIgnoringBatteryOptimizations(context)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        if (!RomUtils.isVivo && !RomUtils.isHuawei) {

            return openStandardBatterySettings(context) || openAppDetailsSettings(context)
        }

        val alreadyIgnoring = isIgnoringBatteryOptimizations(context)

        if (!alreadyIgnoring && openRequestIgnoreBatteryOptimizations(context)) {
            return true
        }

        if (RomUtils.isOriginOS6OrAbove()) {
            val intentO6 = Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.settings",
                    "com.vivo.settings.battery.BatteryManagerActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("target_page", "background_power")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (safeStartActivity(context, intentO6)) return true
        }

        val intent1 = Intent().apply {
            component = android.content.ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.safecenter.GuidePageActivity"
            )
            putExtra("package_name", context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent1)) return true

        val intent2 = Intent().apply {
            component = android.content.ComponentName(
                "com.vivo.settings",
                "com.vivo.settings.battery.BatteryOptimizationActivity"
            )
            putExtra("package_name", context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent2)) return true

        val intent3 = Intent().apply {
            component = android.content.ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity"
            )
            putExtra("tab_index", 2)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent3)) return true

        val intent4 = Intent().apply {
            component = android.content.ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.safecenter.SmartManagerStateActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent4)) return true

        if (openStandardBatterySettings(context)) return true

        return openAppDetailsSettings(context)
    }

    fun openAutoStartSettings(context: Context): Boolean {
        if (!RomUtils.isVivo) {
            return openAppDetailsSettings(context)
        }

        if (RomUtils.isOriginOS6OrAbove()) {
            val intentO6 = Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.settings",
                    "com.vivo.settings.application.AutostartManagerActivity"
                )
                putExtra("package_name", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (safeStartActivity(context, intentO6)) return true
        }

        val intent1 = Intent().apply {
            component = android.content.ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            putExtra("package_name", context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent1)) return true

        val intent2 = Intent().apply {
            component = android.content.ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.safecenter.BgStartUpManagerActivity"
            )
            putExtra("package_name", context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent2)) return true

        val intent3 = Intent().apply {
            component = android.content.ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent3)) return true

        return openAppDetailsSettings(context)
    }

    fun openBackgroundPowerSettings(context: Context): Boolean {
        if (!RomUtils.isVivo) {
            return openAppDetailsSettings(context)
        }

        if (RomUtils.isOriginOS6OrAbove()) {
            val intentO6 = Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.settings",
                    "com.vivo.settings.battery.BatteryManagerActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("target_page", "background_power")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (safeStartActivity(context, intentO6)) return true
        }

        val intent1 = Intent().apply {
            component = android.content.ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.safecenter.GuidePageActivity"
            )
            putExtra("package_name", context.packageName)
            putExtra("target_page", "background_power")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent1)) return true

        val intent2 = Intent().apply {
            component = android.content.ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.safecenter.SmartManagerStateActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent2)) return true

        val intent3 = Intent().apply {
            component = android.content.ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity"
            )
            putExtra("tab_index", 2)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent3)) return true

        val intent4 = Intent().apply {
            component = android.content.ComponentName(
                "com.vivo.abe",
                "com.vivo.abe.MainActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (safeStartActivity(context, intent4)) return true

        return openAppDetailsSettings(context)
    }

    fun openBackgroundPopupSettings(context: Context): Boolean {
        if (!RomUtils.isVivo) {
            return openAppDetailsSettings(context)
        }

        if (RomUtils.isOriginOS6OrAbove()) {
            val intentO6 = Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.KeepBGActivity"
                )
                putExtra("package_name", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (safeStartActivity(context, intentO6)) return true
        }

        val candidates = listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.KeepBGActivity",
            "com.iqoo.secure" to "com.iqoo.secure.safecenter.KeepBGActivity",
            "com.vivo.abe" to "com.vivo.abe.activity.KeepBGActivity"
        )
        val component = RomUtils.findAvailableComponent(context, candidates)
        val intent = if (component != null) {
            Intent().setComponent(component)
        } else {
            openAppDetailsSettings(context)
            return true
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return safeStartActivity(context, intent)
    }

    fun getFullBackgroundGuideSteps(context: Context): List<GuideStep> {
        return buildList {
            add(
                GuideStep(
                    title = "允许自启动",
                    description = "确保应用能在后台自动启动",
                    action = { openAutoStartSettings(context) }
                )
            )
            add(
                GuideStep(
                    title = "允许后台高耗电",
                    description = "防止系统清理后台进程",
                    action = { openBackgroundPowerSettings(context) }
                )
            )
            if (!isIgnoringBatteryOptimizations(context)) {
                add(
                    GuideStep(
                        title = "忽略电池优化",
                        description = "防止 Doze 模式限制后台运行",
                        action = { openBatteryOptimizationSettings(context) }
                    )
                )
            }
        }
    }

    fun getBatteryOptimizationGuideText(): String {
        return when {
            RomUtils.isOriginOS6OrAbove() -> {
                "设置 → 电池 → 后台耗电管理 → 找到「予念」→ 允许后台运行 / 高耗电"
            }
            RomUtils.isOriginOS3OrAbove() -> {
                "设置 → 电池 → 后台耗电管理 → 找到「予念」→ 允许后台高耗电"
            }
            RomUtils.isVivo -> {
                "i管家 → 电池管理 → 后台高耗电 → 找到「予念」→ 允许"
            }
            else -> {
                "设置 → 电池 → 电池优化 → 找到「予念」→ 不优化"
            }
        }
    }

    private fun openStandardBatterySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return safeStartActivity(context, intent)
    }

    private fun openRequestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (isIgnoringBatteryOptimizations(context)) return false
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return safeStartActivity(context, intent)
    }

    private fun openAppDetailsSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return safeStartActivity(context, intent)
    }

    private fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return try {

            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    data class GuideStep(
        val title: String,
        val description: String,
        val action: () -> Boolean
    )
}
