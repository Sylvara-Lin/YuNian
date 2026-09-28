package com.yunian.ai.security

import android.content.Context
import java.io.File

object OatDisabler {

    private var monitorThread: Thread? = null

    fun disable(context: Context) {

        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java)
                .invoke(null, "dalvik.vm.dex2oat-filter", "interpret-only")
        } catch (_: Exception) {

            android.util.Log.w("OatDisabler", "Failed to disable dex2oat compilation")
        }

        deleteOatFiles(context)

        startOatMonitor(context)
    }

    private fun deleteOatFiles(context: Context) {
        val packageName = context.packageName
        val paths = listOf(
            "/data/dalvik-cache/arm64",
            "/data/dalvik-cache/arm",
            context.codeCacheDir?.absolutePath ?: return
        )
        for (base in paths) {
            val dir = File(base)
            dir.listFiles()?.filter {
                it.name.contains(packageName) &&
                (it.name.endsWith(".oat") || it.name.endsWith(".vdex") || it.name.endsWith(".art"))
            }?.forEach { it.delete() }
        }
    }

    private fun startOatMonitor(context: Context) {
        monitorThread = Thread({
            while (true) {
                Thread.sleep(30_000)
                deleteOatFiles(context)
            }
        }, "oat-monitor").apply {
            isDaemon = true
            start()
        }
    }

}
