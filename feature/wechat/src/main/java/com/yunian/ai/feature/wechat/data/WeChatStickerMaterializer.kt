package com.yunian.ai.feature.wechat.data

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.common.StickerManager
import java.io.File
import java.security.MessageDigest

object WeChatStickerMaterializer {

    private const val TAG = "WeChatStickerMat"
    const val CACHE_DIR = "wechat_outbox_stickers"

    data class Materialized(
        val localPath: String,
        val fileName: String,
        val description: String?,
    )

    suspend fun resolveByName(context: Context, stickerName: String): StickerInfo? {
        val name = stickerName.trim()
        if (name.isEmpty()) return null
        val stickerManager = StickerManager.getInstance(context)
        var sticker = stickerManager.findStickerByDescriptionExact(name)
            ?: stickerManager.findStickerByDescription(name)
        if (sticker == null && !name.endsWith(".png", ignoreCase = true)) {
            sticker = stickerManager.findStickerByDescription("$name.png")
        }
        if (sticker == null) {
            sticker = stickerManager.getAllStickers()
                .find { it.fileName == name || it.name == name }
        }
        return sticker
    }

    fun materialize(context: Context, sticker: StickerInfo): Materialized? {
        return materialize(
            sticker = sticker,
            cacheDir = File(context.cacheDir, CACHE_DIR),
            loadBytes = { loadBytes(context, it) },
        )
    }

    suspend fun materializeByName(context: Context, stickerName: String): Materialized? {
        val sticker = resolveByName(context, stickerName) ?: run {
            SecureLog.w(TAG, "sticker not found: $stickerName")
            return null
        }
        return materialize(context, sticker)
    }

    fun materialize(
        sticker: StickerInfo,
        cacheDir: File,
        loadBytes: (StickerInfo) -> ByteArray?,
    ): Materialized? {
        val fileName = sticker.fileName
            ?.takeIf { it.isNotBlank() }
            ?: File(sticker.path.removePrefix("asset://")).name.takeIf { it.isNotBlank() }
            ?: "sticker.png"
        val description = sticker.description ?: sticker.name

        if (!sticker.path.startsWith("asset://")) {
            val file = File(sticker.path)
            if (file.exists() && file.isFile && file.canRead()) {
                return Materialized(
                    localPath = file.absolutePath,
                    fileName = fileName,
                    description = description,
                )
            }
        }

        val bytes = loadBytes(sticker) ?: return null
        return writeCacheFile(cacheDir, sticker.name, fileName, description, bytes)
    }

    private fun loadBytes(context: Context, sticker: StickerInfo): ByteArray? {
        return try {
            when {
                sticker.path.startsWith("asset://") -> {
                    val assetPath = sticker.path.removePrefix("asset://")
                    context.assets.open(assetPath).use { it.readBytes() }
                }
                else -> File(sticker.path).takeIf { it.exists() }?.readBytes()
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "load failed path=${sticker.path}: ${e.message}")
            null
        }
    }

    private fun writeCacheFile(
        cacheDir: File,
        stickerName: String,
        fileName: String,
        description: String?,
        bytes: ByteArray,
    ): Materialized? {
        return try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val out = File(cacheDir, "${digest}_$safeName")
            if (!out.exists() || out.length() != bytes.size.toLong()) {
                out.writeBytes(bytes)
            }
            Materialized(
                localPath = out.absolutePath,
                fileName = fileName,
                description = description,
            )
        } catch (e: Exception) {
            SecureLog.w(TAG, "cache write failed name=$stickerName: ${e.message}")
            null
        }
    }
}
