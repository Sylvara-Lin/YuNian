package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.feature.chat.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatDebugLog {
    private val file by lazy {
        if (!BuildConfig.DEBUG) return@lazy null
        File("/data/data/com.yunian.ai/files/chatvm_debug.log")
    }

    fun log(msg: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val currentFile = file ?: return
            currentFile.parentFile?.mkdirs()
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            currentFile.appendText("$ts $msg\n")
        } catch (_: Exception) {}
    }
}
