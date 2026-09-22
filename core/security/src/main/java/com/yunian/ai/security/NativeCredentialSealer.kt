package com.yunian.ai.security

import android.util.Base64

object NativeCredentialSealer {
    private const val PREFIX = "lyc1:"

    fun isSealed(storedValue: String): Boolean = storedValue.startsWith(PREFIX)

    fun seal(plaintext: String, recordName: String): String {
        ensureNativeCryptoReady()
        val ciphertext = NativeBridge.sealCredential(
            plaintext.toByteArray(Charsets.UTF_8),
            recordName.toByteArray(Charsets.UTF_8),
        )
            ?: throw IllegalStateException("Native credential sealing failed")
        return PREFIX + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun unseal(storedValue: String, recordName: String): String {
        if (!isSealed(storedValue)) return storedValue

        ensureNativeCryptoReady()
        val ciphertext = runCatching {
            Base64.decode(storedValue.removePrefix(PREFIX), Base64.NO_WRAP)
        }.getOrElse {
            throw IllegalStateException("Invalid native credential envelope", it)
        }
        val plaintext = NativeBridge.unsealCredential(
            ciphertext,
            recordName.toByteArray(Charsets.UTF_8),
        )
            ?: throw IllegalStateException("Native credential unsealing failed")
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun ensureNativeCryptoReady() {
        NativeBridge.wbAesInit()
    }
}
