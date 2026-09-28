package com.yunian.ai.network

import com.yunian.ai.common.SecureLog

object ImageHelper {

    fun encodeImageToBase64(imagePath: String): String {
        return try {
            val file = java.io.File(imagePath)
            if (!file.exists()) throw Exception("图片文件不存在: $imagePath")

            val bytes = file.readBytes()
            if (bytes.isEmpty()) throw Exception("图片文件为空")

            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            SecureLog.e("ImageHelper", "encodeImageToBase64 failed", e)
            throw Exception("图片编码失败: ${e.message}")
        }
    }

    fun getImageMimeType(imagePath: String): String {
        return when (imagePath.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
