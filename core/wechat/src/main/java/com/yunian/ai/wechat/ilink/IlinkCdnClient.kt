package com.yunian.ai.wechat.ilink

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-ECB（PKCS7 padding）编解码工具（对标官方 cdn/aes-ecb.ts）。
 * AES 块长 16 字节，JVM 的 PKCS5Padding 与 PKCS7 完全等价。
 */
object IlinkCdnCodec {

    const val AES_KEY_SIZE_BYTES = 16

    /** AES-128-ECB PKCS7 密文大小：ceil((n+1)/16)*16。 */
    fun aesEcbPaddedSize(plaintextSize: Int): Int = ((plaintextSize + 1 + 15) / 16) * 16

    fun encryptAesEcb(plaintext: ByteArray, key: ByteArray): ByteArray {
        val cipher = newCipher(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plaintext)
    }

    fun decryptAesEcb(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val cipher = newCipher(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(ciphertext)
    }

    private fun newCipher(mode: Int, key: ByteArray): Cipher {
        require(key.size == AES_KEY_SIZE_BYTES) {
            "AES-128 key must be $AES_KEY_SIZE_BYTES bytes, got ${key.size}"
        }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex char at ${i * 2}" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * 解析 CDN AES key（对标官方 pic-decrypt parseAesKey）：
     * - hex 明文（32 个 hex 字符，入站图片 image_item.aeskey）→ 直接 hex 解码；
     * - base64(16 字节原始 key，图片 media.aes_key) → 直接 base64 解码；
     * - base64(32 字符 hex 字符串，file/voice/video media.aes_key) → base64 解码后再 hex 解码。
     */
    fun parseAesKeyBytes(aesKey: String): ByteArray {
        val trimmed = aesKey.trim()
        if (trimmed.length == 32 && trimmed.all { it.isHexChar() }) {
            return hexToBytes(trimmed)
        }
        val decoded = Base64.getDecoder().decode(trimmed)
        if (decoded.size == AES_KEY_SIZE_BYTES) return decoded
        if (decoded.size == 32 && String(decoded, Charsets.US_ASCII).all { it.isHexChar() }) {
            return hexToBytes(String(decoded, Charsets.US_ASCII))
        }
        throw IllegalArgumentException(
            "无法识别的 CDN aes_key：解码后 ${decoded.size} 字节（期望 16 字节原始 key 或 32 字符 hex）",
        )
    }

    private fun Char.isHexChar(): Boolean =
        isDigit() || this in 'a'..'f' || this in 'A'..'F'
}

/**
 * 微信 CDN（c2c）上传 / 下载客户端（对标官方 cdn/cdn-upload.ts + cdn-url.ts + pic-decrypt.ts）。
 * 上传：POST AES-128-ECB 密文，4xx 直接失败，5xx/网络错误重试最多 3 次；
 * 200 响应头 `x-encrypted-param` 即下载参数（缺失报错），错误信息取 `x-error-message` 头。
 */
class IlinkCdnClient(client: OkHttpClient? = null) {

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** 上传成功后的信息：发 sendmessage 图片 item 所需字段。 */
    data class UploadedMedia(
        val filekey: String,
        val downloadEncryptedQueryParam: String,
        val aesKeyHex: String,
        val aesKeyBase64: String,
        val rawSize: Int,
        val cipherSize: Int,
    )

    /**
     * POST 密文到 [cdnUrl]，返回下载加密参数（x-encrypted-param）。
     * 4xx 抛出（不重试）；5xx / IO 错误最多重试 [UPLOAD_MAX_RETRIES] 次。
     */
    fun upload(cdnUrl: String, ciphertext: ByteArray): String {
        var lastError: Exception? = null
        for (attempt in 1..UPLOAD_MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(cdnUrl)
                    .post(ciphertext.toRequestBody(OCTET_STREAM_MEDIA_TYPE))
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (resp.code in 400..499) {
                        val errMsg = resp.header("x-error-message")
                            ?: resp.body?.string().orEmpty().take(200)
                        throw IllegalStateException(
                            "CDN upload client error ${resp.code}: $errMsg",
                        )
                    }
                    if (resp.code != 200) {
                        val errMsg = resp.header("x-error-message") ?: "status ${resp.code}"
                        throw IllegalStateException("CDN upload server error: $errMsg")
                    }
                    val downloadParam = resp.header("x-encrypted-param")
                        ?: throw IllegalStateException("CDN upload 响应缺少 x-encrypted-param 头")
                    return downloadParam
                }
            } catch (e: IOException) {
                // 网络错误：重试
                lastError = e
            } catch (e: IllegalStateException) {
                if (e.message.orEmpty().startsWith("CDN upload client error")) throw e
                // 5xx / 缺头：重试
                lastError = e
            }
        }
        throw IllegalStateException(
            "CDN upload 失败（重试 $UPLOAD_MAX_RETRIES 次）：${lastError?.message}",
            lastError,
        )
    }

    /** GET 下载密文（不解密）。 */
    fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("CDN download http ${resp.code}")
            }
            return resp.body?.bytes()
                ?: throw IllegalStateException("CDN download 返回空 body")
        }
    }

    companion object {
        /** 微信 c2c CDN 基地址（官方 CDN_BASE_URL）。 */
        const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"

        const val UPLOAD_MAX_RETRIES = 3

        private const val CONNECT_TIMEOUT_S = 10L
        private const val WRITE_TIMEOUT_S = 30L
        private const val READ_TIMEOUT_S = 30L

        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

        /** 拼接 CDN 上传 URL（官方 buildCdnUploadUrl）。 */
        fun buildUploadUrl(cdnBaseUrl: String, uploadParam: String, filekey: String): String {
            val param = URLEncoder.encode(uploadParam, "UTF-8")
            val key = URLEncoder.encode(filekey, "UTF-8")
            return "${cdnBaseUrl.trimEnd('/')}/upload?encrypted_query_param=$param&filekey=$key"
        }

        /** 拼接 CDN 下载 URL（官方 buildCdnDownloadUrl）。 */
        fun buildDownloadUrl(encryptedQueryParam: String, cdnBaseUrl: String): String {
            val param = URLEncoder.encode(encryptedQueryParam, "UTF-8")
            return "${cdnBaseUrl.trimEnd('/')}/download?encrypted_query_param=$param"
        }
    }
}
