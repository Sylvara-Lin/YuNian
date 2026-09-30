package com.yunian.ai.common.crash

import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告各来源（业务处理器 / 系统退出信息）共用的格式化工具，避免重复实现。
 */
internal object CrashReportFormat {

    fun time(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))

    fun deviceInfo(): String = runCatching {
        "manufacturer=${Build.MANUFACTURER}, brand=${Build.BRAND}, model=${Build.MODEL}, " +
            "sdk=${Build.VERSION.SDK_INT}, release=${Build.VERSION.RELEASE}, " +
            "abis=${Build.SUPPORTED_ABIS.joinToString("/")}"
    }.getOrDefault("(device info unavailable)")

    fun stackTraceOf(throwable: Throwable): String = runCatching {
        val sw = StringWriter(4096)
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        sw.toString()
    }.getOrDefault("(stack trace unavailable)")
}
