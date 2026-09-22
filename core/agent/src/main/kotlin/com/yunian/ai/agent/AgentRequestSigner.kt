package com.yunian.ai.agent

import com.yunian.ai.common.security.DeviceRequestSigner
import com.yunian.ai.agent.uniffi.RequestHeader
import com.yunian.ai.agent.uniffi.RequestSignatureProvider
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * suflow.cloud（PARTNER）设备签名实现。
 *
 * 私钥位于 Android Keystore（ES256/P-256，DeviceRequestSigner），不可导出到 Rust：
 * 本类作为 UniFFI 回调由 Rust gateway 在发送 PARTNER 请求前调用，
 * 按服务端 verify_device_signature 的 payload 规范产出 7 个 X-LianYu-* 签名头。
 * 签名失败返回空列表 → Rust 侧 fail-closed 拒绝发送。
 */
class AgentRequestSigner : RequestSignatureProvider {

    override fun signHeaders(
        method: String,
        path: String,
        body: String,
        clientId: String,
    ): List<RequestHeader> {
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = generateNonce()
        val bodyHash = sha256Hex(body.toByteArray(Charsets.UTF_8))
        val deviceId = DeviceRequestSigner.deviceId()
        // 与服务端 verify_device_signature 完全一致：
        // v1\nMETHOD\nPATH\nBODY_HASH\nTS\nNONCE\nCLIENT_ID\nDEVICE_ID
        val payload = listOf(
            "v1", method, path, bodyHash, timestamp.toString(), nonce, clientId, deviceId,
        ).joinToString("\n")
        val signed = DeviceRequestSigner.sign(payload.toByteArray(Charsets.UTF_8)) ?: return emptyList()
        return listOf(
            RequestHeader("X-LianYu-Sig-Version", "v1"),
            RequestHeader("X-LianYu-Ts", timestamp.toString()),
            RequestHeader("X-LianYu-Nonce", nonce),
            RequestHeader("X-LianYu-Body-SHA256", bodyHash),
            RequestHeader("X-LianYu-Device-Id", deviceId),
            RequestHeader("X-LianYu-Key-Id", signed.keyId),
            RequestHeader("X-LianYu-Sig", signed.signature),
        )
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}