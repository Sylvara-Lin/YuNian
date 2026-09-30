package com.yunian.ai.common.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.InputStream

/**
 * 系统级进程退出捕获（`ActivityManager.getHistoricalProcessExitInfos()`，**API 30+**）。
 *
 * 为什么必须要有它：`Thread.setDefaultUncaughtExceptionHandler` 只能捕获 **Java 崩溃**。
 * 而华为等设备上「初始化期/底层」崩溃大概率是 **native 崩溃（SIGSEGV 等）**，
 * 以及 ANR、被 LMK 杀 —— 这些场景 Java handler 根本不执行，用户会「什么都不显示」。
 * 本类是**系统判定**的结果，不存在启发式误报，正好补上这块空白。
 *
 * 关键点：
 *  - API 30 以下**安静降级**（返回 null，不抛异常、不报错）；
 *  - 只上报「异常退出」类 reason（白名单见 [ApplicationExitPolicy]）；用户主动划掉/正常退出
 *    以及权限变更等非崩溃原因**不上报**；`LOW_MEMORY` 仅在仍处于前台/前台服务时上报；
 *  - 用落盘的 ack 时间戳保证**同一次退出只提示一次**；
 *  - 尽量读取 `getTraceInputStream()`（可能为空/受限），**能拿到就落盘**，
 *    等于在没有 root 的情况下拿到 native 崩溃栈；拿不到就如实标注。
 */
object ApplicationExitMonitor {

    /** 单条系统 trace 上限（字符），防止极端情况下报告过大。 */
    private const val MAX_TRACE_CHARS = 64 * 1024

    /**
     * 需要向用户提示的退出原因（判定逻辑见 [ApplicationExitPolicy]，纯逻辑可单测）。
     *
     * **刻意排除**：`REASON_USER_REQUESTED`（用户划掉）、`REASON_EXIT_SELF`（正常退出）、
     * `REASON_OTHER`（语义不明）—— 这些属于正常退出，绝不能提示。
     */

    /**
     * 读取并「消费」最近一次值得上报的异常退出。
     *
     * **副作用**：无论是否上报，都会把 ack 游标推进到最新一次退出的时间戳，
     * 保证同一次退出不会被反复提示。
     *
     * @return 可直接展示的报告文本；无值得上报的退出时返回 null。
     */
    fun consumeNotableExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            readAndConsume(context)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAndConsume(context: Context): String? {
        val latest = latestExitInfo(context) ?: return null
        val timestamp = latest.timestamp
        val ack = CrashLogStore.readExitAck(context)
        // 推进游标：即使本次不上报，也要标记「已看过这次退出」。
        if (timestamp > ack) CrashLogStore.writeExitAck(context, timestamp)

        if (timestamp <= ack) return null
        // 传入「死亡时刻的重要性」：LOW_MEMORY 仅在仍处于前台/前台服务时才提示（见策略类）。
        if (!ApplicationExitPolicy.isNotable(latest.reason, latest.importance)) return null

        return buildReport(context, latest)
    }

    /**
     * 当已存在 Java 崩溃报告（`crash_business.txt`）时调用：若最近一次退出正是
     * `REASON_CRASH`（即同一起 Java 崩溃），推进 ack，避免同一事件再单独弹一次系统报告。
     *
     * 若最近一次退出是**别的**原因（例如 Java 崩溃之后又发生 native 崩溃），**不**推进 ack，
     * 让它留到 Java 报告被清除后再浮出，避免漏报。
     */
    fun acknowledgeIfJavaCrashExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val latest = latestExitInfo(context) ?: return
            if (latest.reason != ApplicationExitInfo.REASON_CRASH) return
            if (latest.timestamp > CrashLogStore.readExitAck(context)) {
                CrashLogStore.writeExitAck(context, latest.timestamp)
            }
        }
    }

    private fun latestExitInfo(context: Context): ApplicationExitInfo? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        // 注意：SDK 暴露的是 getHistoricalProcessExitReasons(packageName, pid, maxNum)，
        // 而非 AOSP 内部的 getHistoricalProcessExitInfos()。传本包名 + pid=0（全部）+ maxNum=1（最新一条）。
        val infos: List<ApplicationExitInfo> =
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1) ?: return null
        return infos.firstOrNull()
    }

    private fun buildReport(context: Context, info: ApplicationExitInfo): String {
        val ts = info.timestamp
        return buildString(4096) {
            append("=== YuNian Crash Report ===\n")
            append("source: system-exit-info (Android ActivityManager)\n")
            append("recorded_at: ").append(CrashReportFormat.time(ts)).append(" (epoch_ms=").append(ts).append(")\n")
            append("reason: ").append(reasonLabel(info.reason)).append(" (").append(info.reason).append(")\n")
            append("description: ").append(info.description ?: "(none)").append('\n')
            append("process: ").append(info.processName ?: "(unknown)").append(" pid=").append(info.pid).append('\n')
            append("importance: ").append(importanceLabel(info.importance)).append(" (").append(info.importance).append(")\n")
            append("status: ").append(info.status).append('\n')
            append("device: ").append(CrashReportFormat.deviceInfo()).append('\n')
            append("app: ").append(appInfo(context)).append('\n')
            append("--- system trace ---\n")
            // 系统 trace（tombstone/ANR trace）可能夹带寄存器/内存片段中的密钥或正文，同样脱敏。
            append(CrashRedactor.redact(readTrace(info))).append('\n')
            append("--- breadcrumbs (persisted) ---\n")
            // 落盘面包屑可能含业务正文/密钥片段（SecureLog 会记录调用内容），必须脱敏后再展示。
            append(
                CrashRedactor.redact(
                    CrashLogStore.readBreadcrumbs(context) ?: "(no persisted breadcrumbs)"
                )
            ).append('\n')
            append("=== end ===\n")
        }
    }

    /**
     * 尝试读取系统 trace。
     *
     * 实测结论：**API 30+ 对「本应用自己的」历史退出，`getTraceInputStream()` 通常可读**，
     * native 崩溃时能拿到 tombstone 摘要；但并非所有 reason/设备都提供（可能为 null 或空）。
     * 拿不到时返回说明串，绝不伪造内容。
     */
    private fun readTrace(info: ApplicationExitInfo): String {
        val stream: InputStream? = try {
            info.traceInputStream
        } catch (_: Throwable) {
            null
        }
        if (stream == null) return "(system did not provide a trace)"
        return try {
            stream.use { input ->
                val buffer = ByteArray(8192)
                val builder = StringBuilder()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (total + read > MAX_TRACE_CHARS) {
                        builder.append(String(buffer, 0, (MAX_TRACE_CHARS - total).coerceAtLeast(0)))
                        builder.append("\n… (trace truncated)")
                        break
                    }
                    builder.append(String(buffer, 0, read))
                    total += read
                }
                if (builder.isBlank()) "(trace stream was empty)" else builder.toString()
            }
        } catch (_: Throwable) {
            "(trace unavailable)"
        }
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH (Java 未捕获异常)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE (native 崩溃)"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR (应用无响应)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY (内存不足被系统回收)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE (初始化失败)"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED (被信号终止)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
        else -> "REASON_$reason"
    }

    private fun importanceLabel(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        else -> "IMPORTANCE_$importance"
    }

    private fun appInfo(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "package=${context.packageName}, versionName=${info.versionName ?: "?"}, versionCode=$versionCode"
    }.getOrElse { "(app info unavailable: ${it.javaClass.simpleName})" }
}
