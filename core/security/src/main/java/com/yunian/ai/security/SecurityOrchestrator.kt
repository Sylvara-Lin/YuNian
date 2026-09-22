package com.yunian.ai.security

import android.content.Context
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SecurityOrchestrator {

    private const val METADATA_SIZE = 16

    private val cryptoLock = AtomicBoolean(false)
    private val lockSpins = AtomicInteger(0)
    private const val MAX_SPINS = 1000

    private val bkCounter = AtomicInteger(0)

    private val bootSalt: ByteArray by lazy {
        val salt = ByteArray(8)
        SecureRandom().nextBytes(salt)
        salt
    }

    @Synchronized
    fun encrypt(context: Context, plaintext: ByteArray): EncryptionResult {

        val ztState = NativeBridge.zeroTrustGetState()
        if (ztState == 2) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED,
                "encrypt blocked: zero-trust BREACH")
            return EncryptionResult.Breach
        }

        if (!G0.c(context)) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.TAMPER_DETECTED,
                "encrypt blocked: SecurityGuard tampered")
            return EncryptionResult.Error("SecurityGuard reported tampered state")
        }

        if (!acquireLock()) {
            return EncryptionResult.Error("crypto lock timeout: another operation in progress")
        }

        try {

            val counter = bkCounter.incrementAndGet()
            val metadata = buildMetadata(counter)

            val padded = pkcs7Pad(plaintext)

            val encrypted = KmsProvider.encryptWithMetadata(padded, metadata)
                ?: run {
                    AuditLogger.log(context, AuditLogger.Level.ERROR,
                        AuditLogger.Event.ENCRYPT_FAIL,
                        "native encrypt returned null")
                    return EncryptionResult.Error("native encryption failed")
                }

            val result = ByteArray(METADATA_SIZE + encrypted.size)
            System.arraycopy(metadata, 0, result, 0, METADATA_SIZE)
            System.arraycopy(encrypted, 0, result, METADATA_SIZE, encrypted.size)

            AuditLogger.log(context, AuditLogger.Level.DEBUG,
                AuditLogger.Event.DB_ENCRYPTED,
                "encrypt OK: ${plaintext.size}B → ${result.size}B, counter=$counter")

            return EncryptionResult.Success(result)

        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.ENCRYPT_FAIL,
                "encrypt exception: ${e.message}")
            return EncryptionResult.Error(e.message ?: "unknown")
        } finally {
            releaseLock()
        }
    }

    @Synchronized
    fun decrypt(context: Context, ciphertext: ByteArray): EncryptionResult {

        val ztState = NativeBridge.zeroTrustGetState()
        if (ztState == 2) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED,
                "decrypt blocked: zero-trust BREACH")
            return EncryptionResult.Breach
        }

        if (!G0.c(context)) {
            return EncryptionResult.Error("SecurityGuard reported tampered state")
        }

        if (ciphertext.size < METADATA_SIZE + 16) {
            return EncryptionResult.Error("ciphertext too short: ${ciphertext.size}B")
        }

        val metadata = ciphertext.copyOfRange(0, METADATA_SIZE)
        val payload = ciphertext.copyOfRange(METADATA_SIZE, ciphertext.size)

        if (!acquireLock()) {
            return EncryptionResult.Error("crypto lock timeout")
        }

        try {

            val decrypted = KmsProvider.decryptWithMetadata(payload, metadata)
                ?: run {
                    AuditLogger.log(context, AuditLogger.Level.ERROR,
                        AuditLogger.Event.DECRYPT_FAIL,
                        "native decrypt returned null")
                    return EncryptionResult.Error("native decryption failed")
                }

            val plaintext = pkcs7Unpad(decrypted)
                ?: return EncryptionResult.Error("PKCS7 unpad failed: malformed padding")

            AuditLogger.log(context, AuditLogger.Level.DEBUG,
                AuditLogger.Event.DB_ENCRYPTED,
                "decrypt OK: ${ciphertext.size}B → ${plaintext.size}B")

            return EncryptionResult.Success(plaintext)

        } catch (e: Exception) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.DECRYPT_FAIL,
                "decrypt exception: ${e.message}")
            return EncryptionResult.Error(e.message ?: "unknown")
        } finally {
            releaseLock()
        }
    }

    private fun acquireLock(): Boolean {
        var spins = 0
        while (!cryptoLock.compareAndSet(false, true)) {
            if (++spins > MAX_SPINS) {
                lockSpins.incrementAndGet()
                return false
            }
            Thread.onSpinWait()
        }
        return true
    }

    private fun releaseLock() {
        cryptoLock.set(false)
    }

    private fun buildMetadata(counter: Int): ByteArray {
        val meta = ByteArray(METADATA_SIZE)
        System.arraycopy(bootSalt, 0, meta, 0, 8)

        meta[8] = ((counter ushr 56) and 0xFF).toByte()
        meta[9] = ((counter ushr 48) and 0xFF).toByte()
        meta[10] = ((counter ushr 40) and 0xFF).toByte()
        meta[11] = ((counter ushr 32) and 0xFF).toByte()
        meta[12] = ((counter ushr 24) and 0xFF).toByte()
        meta[13] = ((counter ushr 16) and 0xFF).toByte()
        meta[14] = ((counter ushr 8) and 0xFF).toByte()
        meta[15] = (counter and 0xFF).toByte()
        return meta
    }

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val blockSize = 16
        val padLen = blockSize - (data.size % blockSize)
        val padded = ByteArray(data.size + padLen)
        System.arraycopy(data, 0, padded, 0, data.size)
        for (i in data.size until padded.size) {
            padded[i] = padLen.toByte()
        }
        return padded
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val padLen = data[data.size - 1].toInt() and 0xFF
        if (padLen == 0 || padLen > 16 || padLen > data.size) return null

        for (i in data.size - padLen until data.size) {
            if ((data[i].toInt() and 0xFF) != padLen) return null
        }
        return data.copyOf(data.size - padLen)
    }
}

sealed class EncryptionResult {

    data class Success(val data: ByteArray) : EncryptionResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Error(val reason: String) : EncryptionResult()

    object Breach : EncryptionResult()
}
