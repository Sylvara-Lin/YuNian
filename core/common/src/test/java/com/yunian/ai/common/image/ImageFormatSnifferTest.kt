package com.yunian.ai.common.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bug2 回归测试：图片格式嗅探只看魔数，与 DISPLAY_NAME / MIME 无关。
 */
class ImageFormatSnifferTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `PNG 魔数识别`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        assertEquals("png", ImageFormatSniffer.detect(png))
    }

    @Test
    fun `JPEG 魔数识别`() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
        assertEquals("jpg", ImageFormatSniffer.detect(jpeg))
    }

    @Test
    fun `GIF 魔数识别`() {
        val gif = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00)
        assertEquals("gif", ImageFormatSniffer.detect(gif))
    }

    @Test
    fun `WEBP 魔数识别`() {
        val webp = bytes(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20)
        assertEquals("webp", ImageFormatSniffer.detect(webp))
    }

    @Test
    fun `BMP 魔数识别`() {
        val bmp = bytes(0x42, 0x4D, 0x36, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x36, 0x00)
        assertEquals("bmp", ImageFormatSniffer.detect(bmp))
    }

    @Test
    fun `HEIC 魔数识别`() {
        // 00 00 00 18 'f''t''y''p' 'h''e''i''c'
        val heic = bytes(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63, 0x00, 0x00, 0x00, 0x00)
        assertEquals("heic", ImageFormatSniffer.detect(heic))
    }

    @Test
    fun `HEIF mif1 魔数识别`() {
        // brand = 'm''i''f''1'
        val heif = bytes(0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x69, 0x66, 0x31, 0x00, 0x00, 0x00, 0x00)
        assertEquals("heif", ImageFormatSniffer.detect(heif))
    }

    @Test
    fun `垃圾字节返回 null`() {
        val garbage = bytes(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
        assertNull(ImageFormatSniffer.detect(garbage))
        assertNull(ImageFormatSniffer.detect("hello world!!!!!".toByteArray()))
    }

    @Test
    fun `不足 16 字节的短头不崩且按已有字节判定`() {
        assertNull(ImageFormatSniffer.detect(ByteArray(0)))
        assertNull(ImageFormatSniffer.detect(ByteArray(1)))
        assertNull(ImageFormatSniffer.detect(ByteArray(2)))
        assertNull(ImageFormatSniffer.detect(ByteArray(3)))
        // 只有 4 字节也能识别 PNG / GIF
        assertEquals("png", ImageFormatSniffer.detect(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertEquals("gif", ImageFormatSniffer.detect(bytes(0x47, 0x49, 0x46, 0x38)))
        // 只有 3 字节也能识别 JPEG
        assertEquals("jpg", ImageFormatSniffer.detect(bytes(0xFF, 0xD8, 0xFF)))
        // WEBP 需要 12 字节，头部不足则无法识别（不崩）
        assertNull(ImageFormatSniffer.detect(bytes(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42)))
    }
}
