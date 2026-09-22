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
