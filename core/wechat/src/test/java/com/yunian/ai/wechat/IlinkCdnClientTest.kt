package com.yunian.ai.wechat

import com.yunian.ai.wechat.ilink.IlinkCdnClient
import com.yunian.ai.wechat.ilink.IlinkCdnCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class IlinkCdnClientTest {

    private val key = byteArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    // ------------------------------------------------------------------
    // AES-128-ECB PKCS7
    // ------------------------------------------------------------------

    @Test
    fun aesEcbPaddedSize_matchesOfficialFormula() {
        // ceil((n+1)/16)*16
        assertEquals(16, IlinkCdnCodec.aesEcbPaddedSize(0))
        assertEquals(16, IlinkCdnCodec.aesEcbPaddedSize(15))
        assertEquals(32, IlinkCdnCodec.aesEcbPaddedSize(16))
        assertEquals(32, IlinkCdnCodec.aesEcbPaddedSize(17))
        assertEquals(112, IlinkCdnCodec.aesEcbPaddedSize(100))
    }

    @Test
    fun aesEcbRoundTrip_variousSizes() {
        for (size in intArrayOf(0, 1, 15, 16, 17, 100, 1024)) {
            val plaintext = ByteArray(size) { (it % 251).toByte() }
            val ciphertext = IlinkCdnCodec.encryptAesEcb(plaintext, key)
            assertEquals(IlinkCdnCodec.aesEcbPaddedSize(size), ciphertext.size)
            assertArrayEquals(plaintext, IlinkCdnCodec.decryptAesEcb(ciphertext, key))
        }
    }

    @Test
    fun aesEcbIsDeterministicAndBlockAligned() {
        val a = IlinkCdnCodec.encryptAesEcb("lianyu".toByteArray(), key)
        val b = IlinkCdnCodec.encryptAesEcb("lianyu".toByteArray(), key)
        assertArrayEquals(a, b)
        assertEquals(16, a.size)
    }

    @Test
    fun decryptRejectsWrongKeyOrCorruptData() {
        val ciphertext = IlinkCdnCodec.encryptAesEcb("hello".toByteArray(), key)
        val wrongKey = ByteArray(16) { 9 }
        assertThrows(Exception::class.java) {
            IlinkCdnCodec.decryptAesEcb(ciphertext, wrongKey)
        }
        assertThrows(Exception::class.java) {
            IlinkCdnCodec.decryptAesEcb(ciphertext.copyOfRange(0, 10), key)
        }
    }

    // ------------------------------------------------------------------
    // key 解析：hex / base64(16B) / base64(hex)
    // ------------------------------------------------------------------

    @Test
    fun parseAesKeyBytes_acceptsHexPlaintext() {
        val hex = IlinkCdnCodec.bytesToHex(key)
        assertArrayEquals(key, IlinkCdnCodec.parseAesKeyBytes(hex))
    }

    @Test
    fun parseAesKeyBytes_acceptsBase64RawKey() {
        val b64 = Base64.getEncoder().encodeToString(key)
        assertArrayEquals(key, IlinkCdnCodec.parseAesKeyBytes(b64))
    }

    @Test
    fun parseAesKeyBytes_acceptsBase64OfHexString() {
        // file/voice/video 的 media.aes_key：base64(32 字符 hex)
        val hex = IlinkCdnCodec.bytesToHex(key)
        val b64 = Base64.getEncoder().encodeToString(hex.toByteArray(Charsets.US_ASCII))
        assertArrayEquals(key, IlinkCdnCodec.parseAesKeyBytes(b64))
    }

    @Test
    fun parseAesKeyBytes_rejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            IlinkCdnCodec.parseAesKeyBytes("not-a-valid-key!!!")
        }
    }

    // ------------------------------------------------------------------
    // URL 拼接
    // ------------------------------------------------------------------

    @Test
    fun buildUploadUrl_encodesParams() {
        val url = IlinkCdnClient.buildUploadUrl(
            "https://novac2c.cdn.weixin.qq.com/c2c/",
            "abc def&x=1",
            "fk01",
        )
        assertEquals(
            "https://novac2c.cdn.weixin.qq.com/c2c/upload?encrypted_query_param=abc+def%26x%3D1&filekey=fk01",
            url,
        )
    }

    @Test
    fun buildDownloadUrl_encodesParam() {
        val url = IlinkCdnClient.buildDownloadUrl("a b&c", IlinkCdnClient.CDN_BASE_URL)
        assertEquals(
            "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=a+b%26c",
            url,
        )
    }

    @Test
    fun cdnBaseUrl_matchesOfficial() {
        assertEquals("https://novac2c.cdn.weixin.qq.com/c2c", IlinkCdnClient.CDN_BASE_URL)
    }

    @Test
    fun hexHelpers_roundTrip() {
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0, 0x0f)
        assertEquals("dead000f", IlinkCdnCodec.bytesToHex(bytes))
        assertArrayEquals(bytes, IlinkCdnCodec.hexToBytes("dead000f"))
        assertTrue(IlinkCdnCodec.bytesToHex(ByteArray(16)).length == 32)
    }
}
