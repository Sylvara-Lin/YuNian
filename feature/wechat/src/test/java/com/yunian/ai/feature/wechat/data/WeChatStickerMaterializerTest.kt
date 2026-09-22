package com.yunian.ai.feature.wechat.data

import com.yunian.ai.common.StickerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WeChatStickerMaterializerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun materialize_reusesExistingLocalFile() {
        val src = tmp.newFile("sticker_src_test.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val sticker = StickerInfo(
            name = "test_sticker",
            path = src.absolutePath,
            description = "测试表情",
            fileName = "sticker_src_test.png",
        )
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = tmp.newFolder("cache"),
            loadBytes = { error("should not load when local file exists") },
        )
        assertNotNull(material)
        assertEquals(src.absolutePath, material!!.localPath)
        assertEquals("sticker_src_test.png", material.fileName)
        assertEquals("测试表情", material.description)
    }

    @Test
    fun materialize_writesCacheWhenBytesProvided() {
        val cacheDir = tmp.newFolder("cache")
        val sticker = StickerInfo(
            name = "asset_sticker",
            path = "asset://stickers/happy.png",
            description = "开心",
            fileName = "happy.png",
        )
        val payload = byteArrayOf(9, 8, 7, 6)
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = cacheDir,
            loadBytes = { payload },
        )
        assertNotNull(material)
        val out = File(material!!.localPath)
        assertTrue(out.exists())
        assertTrue(out.parentFile!!.absolutePath.startsWith(cacheDir.absolutePath))
        assertEquals(payload.toList(), out.readBytes().toList())
        assertEquals("happy.png", material.fileName)
        assertEquals("开心", material.description)
    }

    @Test
    fun materialize_missingBytes_returnsNull() {
        val sticker = StickerInfo(
            name = "missing",
            path = "asset://stickers/missing.png",
            fileName = "missing.png",
        )
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = tmp.newFolder("cache"),
            loadBytes = { null },
        )
        assertEquals(null, material)
    }

    @Test
    fun materialize_importedCustomSticker_reusesLocalFileDirectly() {
        // 回归：导入的自定义表情（category=imported，真实文件路径）应直接物化本地文件，
        // 不触发 bytes 加载 / 缓存写入，且保留用户自定义 description（微信镜像一致性）
        val src = tmp.newFile("custom_1728123456789.png").apply {
            writeBytes(byteArrayOf(7, 7, 7))
        }
        val sticker = StickerInfo(
            name = "裂开",
            path = src.absolutePath,
            category = "imported",
            isBuiltIn = false,
            description = "裂开",
            fileName = "custom_1728123456789.png",
        )
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = tmp.newFolder("cache"),
            loadBytes = { error("imported 自定义表情不应触发 bytes 加载") },
        )
        assertNotNull(material)
        assertEquals(src.absolutePath, material!!.localPath)
        assertEquals("custom_1728123456789.png", material.fileName)
        assertEquals("裂开", material.description)
    }

    @Test
    fun materialize_importedCustomSticker_fileDeleted_fallsBackToBytes() {
        // 回归：本地文件已被删除（用户清理过）时，走 loadBytes 兜底；bytes 也拿不到 → 返回 null
        // （app 层 WeChatOutboundPortImpl.enqueueSticker 对 null 降级为文本入队，不再静默丢弃）
        val sticker = StickerInfo(
            name = "裂开",
            path = tmp.root.resolve("gone/custom_deleted.png").absolutePath,
            category = "imported",
            isBuiltIn = false,
            description = "裂开",
            fileName = "custom_deleted.png",
        )
        val cacheDir = tmp.newFolder("cache")
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = cacheDir,
            loadBytes = { null },
        )
        assertEquals(null, material)
    }
}
