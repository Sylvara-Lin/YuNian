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
        running -> "Shizuku 正在运行但未授权：请打开 Shizuku 应用 → 授权「予念」"
        installed -> "Shizuku 已安装但未运行：请打开 Shizuku 应用启动服务"
        else -> "未检测到 Shizuku：请从应用商店或官网安装 Shizuku，并通过无线调试或连接电脑启动"
    }
    return ShizukuStatus(installed, running, granted, hint)
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
