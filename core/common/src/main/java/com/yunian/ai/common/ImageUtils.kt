package com.yunian.ai.common

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ImageUtils {

    suspend fun saveUriToInternalStorage(context: Context, uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val existing = if (!uri.startsWith("content://") && File(uri).exists()) File(uri) else null

            if (existing != null && existing.absolutePath.startsWith(context.filesDir.absolutePath)) {
                return@withContext uri
            }

            val inputUri = Uri.parse(uri)
            val fileName = "avatar_${UUID.randomUUID()}.jpg"
            val avatarsDir = File(context.filesDir, "avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }
            val file = File(avatarsDir, fileName)

            if (existing != null) {

                existing.inputStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    return@withContext null
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun deleteAvatarFile(path: String?) {
        path?.let {
            try {
                File(it).delete()
            } catch (_: Exception) { }
        }
    }
}
