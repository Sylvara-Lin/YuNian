package com.yunian.ai.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object OppoVivoAdaptationHelper {

    fun needGuide(): Boolean = RomUtils.isOppoOrVivo()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimizations(context)) {
            openBatteryOptimizationSettings(context)
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {

            openBatteryOptimizationSettings(context)
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (RomUtils.isVivo || RomUtils.isHuawei) {
            OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)
            return
        }
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        safeStartActivity(context, intent)
    }

    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        safeStartActivity(context, intent)
    }

    fun openAutoStartSettings(context: Context) {
        val candidates = when {
            RomUtils.isOppo -> getOppoAutoStartCandidates()
            RomUtils.isVivo -> getVivoAutoStartCandidates()
            else -> emptyList()
        }

        val component = RomUtils.findAvailableComponent(context, candidates)
        val intent = if (component != null) {
            Intent().setComponent(component)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        safeStartActivity(context, intent)
    }

    fun openBackgroundPowerSettings(context: Context) {
        val candidates = when {
            RomUtils.isOppo -> getOppoBackgroundPowerCandidates()
            RomUtils.isVivo -> getVivoBackgroundPowerCandidates()
            else -> emptyList()
        }

        val component = RomUtils.findAvailableComponent(context, candidates)
        val intent = if (component != null) {
            Intent().setComponent(component)
        } else {
            openAppDetailsSettings(context)
            return
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        safeStartActivity(context, intent)
    }

    fun openBackgroundPopupSettings(context: Context) {
        val candidates = when {
            RomUtils.isOppo -> getOppoBackgroundPopupCandidates()
            RomUtils.isVivo -> getVivoBackgroundPopupCandidates()
            else -> emptyList()
        }

        val component = RomUtils.findAvailableComponent(context, candidates)
        val intent = if (component != null) {
            Intent().setComponent(component)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        safeStartActivity(context, intent)
    }

    fun openVivoGodModeSettings(context: Context) {
        if (!RomUtils.isVivo) {
            openBackgroundPowerSettings(context)
            return
        }
        val candidates = listOf(
            "com.iqoo.secure" to "com.iqoo.secure.safecenter.SmartManagerStateActivity",
            "com.vivo.abe" to "com.vivo.abe.MainActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity"
        )
        val component = RomUtils.findAvailableComponent(context, candidates)
        val intent = if (component != null) {
            Intent().setComponent(component)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        safeStartActivity(context, intent)
    }

    fun openFullBackgroundGuide(context: Context) {
        openAutoStartSettings(context)
        openBackgroundPowerSettings(context)
        if (!isIgnoringBatteryOptimizations(context)) {
            requestIgnoreBatteryOptimizations(context)
        }
    }

    fun getGuideSteps(): List<String> {
        return when {
            RomUtils.isOppo -> listOf(
                "允许“自启动”",
                "允许“后台运行”或关闭省电模式限制",
                "将电池优化设为“不优化”",
                "开启通知权限"
            )
            RomUtils.isVivo -> listOf(
                "在 i管家 中允许“自启动”",
                "在 i管家 → 电池管理 中加入“后台高耗电”白名单",
                "设置 → 电池 → 电池优化 → 选择本应用 → 不优化",
                "关闭“神隐模式”限制（如存在）",
                "开启通知权限"
            )
            else -> listOf(
                "允许自启动 / 后台运行",
                "忽略电池优化",
                "开启通知权限"
            )
        }
    }

    private fun getOppoAutoStartCandidates(): List<Pair<String, String>> = listOf(

        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",

        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",

        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",

        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity"
    )

    private fun getVivoAutoStartCandidates(): List<Pair<String, String>> = listOf(

        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",

        "com.iqoo.secure" to "com.iqoo.secure.safecenter.BgStartUpManagerActivity",

        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",

        "com.vivo.abe" to "com.vivo.abe.ui.ExActivity"
    )

    private fun getOppoBackgroundPowerCandidates(): List<Pair<String, String>> = listOf(

        "com.coloros.safecenter" to "com.coloros.safecenter.powermanager.PowerConsumptionActivity",

        "com.oplus.battery" to "com.oplus.battery.CompeletePowerControlActivity",

        "com.realme.safecenter" to "com.realme.safecenter.power.PowerConsumptionActivity",

        "com.oppo.safe" to "com.oppo.safe.power.PowerConsumptionActivity"
    )

    private fun getVivoBackgroundPowerCandidates(): List<Pair<String, String>> = listOf(

        "com.iqoo.secure" to "com.iqoo.secure.safecenter.GuidePageActivity",

        "com.vivo.abe" to "com.vivo.abe.MainActivity",

        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.PurviewTabActivity"
    )

    private fun getOppoBackgroundPopupCandidates(): List<Pair<String, String>> = listOf(

        "com.coloros.safecenter" to "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity",

        "com.oplus.safecenter" to "com.oplus.safecenter.permission.floatwindow.FloatWindowListActivity",

        "com.oppo.safe" to "com.oppo.safe.permission.PermissionTopActivity"
    )

    private fun getVivoBackgroundPopupCandidates(): List<Pair<String, String>> = listOf(

        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.KeepBGActivity",

        "com.iqoo.secure" to "com.iqoo.secure.safecenter.KeepBGActivity",

        "com.vivo.abe" to "com.vivo.abe.activity.KeepBGActivity"
    )

    private fun safeStartActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {

            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {

            }
        }
    }
}
