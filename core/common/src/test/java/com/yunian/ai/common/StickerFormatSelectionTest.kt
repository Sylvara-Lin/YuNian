package com.yunian.ai.common

import com.yunian.ai.common.image.ImageFormatSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2 回归：表情包导入格式判定「内容优先于文件名」。
 *
 * 规则：三级信号（DISPLAY_NAME 后缀 → MIME → 魔数嗅探）只接受白名单（png/jpg/gif/webp）；
 * 后缀 / MIME 若不是白名单（如误命名的 .bmp / .heic），**不得短路**，须让后续信号（最终是魔数嗅探）说话。
 */
class StickerFormatSelectionTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private val jpegHeader = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
    private val pngHeader = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    private val bmpHeader = bytes(0x42, 0x4D, 0x36, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x36, 0x00)

    @Test
    fun `后缀归一 别名与大小写`() {
        assertEquals("jpg", normalizeStickerImageExtension("photo.JPEG"))
        assertEquals("jpg", normalizeStickerImageExtension("a.jfif"))
        assertEquals("jpg", normalizeStickerImageExtension("a.JPE"))
        assertEquals("jpg", normalizeStickerImageExtension("a.jpg"))
        assertEquals("png", normalizeStickerImageExtension("a.png"))
        // 已知但非白名单：仍需返回原始归一值（供「检测到」提示），由选择阶段过滤
        assertEquals("bmp", normalizeStickerImageExtension("x.bmp"))
        assertEquals("heic", normalizeStickerImageExtension("photo.heic"))
        // 未知 / 空 / 纯数字缓存名
        assertNull(normalizeStickerImageExtension("notes.txt"))
        assertNull(normalizeStickerImageExtension("1000045678"))
        assertNull(normalizeStickerImageExtension(""))
        assertNull(normalizeStickerImageExtension(null))
    }

    @Test
    fun `真实 JPEG 但名为 x_bmp - 内容优先得 jpg`() {
        val sniffed = ImageFormatSniffer.detect(jpegHeader)
        assertEquals("jpg", sniffed)
        val nameExt = normalizeStickerImageExtension("x.bmp")
        assertEquals("bmp", nameExt)
        // 后缀 bmp 非白名单不短路 → 嗅探 jpg 胜出
        assertEquals("jpg", selectStickerImageExtension(nameExt, null, sniffed))
    }

    @Test
    fun `真实 PNG 但名为 photo_heic - 内容优先得 png`() {
        val sniffed = ImageFormatSniffer.detect(pngHeader)
        assertEquals("png", sniffed)
        val nameExt = normalizeStickerImageExtension("photo.heic")
        assertEquals("heic", nameExt)
        assertEquals("png", selectStickerImageExtension(nameExt, null, sniffed))
    }

    @Test
    fun `真 BMP 且名为 x_bmp - 白名单外被拒`() {
        val sniffed = ImageFormatSniffer.detect(bmpHeader)
        assertEquals("bmp", sniffed)
        val nameExt = normalizeStickerImageExtension("x.bmp")
        assertEquals("bmp", nameExt)
        assertNull(selectStickerImageExtension(nameExt, null, sniffed))
    }

    @Test
    fun `白名单信号正常短路`() {
        assertEquals("jpg", selectStickerImageExtension("jpg", null, null))
        assertEquals("png", selectStickerImageExtension(null, "png", null))
        assertEquals("webp", selectStickerImageExtension(null, null, "webp"))
        assertNull(selectStickerImageExtension(null, null, null))
    }

    @Test
    fun `MIME 非白名单也不短路 - 内容说话`() {
        // MIME 误报 bmp，但内容实为 PNG
        assertEquals("png", selectStickerImageExtension(null, "bmp", ImageFormatSniffer.detect(pngHeader)))
    }

    @Test
    fun `无后缀无MIME 时纯靠嗅探`() {
        assertEquals("jpg", selectStickerImageExtension(null, null, ImageFormatSniffer.detect(jpegHeader)))
        assertEquals("png", selectStickerImageExtension(null, null, ImageFormatSniffer.detect(pngHeader)))
    }
}
