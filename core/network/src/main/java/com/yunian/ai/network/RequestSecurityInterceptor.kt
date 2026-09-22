package com.yunian.ai.network

import com.yunian.ai.common.security.DeviceRequestSigner
import com.yunian.ai.security.NativeBridge
import com.yunian.ai.security.SecurityState
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest

class RequestSecurityInterceptor(
    private val appId: String = "lianyu-1.5.1",
    private val signer: Signer = DeviceSigner,
    private val shouldSignRequest: (okhttp3.Request) -> Boolean = { true }
) : Interceptor {

    companion object {

        fun enforceTls(builder: OkHttpClient.Builder) {
            try {
                val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                    .tlsVersions(okhttp3.TlsVersion.TLS_1_2, okhttp3.TlsVersion.TLS_1_3)
                    .cipherSuites(

                        okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,

                        okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                        okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                        okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                        okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
                    )
                    .build()
                builder.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, tlsSpec))
            } catch (_: Exception) {

            }
        }

        private fun padToBlock(data: ByteArray): ByteArray {
            val blockSize = 16
            val paddedSize = ((data.size + blockSize - 1) / blockSize) * blockSize
            return data.copyOf(paddedSize)
        }
    }

    data class RequestSignature(
        val signature: String,
        val keyId: String,
        val deviceId: String
    )

    fun interface Signer {
        fun sign(payload: ByteArray): RequestSignature?
    }

    private object DeviceSigner : Signer {
        override fun sign(payload: ByteArray): RequestSignature? {
            val signed = DeviceRequestSigner.sign(payload) ?: return null
            return RequestSignature(
                signature = signed.signature,
                keyId = signed.keyId,
                deviceId = signed.deviceId
            )
        }
    }

    private object WhiteBoxAesSigner : Signer {
        override fun sign(payload: ByteArray): RequestSignature? {
            val encrypted = NativeBridge.wbAesEncrypt(padToBlock(payload)) ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(encrypted)

            return RequestSignature(
                signature = digest.joinToString("") { "%02x".format(it) },
                keyId = "legacy-whitebox",
                deviceId = "legacy"
            )
        }
    }

    private object AndroidKeystoreHmacSigner : Signer {
        private const val KEY_ALIAS = "lianyu_request_hmac_key"

        private fun getOrCreateKey(): javax.crypto.SecretKey? {
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val existing = ks.getEntry(KEY_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry
                if (existing != null) return existing.secretKey

                val kf = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    "AndroidKeyStore"
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setKeySize(256)
                    .build()
                kf.init(spec)
                return kf.generateKey()
            } catch (e: Exception) {
                return null
            }
        }

        override fun sign(payload: ByteArray): RequestSignature? {
            val key = getOrCreateKey() ?: return null
            return try {
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(key)
                val digest = mac.doFinal(payload)
                RequestSignature(
                    signature = digest.joinToString("") { "%02x".format(it) },
                    keyId = "legacy-hmac",
                    deviceId = "legacy"
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (!shouldSignRequest(originalRequest)) {
            return chain.proceed(originalRequest)
        }

        val state = SecurityState.snapshot()
        if (state.tampered) {
            throw IOException("security state is tampered")
        }
        val requestBuilder = originalRequest.newBuilder()

        val timestamp = System.currentTimeMillis() / 1000
        val nonce = generateNonce()

        val path = originalRequest.url.encodedPath +
            originalRequest.url.encodedQuery?.let { "?$it" }.orEmpty()
        val bodyHash = computeBodySha256(originalRequest)
        val clientId = extractYuNianClientId(originalRequest)

        val signature = computeRequestSignature(
            method = originalRequest.method,
            path = path,
            bodyHash = bodyHash,
            timestamp = timestamp,
            nonce = nonce,
            clientId = clientId
        ) ?: throw IOException("request signing unavailable")
        requestBuilder.header("X-LianYu-Sig-Version", "v1")
        requestBuilder.header("X-LianYu-Ts", timestamp.toString())
        requestBuilder.header("X-LianYu-Nonce", nonce)
        requestBuilder.header("X-LianYu-Body-SHA256", bodyHash)
        requestBuilder.header("X-LianYu-Device-Id", signature.deviceId)
        requestBuilder.header("X-LianYu-Key-Id", signature.keyId)
        requestBuilder.header("X-LianYu-Sig", signature.signature)
        requestBuilder.header("X-LianYu-Client", appId)

        return chain.proceed(requestBuilder.build())
    }

    private fun computeRequestSignature(
        method: String,
        path: String,
        bodyHash: String,
        timestamp: Long,
        nonce: String,
        clientId: String
    ): RequestSignature? {
        val deviceId = DeviceRequestSigner.deviceId()
        val payload = "v1\n$method\n$path\n$bodyHash\n$timestamp\n$nonce\n$clientId\n$deviceId"
        val payloadBytes = payload.toByteArray()
        return signer.sign(payloadBytes)
    }

    private fun computeBodySha256(request: okhttp3.Request): String {
        val body = request.body ?: return sha256Hex(ByteArray(0))
        val buffer = Buffer()
        body.writeTo(buffer)
        return sha256Hex(buffer.readByteArray())
    }

    private fun extractYuNianClientId(request: okhttp3.Request): String {
        request.header("X-LianYu-Client-Id")?.takeIf { it.isNotBlank() }?.let { return it }
        val authorization = request.header("Authorization") ?: return ""
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return ""
        return authorization.removePrefix("Bearer ").substringBefore(':')
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(12)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
