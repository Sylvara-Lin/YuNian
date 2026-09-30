package com.yunian.ai.security

import android.app.Application
import android.content.Context
import com.yunian.ai.common.PerformanceTrace

class StaticApkShell : Application(), androidx.work.Configuration.Provider {

    private val shellLibAvailable: Boolean = runCatching {
        System.loadLibrary("lianyu_shell")
        true
    }.getOrElse {
        android.util.Log.e("StaticApkShell", "liblianyu_shell.so not found", it)
        false
    }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun attachBaseContext(base: Context) {
        PerformanceTrace.startShell()
        // ── 崩溃日志：最早可达点安装（release 也生效，不依赖 BuildConfig.DEBUG）──
        // Gradle 构建下本类是真正的 Application（manifest: com.yunian.ai.security.StaticApkShell），
        // 业务类与它同处一个 DEX，故此处安装即「业务层」捕获；安装本身幂等。
        runCatching { com.yunian.ai.common.crash.CrashReporter.install(base) }
        if (shellLibAvailable) {
            runCatching { nativeAntiHookInit() }
        }
        PerformanceTrace.markShellAntiHookDone()

        runCatching {
            val blobBytes = base.assets.open("yunian_shell/code_items.bin").use { it.readBytes() }
            nativeShellInitWithBlob(blobBytes)
        }.onFailure {
            android.util.Log.w("StaticApkShell", "VMP blob not present; skipping native shell init", it)
        }
        PerformanceTrace.markShellNativeInitDone()

        runCatching { MethodRecoveryEngine.install(base.classLoader) }
        runCatching { OatDisabler.disable(base) }
        PerformanceTrace.markShellRecoveryDone()

        if (shellLibAvailable) {
            runCatching { nativeEnableMemoryGuard() }
        }
        PerformanceTrace.markShellMemoryGuardDone()

        try {
            val g0Class = Class.forName("com.yunian.ai.security.G0")
            g0Class.getMethod("b", Context::class.java).invoke(null, base)
        } catch (e: Exception) {
            SecurityState.markTampered("startup preflight threw: ${e.javaClass.simpleName}")
        }
        PerformanceTrace.markShellPreflightDone()

        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // ── breadcrumbs 后台落盘（native/ANR/被系统杀时 Java handler 不执行，靠它保留上下文）──
        runCatching { com.yunian.ai.common.crash.CrashBreadcrumbPersister.install(this) }

        PerformanceTrace.startSecurity()
        try {
            val g0Class = Class.forName("com.yunian.ai.security.G0")
            g0Class.getMethod("a", Application::class.java).invoke(null, this)
        } catch (e: Exception) {
            SecurityState.markTampered("security runtime init failed: ${e.javaClass.simpleName}")
        }
        PerformanceTrace.markSecurityDone()
        PerformanceTrace.persistReleaseMetrics(this)
        logSecurityPerformance()

        com.yunian.ai.YuNianApplication.initBusiness(this)
    }

    external fun nativeShellInitWithBlob(blob: ByteArray): Int
    external fun nativeEnableMemoryGuard()
    external fun nativeAntiHookInit()

    private fun logSecurityPerformance() {
        val metrics = PerformanceTrace.shellMetricsNanos() + PerformanceTrace.securityMetricsNanos()
        android.util.Log.i(
            "YuNianReleasePerformance",
            metrics.entries.joinToString { (name, nanos) -> "$name=${nanos / 1_000_000.0}ms" }
        )
    }
}
