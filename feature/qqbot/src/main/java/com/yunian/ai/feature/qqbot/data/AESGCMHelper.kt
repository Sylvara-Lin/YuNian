package com.yunian.ai.feature.qqbot.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AESGCMHelper {

    private const val AES_KEY_SIZE = 32
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    fun generateKey(): String {
        val key = ByteArray(AES_KEY_SIZE)
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun decrypt(encryptedBase64: String, keyBase64: String): String {
        val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
        val raw = Base64.decode(encryptedBase64, Base64.NO_WRAP)

        require(raw.size >= GCM_IV_LENGTH) { "密文太短，无法提取 IV" }

        val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = raw.copyOfRange(GCM_IV_LENGTH, raw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
