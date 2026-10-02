package com.yunian.ai.feature.skills.tools

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rikka.shizuku.Shizuku

/**
 * Shizuku 特权通道（第一期：状态检测与授权引导）。
 * Shizuku 授权后可执行需要 ADB/ROOT 权限的操作（静默安装、强制停止应用等），
 * 特权动作工具将随第二期扩展。
 */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Shizuku 状态检测结果（工具与设置页共用） */
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val granted: Boolean,
    val hint: String,
)

/** 检测 Shizuku 安装/运行/授权三态（UI 与 AI 工具共用） */
fun checkShizukuStatus(context: Context): ShizukuStatus {
    val installed = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
    }.getOrDefault(false)
    val running = runCatching { Shizuku.pingBinder() }.getOrElse { false }
    val granted = running && runCatching {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrElse { false }

    val hint = when {
        granted -> "Shizuku 通道可用，可执行特权操作"
        running -> "Shizuku 正在运行但「予念」尚未授权：点下方「请求授权」，在系统弹窗中允许即可（无需去 Shizuku 里找入口）"
        installed -> "Shizuku 已安装但未运行：请打开 Shizuku 应用启动服务后再回来授权"
        else -> "未检测到 Shizuku：请从应用商店或官网安装 Shizuku，并通过无线调试或连接电脑启动"
    }
    return ShizukuStatus(installed, running, granted, hint)
}

/**
 * Shizuku 授权请求桥：一次「请求授权 + 结果回调」会话。
 *
 * Shizuku 的机制是**应用必须先主动请求授权**，才会出现在 Shizuku 的「可授权应用」列表里；
 * 从不请求 ⇒ 用户在 Shizuku 里根本看不到「予念」⇒ 无从授权（真机实测复现：
 * 旧实现只有状态检测、没有 `Shizuku.requestPermission`，因此永远停在「未授权」）。
 *
 * 用法：`val req = ShizukuPermissionRequest { granted -> ... }; req.request()`；
 * 调用方须在生命周期结束（Compose `onDispose` / `onDestroy`）时调用 [dispose] 注销监听，
 * 与 [request] 成对，避免监听泄漏。
 *
 * 版本说明：所用 API（`requestPermission(int)` / `addRequestPermissionResultListener` /
 * `removeRequestPermissionResultListener` / `OnRequestPermissionResultListener`）在
 * rikka.shizuku:api 13.x 均存在；版本由 gradle 版本目录统一管理，此处不硬编码。
 */
class ShizukuPermissionRequest(
    private val onResult: (granted: Boolean) -> Unit,
) {
    private val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            onResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }
    private var registered = false

    /** 是否已注册结果监听。 */
    val isListening: Boolean get() = registered

    /**
     * 发起授权请求（先注册结果监听再请求）。
     *
     * @return true = 已成功发出请求（Shizuku 正在运行且调用未抛异常）；
     *         false = Shizuku 未运行/不可用（调用方应引导用户先启动 Shizuku）。
     */
    fun request(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        if (!registered) {
            runCatching { Shizuku.addRequestPermissionResultListener(listener) }
                .onFailure { SecureLog.w(TAG, "addRequestPermissionResultListener failed: ${it.message}") }
            registered = true
        }
        return runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { SecureLog.w(TAG, "requestPermission failed: ${it.message}") }
            .isSuccess
    }

    /** 注销结果监听（与 [request] 成对；重复调用安全）。 */
    fun dispose() {
        if (!registered) return
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            .onFailure { SecureLog.w(TAG, "removeRequestPermissionResultListener failed: ${it.message}") }
        registered = false
    }

    companion object {
        /** 授权请求码（本应用自定义，任意 int；回调据此过滤）。 */
        const val REQUEST_CODE = 0x5348 // 'S''H'

        private const val TAG = "ShizukuPermission"
    }
}

/** 打开 Shizuku 应用（引导用户启动服务；失败静默，由调用方提示）。 */
fun openShizukuApp(context: Context) {
    runCatching {
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { intent ->
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }.onFailure { SecureLog.w("ShizukuTools", "open Shizuku app failed: ${it.message}") }
}

class ShizukuStatusTool(private val context: Context) : AiTool {
    override val name = "shizuku_status"
    override val description = "查询 Shizuku 特权通道状态：是否安装、是否运行、是否已授权（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "shizuku_status: 查询 Shizuku 特权通道状态。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String {
        val status = checkShizukuStatus(context)
        SecureLog.d(
            TAG,
            "shizuku status installed=${status.installed} running=${status.running} granted=${status.granted}",
        )
        return buildJsonObject {
            put("ok", true)
            put("installed", status.installed)
            put("running", status.running)
            put("granted", status.granted)
            put("hint", status.hint)
        }.toString()
    }

    private companion object {
        const val TAG = "ShizukuTools"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}

/** 注册 Shizuku 工具 */
fun registerShizukuTools(context: Context) {
    ToolRegistry.register(ShizukuStatusTool(context.applicationContext))
}
