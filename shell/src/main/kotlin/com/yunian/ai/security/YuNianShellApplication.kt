package com.yunian.ai.security

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.lang.reflect.Method

class YuNianShellApplication : Application() {

    private var realApp: Application? = null
    private var decryptOk: Boolean = false
    private var signatureOk: Boolean = false
    private var nativeBridgeLoaded: Boolean = false

    companion object {
        @Volatile
        var appContext: Context? = null
            private set

        @Volatile
        var realApplication: Application? = null
            private set

        @JvmStatic
        fun getAppContext(): Context? = appContext

        @JvmStatic
        fun getRealApplication(): Application? = realApplication
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base

        nativeBridgeLoaded = loadNativeBridge()
        if (!nativeBridgeLoaded) {
            selfDestruct("Native bridge load failed")
            return
        }

        signatureOk = verifyApkSignature(base)
        if (!signatureOk) {
            selfDestruct("APK signature verification failed")
            return
        }

        val gateOk = OnePieceShellGate.init(base)
        if (!gateOk) {
            selfDestruct("Shell gate initialization failed")
            return
        }

        decryptOk = decryptAndLoadDex(base)
        if (!decryptOk) {
            selfDestruct("DEX decryption failed")
            return
        }

        attachRealApplication(base)
    }

    override fun onCreate() {
        super.onCreate()
        realApp?.onCreate()
    }

    override fun onTerminate() {
        super.onTerminate()
        realApp?.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        realApp?.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        realApp?.onTrimMemory(level)
    }

    private fun loadNativeBridge(): Boolean {
        return try {
            System.loadLibrary("lianyu_shell")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("YuNianShell", "Failed to load liblianyu_shell.so", e)
            false
        }
    }

    private fun verifyApkSignature(ctx: Context): Boolean {
        return try {

            NativeBridge.verifySignature(ctx)
        } catch (e: Exception) {
            android.util.Log.e("YuNianShell", "Signature verification error", e)
            false
        }
    }

    private fun decryptAndLoadDex(ctx: Context): Boolean {
        return try {

            OnePieceShellGate.decryptAndLoad(ctx)
        } catch (e: Exception) {
            android.util.Log.e("YuNianShell", "DEX decrypt/load error", e)
            false
        }
    }

    private fun attachRealApplication(ctx: Context) {
        try {
            val appClassName = OnePieceShellGate.getRealAppClassName()
            val appClass = Class.forName(appClassName)
            val app = appClass.newInstance() as Application

            val attachMethod: Method = Application::class.java.getDeclaredMethod(
                "attach", Context::class.java
            )
            attachMethod.isAccessible = true
            attachMethod.invoke(app, ctx)

            realApp = app
            realApplication = app

            app.onCreate()

            android.util.Log.i("YuNianShell", "Real application attached: $appClassName")
        } catch (e: Exception) {
            android.util.Log.e("YuNianShell", "Failed to attach real application", e)
            selfDestruct("Real application attach failed")
        }
    }

    private fun selfDestruct(reason: String) {
        android.util.Log.e("YuNianShell", "Self-destruct: $reason")

        try {
            OnePieceShellGate.secureWipe()
        } catch (_: Exception) {}

        Thread {
            try {

                Thread.sleep((100..500).random().toLong())
            } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}
