package com.yunian.ai.feature.chat.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把本地图片保存进系统相册。
 *
 * 走 MediaStore 而不是直接写 `Environment.getExternalStoragePublicDirectory`：
 * Android 10+ 分区存储下后者会失败，且 MediaStore 会自动触发相册扫描。
 */
internal fun saveImageToGallery(context: Context, sourcePath: String): Boolean {
    val source = File(sourcePath)
    if (!source.isFile || source.length() == 0L) return false

    val extension = source.extension.lowercase().takeIf { it in SUPPORTED_EXTENSIONS } ?: "png"
    val mimeType = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
    val displayName = "YuNian_${System.currentTimeMillis()}.$extension"

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/YuNian")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

    return try {
        val output = resolver.openOutputStream(uri)
        if (output == null) {
            runCatching { resolver.delete(uri, null, null) }
            false
        } else {
            output.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 清除 pending 标记，相册才会真正可见
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            true
        }
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
