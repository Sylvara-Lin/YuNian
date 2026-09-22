package com.yunian.ai.common.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 更新接口加密通道（纯 JVM 实现，无第三方依赖，便于单测）。
 *
 * 协议 v1：
 *   请求 —— 设备 ES256 签名绑定 (前缀, 方法, 路径, 请求体哈希, 时间戳, nonce, 客户端密钥, 设备号)
 *   响应 —— AES-256-GCM 密文 + HMAC-SHA256 签名，密钥由客户端密钥派生
 *   下载 —— 服务端返回 nginx secure_link 短时签名地址
 *
 * 加密密钥链：
 *   AES_KEY = SHA256(APP_KEY + "|lianyu-update-v1")       → 32B，用于解密清单
 *   SIG_KEY = SHA256(APP_KEY + "|lianyu-update-sig-v1")  → 32B，用于校验响应签名
 */
object UpdateCrypto {

    private const val AES_SALT = "lianyu-update-v1"
    private const val SIG_SALT = "lianyu-update-sig-v1"
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val MAX_CLOCK_SKEW_SEC = 300L
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EncryptedEnvelope(
        @SerialName("v") val v: Int = 0,
        @SerialName("ts") val ts: Long = 0L,
        @SerialName("nonce") val nonce: String = "",
        @SerialName("iv") val iv: String = "",
        @SerialName("tag") val tag: String = "",
        @SerialName("data") val data: String = "",
        @SerialName("sig") val sig: String = ""
    )

    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 请求体摘要；无请求体时返回空串的 SHA256（与服务器一致） */
    fun bodyHash(body: ByteArray?): String =
        if (body == null || body.isEmpty()) EMPTY_SHA256 else sha256Hex(body)

    private fun derive(appKey: String, salt: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("$appKey|$salt".toByteArray(Charsets.UTF_8))

    private fun aesKey(appKey: String) = derive(appKey, AES_SALT)

    private fun sigKey(appKey: String) = derive(appKey, SIG_SALT)

    /** 构造待签名载荷（须与服务器 server.mjs 逐字节一致） */
    fun buildSignaturePayload(
        prefix: String,
        method: String,
        path: String,
        bodyHash: String,
        timestamp: Long,
        nonce: String,
        appKey: String,
        deviceId: String
    ): ByteArray = buildString {
        append(prefix).append('\n')
        append(method).append('\n')
        append(path).append('\n')
        append(bodyHash).append('\n')
        append(timestamp).append('\n')
        append(nonce).append('\n')
        append(appKey).append('\n')
        append(deviceId)
    }.toByteArray(Charsets.UTF_8)

    fun randomNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解密并验签服务端清单。
     *
     * 校验顺序：协议版本 → 字段完整 → nonce 绑定 → 时间窗 → HMAC 签名 → AES-GCM 解密。
     * 任一环节失败返回 null（调用方按"检查失败"处理，不会采用可疑数据）。
     *
     * @param expectedNonce 本次请求使用的 nonce，用于绑定请求与响应，防止响应重放
     * @return 解密后的明文 JSON 字符串
     */
    fun decodeManifest(appKey: String, responseJson: String, expectedNonce: String): String? {
        return try {
            val env = json.decodeFromString(EncryptedEnvelope.serializer(), responseJson)

            if (env.v != 1) return null
            if (env.ts <= 0L || env.nonce.isBlank() || env.iv.isBlank() ||
                env.tag.isBlank() || env.data.isBlank() || env.sig.isBlank()
            ) {
                return null
            }
            if (env.nonce != expectedNonce) return null

            val nowSec = System.currentTimeMillis() / 1000
            if (kotlin.math.abs(nowSec - env.ts) > MAX_CLOCK_SKEW_SEC) return null

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(sigKey(appKey), "HmacSHA256"))
            val expected = mac.doFinal(
                "1|${env.ts}|${env.nonce}|${env.iv}|${env.tag}|${env.data}"
                    .toByteArray(Charsets.UTF_8)
            )
            val actual = Base64.getDecoder().decode(env.sig)
            if (!MessageDigest.isEqual(expected, actual)) return null

            val iv = Base64.getDecoder().decode(env.iv)
            if (iv.size != 12) return null
            val cipherText = Base64.getDecoder().decode(env.data)
            val tagBytes = Base64.getDecoder().decode(env.tag)
            val combined = ByteArray(cipherText.size + tagBytes.size)
            System.arraycopy(cipherText, 0, combined, 0, cipherText.size)
            System.arraycopy(tagBytes, 0, combined, cipherText.size, tagBytes.size)

            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey(appKey), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(combined), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
