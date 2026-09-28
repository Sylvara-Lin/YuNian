package com.yunian.ai.security

import com.yunian.ai.common.SecureLog

object Sm4Cipher {
    private const val TAG = "Sm4Cipher"

    fun encrypt(plaintext: ByteArray): ByteArray? {
        if (plaintext.isEmpty()) return null
        return KmsProvider.encryptWithSession(plaintext) ?: run {
            SecureLog.w(TAG, "encrypt failed: native returned null")
            null
        }
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (ciphertext.isEmpty()) return null

        return KmsProvider.decryptWithMetadata(ciphertext, ByteArray(0))
            ?: KmsProvider.decryptWithSession(ciphertext)
            ?: run {
                SecureLog.w(TAG, "decrypt failed: both native paths returned null")
                null
            }
    }

    fun encryptWithMetadata(plaintext: ByteArray, metadata: ByteArray): ByteArray? {
        return KmsProvider.encryptWithMetadata(plaintext, metadata) ?: run {
            SecureLog.w(TAG, "encryptWithMetadata failed")
            null
        }
    }

    fun decryptWithMetadata(ciphertext: ByteArray, metadata: ByteArray): ByteArray? {
        return KmsProvider.decryptWithMetadata(ciphertext, metadata) ?: run {
            SecureLog.w(TAG, "decryptWithMetadata failed")
            null
        }
    }
}
