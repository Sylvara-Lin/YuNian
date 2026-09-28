package com.yunian.ai.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dalvik.system.InMemoryDexClassLoader
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object CompositeVmpRuntime {
    @Volatile
    var debugMode = false
        private set

    private fun isDebugBuild(context: Context): Boolean {
        if (!com.yunian.ai.security.BuildConfig.DEBUG) return false

        val debuggable = (context.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) ?: 0) != 0
        val buildTypeDebug = Build.TYPE == "userdebug" || Build.TYPE == "eng" || Build.TAGS?.contains("debug") == true
        return debuggable || buildTypeDebug
    }
    const val OP_SHELL_RECORD_STARTUP_PREFLIGHT = 0x11
    const val OP_SHELL_VERIFY_BEFORE_PAYLOAD = 0x12
    const val OP_SHELL_CREATE_PAYLOAD_LOADER = 0x13
    const val OP_SHELL_LOAD_NETWORK_DEX = 0x14
    const val OP_SHELL_LOAD_UI_DEX = 0x15
    const val OP_SHELL_LOAD_CHAT_DEX = 0x16
    const val OP_SHELL_UNLOAD_UI_DEX = 0x17
    const val OP_SHELL_UNLOAD_CHAT_DEX = 0x18
    const val OP_API_SECRET_ENCRYPT = 0x21
    const val OP_API_SECRET_DECRYPT = 0x22

    private const val SHELL_PAYLOAD_ASSET = "yunian_shell/shell_payload.bin"
    private const val SHELL_PAYLOAD_MANIFEST_ASSET = "yunian_shell/shell_payload_manifest.json"
    private const val PAYLOAD_METADATA_SIZE = 16
    private const val SECRET_PREFIX = "enc:v2:kms:"
    private const val TINK_SECRET_PREFIX = "enc:v3:tink:"
    private const val TINK_ENVELOPE_PREFIX = "enc:v4:tink-env:"
    private const val NONCE_SIZE = 16

    private val random = SecureRandom()

    fun execute(opcode: Int, arg0: Any? = null, arg1: Any? = null): Any? = when (opcode) {
        OP_SHELL_RECORD_STARTUP_PREFLIGHT -> {
            recordStartupPreflight(arg0 as Context)
            Unit
        }

        OP_SHELL_VERIFY_BEFORE_PAYLOAD -> {
            verifyBeforePayload(arg0 as Context)
            Unit
        }

        OP_SHELL_CREATE_PAYLOAD_LOADER -> {

        DexFragmentLoader.loadShellFragment(
            arg0 as Context,
            arg1 as ClassLoader
        )
    }

        OP_API_SECRET_ENCRYPT -> encryptApiSecret(arg0 as String)
        OP_API_SECRET_DECRYPT -> decryptApiSecret(arg0 as String)

        OP_SHELL_LOAD_NETWORK_DEX -> {
            DexFragmentLoader.loadNetworkFragment(arg0 as Context)
            Unit
        }
        OP_SHELL_LOAD_UI_DEX -> {
            DexFragmentLoader.loadUiFragment(arg0 as Context)
            Unit
        }
        OP_SHELL_LOAD_CHAT_DEX -> {
            DexFragmentLoader.loadChatFragment(arg0 as Context)
            Unit
        }
        OP_SHELL_UNLOAD_UI_DEX -> {
            DexFragmentLoader.unloadUiFragment(arg0 as Context)
            Unit
        }
        OP_SHELL_UNLOAD_CHAT_DEX -> {
            DexFragmentLoader.unloadChatFragment(arg0 as Context)
            Unit
        }
        else -> throw IllegalArgumentException("unknown composite VMP opcode: $opcode")
    }

    private fun recordStartupPreflight(context: Context) {
        if (isDebugBuild(context)) {
            debugMode = true
            android.util.Log.w("YuNian-Gate", "Debug build detected — bypassing startup preflight")

            SecurityState.markPreflightPassed(
                wbAesReady = true,
                signatureTrusted = true,
                dexTrusted = true,
                soTrusted = true,
                resourcesTrusted = true,
                payloadVerified = true,
                kmsReady = false
            )
            return
        }
        runCatching { G0.b(context) }
            .onFailure { SecurityState.markTampered("one-piece shell startup preflight failed") }
    }

    private fun verifyBeforePayload(context: Context) {
        if (isDebugBuild(context)) {
            android.util.Log.w("YuNian-Gate", "Debug build detected — bypassing one-piece shell hard gate")

            SecurityState.markPreflightPassed(
                wbAesReady = true,
                signatureTrusted = true,
                dexTrusted = true,
                soTrusted = true,
                resourcesTrusted = true,
                payloadVerified = true,
                kmsReady = false
            )
            return
        }

        G0.b(context)
        val state = SecurityState.snapshot()

        val failures = mutableListOf<String>()
        if (state.tampered) failures.add("tampered(reason=${state.reason})")
        if (!state.preflightPassed) failures.add("preflight not passed")
        if (!state.wbAesReady) failures.add("wbAes not ready")
        if (!state.signatureTrusted) failures.add("signature not trusted")
        if (!state.dexTrusted) failures.add("dex not trusted")
        if (!state.soTrusted) failures.add("so not trusted")
        if (!state.resourcesTrusted) failures.add("resources not trusted")
        if (!state.payloadVerified) failures.add("payload not verified")
        val kmsOk = KmsProvider.isReady
        if (!kmsOk) failures.add("KMS not ready")

        if (failures.isNotEmpty()) {
            val reason = failures.joinToString("; ")

            val sigActuallyOk = state.signatureTrusted || SecurityGuard.verifySignatureViaPackageManager(context)

            if (!sigActuallyOk || !kmsOk) {
                SecurityState.markTampered("one-piece shell payload gate failed: $reason")
                throw SecurityException("one-piece shell payload gate failed: $reason")
            }

            android.util.Log.e("YuNian-Gate", "Gate allowing launch with soft-failures: $reason")
            SecurityState.markPreflightPassed(
                wbAesReady = state.wbAesReady,
                signatureTrusted = true,
                dexTrusted = state.dexTrusted,
                soTrusted = state.soTrusted,
                resourcesTrusted = state.resourcesTrusted,
                payloadVerified = state.payloadVerified,
                kmsReady = kmsOk
            )
        }
    }

    internal fun createInMemoryPayloadLoader(
        context: Context,
        parent: ClassLoader
    ): InMemoryDexClassLoader {
        val encryptedPayload = readShellPayload(context)
        val decryptedPayload = decryptPayload(encryptedPayload)
        return try {
            verifyPayloadDigest(context, encryptedPayload, decryptedPayload)
            InMemoryDexClassLoader(ByteBuffer.wrap(decryptedPayload), parent)
        } finally {
            encryptedPayload.fill(0)
            decryptedPayload.fill(0)
        }
    }

    private fun readShellPayload(context: Context): ByteArray =
        context.assets.open(SHELL_PAYLOAD_ASSET).use { it.readBytes() }

    private fun verifyPayloadDigest(
        context: Context,
        encryptedPayload: ByteArray,
        decryptedPayload: ByteArray
    ) {
        val rawManifest = context.assets.open(SHELL_PAYLOAD_MANIFEST_ASSET).use { it.readBytes() }
        val rawText = String(rawManifest, Charsets.UTF_8)

        val hmacPrefix = "\"hmac\": \""
        val hmacStart = rawText.indexOf(hmacPrefix)
        if (hmacStart < 0) throw SecurityException("shell payload manifest missing signature")
        val hmacValueStart = hmacStart + hmacPrefix.length
        val hmacValueEnd = rawText.indexOf('"', hmacValueStart)
        if (hmacValueEnd < 0) throw SecurityException("shell payload manifest malformed signature")

        val hmacB64 = rawText.substring(hmacValueStart, hmacValueEnd)
        val signature = runCatching {
            Base64.getDecoder().decode(hmacB64)
        }.getOrNull() ?: throw SecurityException("shell payload manifest invalid signature encoding")

        val hmacFieldEnd = rawText.indexOf(',', hmacValueEnd)
        val contentForVerification = if (hmacFieldEnd > 0) {
            rawText.substring(0, hmacStart) + rawText.substring(hmacFieldEnd + 1)
        } else if (hmacStart > 0) {
            rawText.substring(0, hmacStart - 1)
        } else {
            rawText.substring(rawText.indexOf('}', hmacValueEnd).let { closeBrace ->
                if (closeBrace > 0) closeBrace + 1 else rawText.length
            })
        }

        if (!TinkAeadProvider.verifyManifest(
                contentForVerification.trim().toByteArray(Charsets.UTF_8), signature)) {
            throw SecurityException("shell payload manifest signature verification failed")
        }

        val encryptedDexZip = encryptedPayload.copyOfRange(PAYLOAD_METADATA_SIZE, encryptedPayload.size)
        val ciphertextSha256 = try {
            sha256Hex(encryptedDexZip)
        } finally {
            encryptedDexZip.fill(0)
        }
        val plaintextSha256 = sha256Hex(decryptedPayload)
        if (!rawText.contains("\"ciphertext_sha256\": \"$ciphertextSha256\"")) {
            throw SecurityException("shell payload ciphertext digest mismatch")
        }
        if (!rawText.contains("\"plaintext_sha256\": \"$plaintextSha256\"")) {
            throw SecurityException("shell payload plaintext digest mismatch")
        }
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    private fun decryptPayload(encryptedPayload: ByteArray): ByteArray {
        verifyPayloadTrust()
        if (encryptedPayload.size <= PAYLOAD_METADATA_SIZE || encryptedPayload.size % 16 != 0) {
            throw SecurityException("invalid shell payload size")
        }

        val payloadMetadata = extractPayloadMetadata(encryptedPayload)
        val encryptedDexZip = encryptedPayload.copyOfRange(PAYLOAD_METADATA_SIZE, encryptedPayload.size)
        val decryptedPaddedPayload = KmsProvider.decryptWithMetadata(encryptedDexZip, payloadMetadata)
            ?: throw SecurityException("native shell payload decrypt failed")
        return try {
            stripPkcs7Padding(decryptedPaddedPayload)
        } finally {
            payloadMetadata.fill(0)
            encryptedDexZip.fill(0)
            decryptedPaddedPayload.fill(0)
        }
    }

    private fun verifyPayloadTrust() {
        val state = SecurityState.snapshot()
        if (!KmsProvider.isReady) {
            SecurityState.markTampered("payload decrypt denied: KMS not ready")
            throw SecurityException("payload decrypt denied: KMS not ready")
        }

        if (state.tampered || !state.isTrustedForSensitiveOps) {
            android.util.Log.w("YuNian-Gate", "Payload trust soft-fail: tampered=${state.tampered} trusted=${state.isTrustedForSensitiveOps}")
        }
    }

    private fun extractPayloadMetadata(encryptedPayload: ByteArray): ByteArray =
        encryptedPayload.copyOfRange(0, PAYLOAD_METADATA_SIZE)

    private fun stripPkcs7Padding(value: ByteArray): ByteArray {
        if (value.isEmpty()) throw SecurityException("empty shell payload")
        val padding = value.last().toInt() and 0xff
        if (padding !in 1..16 || padding > value.size) {
            throw SecurityException("invalid shell payload padding")
        }
        for (index in value.size - padding until value.size) {
            if ((value[index].toInt() and 0xff) != padding) {
                throw SecurityException("corrupt shell payload padding")
            }
        }
        return value.copyOfRange(0, value.size - padding)
    }

    private fun encryptApiSecret(plaintext: String): String {
        if (plaintext.isBlank() || plaintext.startsWith(TINK_ENVELOPE_PREFIX)
            || plaintext.startsWith(TINK_SECRET_PREFIX) || plaintext.startsWith(SECRET_PREFIX)) return plaintext
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            val encrypted = TinkAeadProvider.encryptPayload(plaintextBytes)
            TINK_ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(encrypted)
        } finally {
            plaintextBytes.fill(0)
        }
    }

    private fun decryptApiSecret(value: String): String? {
        if (value.isBlank()) return value

        if (value.startsWith(TINK_ENVELOPE_PREFIX)) {
            val encrypted = runCatching {
                Base64.getDecoder().decode(value.removePrefix(TINK_ENVELOPE_PREFIX))
            }.getOrNull() ?: return null
            val decrypted = TinkAeadProvider.decryptPayload(encrypted) ?: return null
            return try {
                String(decrypted, Charsets.UTF_8)
            } finally {
                decrypted.fill(0)
            }
        }

        if (value.startsWith(TINK_SECRET_PREFIX)) {
            val encrypted = runCatching {
                Base64.getDecoder().decode(value.removePrefix(TINK_SECRET_PREFIX))
            }.getOrNull() ?: return null
            val decrypted = TinkAeadProvider.decryptSecret(encrypted) ?: return null
            return try {
                String(decrypted, Charsets.UTF_8)
            } finally {
                decrypted.fill(0)
            }
        }

        if (!value.startsWith(SECRET_PREFIX)) return value
        val encrypted = runCatching { Base64.getDecoder().decode(value.removePrefix(SECRET_PREFIX)) }.getOrNull()
            ?: return null
        val decrypted = NativeBridge.decryptData(encrypted) ?: return null
        return try {
            val unpadded = pkcs7Unpad(decrypted) ?: return null
            if (unpadded.size < NONCE_SIZE) return null
            val secretBytes = unpadded.copyOfRange(NONCE_SIZE, unpadded.size)
            try {
                String(secretBytes, Charsets.UTF_8)
            } finally {
                unpadded.fill(0)
                secretBytes.fill(0)
            }
        } finally {
            encrypted.fill(0)
            decrypted.fill(0)
        }
    }

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val blockSize = 16
        val padLen = blockSize - (data.size % blockSize)
        val out = ByteArray(data.size + padLen)
        System.arraycopy(data, 0, out, 0, data.size)
        for (i in data.size until out.size) out[i] = padLen.toByte()
        return out
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val padLen = data[data.lastIndex].toInt() and 0xFF
        if (padLen == 0 || padLen > 16 || padLen > data.size) return null
        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xFF) != padLen) return null
        }
        return data.copyOf(data.size - padLen)
    }

    fun verifyWbAesIntegrity(): Boolean = NativeBridge.vmpWbAesKeycheck() == 1

    fun verifyTeeAttestation(): Boolean = NativeBridge.vmpTeeAttest() == 1

    fun verifyApkSignature(): Boolean = NativeBridge.vmpApkSigVerify() == 1

    fun verifyTrustAnchors(): Boolean {
        if (!verifyWbAesIntegrity()) {
            if (debugMode) android.util.Log.e("VMP", "WB-AES integrity FAIL")
            return false
        }
        if (!verifyTeeAttestation()) {
            if (debugMode) android.util.Log.e("VMP", "TEE attestation FAIL")
            return false
        }
        if (!verifyApkSignature()) {
            if (debugMode) android.util.Log.e("VMP", "APK signature FAIL")
            return false
        }
        return true
    }
}
