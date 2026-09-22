package com.yunian.ai.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object NativePermissionRequester {

    fun getAllRequiredPermissions(): Array<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }

        }.toTypedArray()
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return getAllRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun shouldShowRationale(activity: FragmentActivity, permission: String): Boolean {
        return activity.shouldShowRequestPermissionRationale(permission)
    }

    fun createBatchPermissionLauncher(
        activity: FragmentActivity,
        onAllGranted: () -> Unit,
        onResult: (PermissionResult) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val deniedPermissions = permissions.filter { !it.value }.keys.toList()
            if (deniedPermissions.isEmpty()) {
                onAllGranted()
                return@registerForActivityResult
            }

            val grantedPermissions = permissions.filter { it.value }.keys.toList()
            val rationalePermissions = deniedPermissions.filter { activity.shouldShowRequestPermissionRationale(it) }
            val permanentlyDeniedPermissions = deniedPermissions.filter { !activity.shouldShowRequestPermissionRationale(it) }

            onResult(
                PermissionResult(
                    grantedPermissions = grantedPermissions,
                    deniedPermissions = deniedPermissions,
                    rationalePermissions = rationalePermissions,
                    permanentlyDeniedPermissions = permanentlyDeniedPermissions
                )
            )
        }
    }

    fun createSinglePermissionLauncher(
        activity: FragmentActivity,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ): ActivityResultLauncher<String> {
        return activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) onGranted() else onDenied()
        }
    }

    fun requestAllPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(getAllRequiredPermissions())
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

    fun openBatteryOptimizationSettings(context: Context) {
        if (RomUtils.isVivo || RomUtils.isHuawei) {
            OriginOSBatteryOptimizer.openBatteryOptimizationSettings(context)
            return
        }
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        safeStartActivity(context, intent)
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

            SecureLog.w(
                "NativePermissionRequester",
                "requestIgnoreBatteryOptimizations failed: ${e.message}, fallback to settings page",
            )
            openBatteryOptimizationSettings(context)
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> "通知权限"
            Manifest.permission.CAMERA -> "相机权限"
            Manifest.permission.RECORD_AUDIO -> "麦克风权限"
            Manifest.permission.READ_MEDIA_IMAGES -> "读取图片权限"
            Manifest.permission.READ_MEDIA_VIDEO -> "读取视频权限"
            Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储权限"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入存储权限"
            else -> "未知权限"
        }
    }

    fun getPermissionRationale(permission: String): String {
        return when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> "需要通知权限才能接收虚拟恋人的消息提醒"
            Manifest.permission.CAMERA -> "需要相机权限才能拍摄照片"
            Manifest.permission.RECORD_AUDIO -> "需要麦克风权限才能录制语音消息"
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_EXTERNAL_STORAGE -> "需要存储权限才能选择图片"
            else -> "需要此权限以使用完整功能"
        }
    }

    private fun safeStartActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {

            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (_: Exception) {

            }
        }
    }

    data class PermissionResult(
        val grantedPermissions: List<String> = emptyList(),
        val deniedPermissions: List<String> = emptyList(),
        val rationalePermissions: List<String> = emptyList(),
        val permanentlyDeniedPermissions: List<String> = emptyList()
    ) {
        val allGranted: Boolean get() = deniedPermissions.isEmpty()
    }
}
