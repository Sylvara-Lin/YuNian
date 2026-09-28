package com.yunian.ai.security

import android.app.Application
import android.content.Context
import com.yunian.ai.YuNianApplication

@Deprecated("Production shell entry is pure-Java StaticApkShell via package_thin_shell.py")
class YuNianShellApplication : Application(), androidx.work.Configuration.Provider {

    private var realApp: YuNianApplication? = null

    override val workManagerConfiguration: androidx.work.Configuration
        get() = realApp?.workManagerConfiguration ?: androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN).build()

    private val vmpAvailable: Boolean by lazy {
        try {
            val libPath = baseContext?.applicationInfo?.nativeLibraryDir + "/liblianyu_security.so"
            java.io.File(libPath).exists()
        } catch (_: Exception) { false }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        if (vmpAvailable && attachViaVmp(base)) return

        attachViaReflection(base)
    }

    override fun onCreate() {
        super.onCreate()
        realApp?.onCreate()
    }

    override fun onTerminate() { super.onTerminate(); realApp?.onTerminate() }
    override fun onLowMemory() { super.onLowMemory(); realApp?.onLowMemory() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); realApp?.onTrimMemory(level) }

    private fun attachViaVmp(base: Context): Boolean {
        return runCatching {
            val rc = NativeBridge.nativeLoadPayload(base, "com.yunian.ai.YuNianApplication")
            if (rc != 0) return false

            val loader = NativeBridge.sDexClassLoader ?: return false
            injectClassLoader(base, loader)

            val appClass = NativeBridge.sRealAppClass ?: return false
            realApp = appClass.getDeclaredConstructor().newInstance() as YuNianApplication
            invokeAttach(realApp!!, base)
            true
        }.getOrDefault(false)
    }

    private fun attachViaReflection(base: Context) {
        realApp = YuNianApplication()
        invokeAttach(realApp!!, base)
    }

    private fun invokeAttach(app: Application, base: Context) {
        Application::class.java
            .getDeclaredMethod("attach", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, base)
    }

    private fun injectClassLoader(base: Context, loader: ClassLoader) {
        val loadedApk = runCatching {
            base.javaClass.getDeclaredField("mLoadedApk").apply { isAccessible = true }.get(base)
        }.getOrElse {
            base.javaClass.getDeclaredField("mPackageInfo").apply { isAccessible = true }.get(base)
        }
        loadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .set(loadedApk, loader)
    }
}

object OnePieceShellGate {
    fun recordStartupPreflight(context: android.content.Context) {}
    fun verifyBeforePayload(context: android.content.Context) {}
}
