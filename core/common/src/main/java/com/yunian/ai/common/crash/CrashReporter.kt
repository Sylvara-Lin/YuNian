package com.yunian.ai.common.crash

import android.content.Context
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 业务层崩溃捕获器。
 *
 * 行为：
 *  1. 安装一个 `Thread.setDefaultUncaughtExceptionHandler`；
 *  2. 崩溃时把「异常 + 线程 + 设备/版本 + breadcrumbs」写进 `filesDir/crash/crash_business.txt`；
 *  3. **链式转发**捕获到的原 handler，保证进程仍按系统语义退出（绝不吞掉崩溃）。
 *
 * 安全约束：
 *  - 整个落盘过程包 `try/catch (Throwable)`，崩溃路径自身绝不二次崩溃。
 *  - 落盘**不依赖** `BuildConfig.DEBUG` / `SecureLog`，release 同样生效。
 *  - 安装是**幂等**的（`AtomicBoolean`），避免重复包 handler。
 */
object CrashReporter {

    private val installed = AtomicBoolean(false)

    /**
     * 安装业务层崩溃处理器。可从 `Application.attachBaseContext` 尽早调用。
     *
     * @param context 任意可用 Context（`attachBaseContext` 传入的 base 亦可）。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appCtx: Context = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
        runCatching { CrashLogStore.markBusinessAlive(appCtx) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(appCtx, thread, throwable)
                CrashLogStore.writeBusiness(appCtx, report)
            } catch (_: Throwable) {
                // 业务层落盘失败 -> 撤掉存活标记，交给壳层写一份最小报告。
                runCatching { CrashLogStore.clearBusinessAlive(appCtx) }
            } finally {
                // 链式转发：必须把异常交回原 handler，进程照常退出。
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    // 原 handler 自身异常也不能影响退出。
                }
            }
        }
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val now = System.currentTimeMillis()
        return buildString(8192) {
            append("=== YuNian Crash Report ===\n")
            append("source: business\n")
            append("recorded_at: ").append(CrashReportFormat.time(now)).append(" (epoch_ms=").append(now).append(")\n")
            append("thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
            append("process: pid=").append(safePid()).append('\n')
            append("device: ").append(CrashReportFormat.deviceInfo()).append('\n')
            append("app: ").append(appInfo(context)).append('\n')
            append("exception: ").append(throwable.javaClass.name).append('\n')
            append("message: ").append(CrashRedactor.redact(throwable.message ?: "(null)")).append('\n')
            append("--- stack ---\n")
            // 堆栈本身也脱敏：异常消息（含 Caused by）可能夹带内容/密钥片段。
            append(CrashRedactor.redact(CrashReportFormat.stackTraceOf(throwable))).append('\n')
            append("--- breadcrumbs ---\n")
            append(CrashBreadcrumbs.snapshot()).append('\n')
            append("=== end ===\n")
        }
    }

    private fun deviceInfo(): String = CrashReportFormat.deviceInfo()

    private fun appInfo(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = info.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "package=${context.packageName}, versionName=$versionName, versionCode=$versionCode"
    }.getOrElse { "(app info unavailable: ${it.javaClass.simpleName})" }

    private fun safePid(): Int = runCatching { Process.myPid() }.getOrDefault(-1)

    /** 供诊断/测试查询 handler 是否已安装。 */
    fun isInstalled(): Boolean = installed.get()
}
