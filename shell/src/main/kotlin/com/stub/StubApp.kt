package com.stub

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.lang.reflect.Method

class StubApp : Application() {

    private var realApp: Application? = null
    private var decryptOk: Boolean = false
    private var signatureOk: Boolean = false

    external fun interface13(ctx: Context): Int
    external fun interface14(): String
    external fun interface15(): Boolean

    companion object {
        init {
            System.loadLibrary("lianyu_shell")
        }

        var appContext: Context? = null
            private set

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

        val config = loadConfig(base)

        try {
            interface13(base)
            signatureOk = true
        } catch (e: Exception) {

            return
        }

        decryptOk = decryptAndLoad(base, config)

        if (decryptOk) {
            attachRealApp(base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (realApp != null) {
            realApp?.onCreate()
        }
    }

    data class Config(
        val fragmentCount: Int,
        val usePngCarriers: Boolean,
        val realAppClass: String
    )

    private fun loadConfig(ctx: Context): Config {

        val count = try { interface14().toIntOrNull() ?: 7 } catch (_: Exception) { 7 }
        return Config(
            fragmentCount = count,
            usePngCarriers = true,
            realAppClass = "com.yunian.ai.YuNianApplication"
        )
    }

    private fun decryptAndLoad(ctx: Context, config: Config): Boolean {
        return try {

            interface15()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun attachRealApp(ctx: Context) {
        try {
            val appClass = Class.forName("com.yunian.ai.YuNianApplication")
            val app = appClass.newInstance() as Application

            val attachMethod: Method = Application::class.java.getDeclaredMethod(
                "attach", Context::class.java
            )
            attachMethod.isAccessible = true
            attachMethod.invoke(app, ctx)

            realApp = app
            realApplication = app
            app.onCreate()
        } catch (e: Exception) {

            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
