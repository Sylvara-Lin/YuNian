package com.yunian.ai.feature.chat.ui.screen

import android.content.Context
import android.net.Uri

internal fun copyUriToCache(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val fileName = "sticker_import_${System.currentTimeMillis()}.zip"
        val cacheFile = java.io.File(context.cacheDir, fileName)
        inputStream.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
