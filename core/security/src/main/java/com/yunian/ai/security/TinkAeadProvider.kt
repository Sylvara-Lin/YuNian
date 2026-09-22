package com.yunian.ai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.security.KeyStore
import javax.crypto.KeyGenerator

object TinkAeadProvider {
    private const val KEK_ALIAS = "lianyu_tink_kek"
    private const val MASTER_KEY_URI = "android-keystore://$KEK_ALIAS"

    @Volatile private var initialized = false
    @Volatile private var smallSecretAead: Aead? = null

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            AeadConfig.register()
            ensureKekExists()
            initialized = true
        }
    }

    fun encryptSecret(plaintext: ByteArray): ByteArray {
        ensureInitialized()
        return getOrCreateSmallSecretAead().encrypt(plaintext, byteArrayOf())
    }

    fun decryptSecret(ciphertext: ByteArray): ByteArray? {
        ensureInitialized()
        return runCatching {
            getOrCreateSmallSecretAead().decrypt(ciphertext, byteArrayOf())
        }.getOrNull()
    }

    fun signManifest(manifestBytes: ByteArray): ByteArray {
        ensureInitialized()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(manifestBytes)
        val payload = "manifest:v1:".toByteArray() + hash
        return getOrCreateSmallSecretAead().encrypt(payload, byteArrayOf())
    }

    fun verifyManifest(manifestBytes: ByteArray, signature: ByteArray): Boolean {
        ensureInitialized()
        val decrypted = runCatching {
            getOrCreateSmallSecretAead().decrypt(signature, byteArrayOf())
        }.getOrNull() ?: return false

        val prefix = "manifest:v1:".toByteArray()
        if (decrypted.size < prefix.size + 32) return false
        if (!decrypted.copyOfRange(0, prefix.size).contentEquals(prefix)) return false

        val expectedHash = decrypted.copyOfRange(prefix.size, decrypted.size)
        val actualHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(manifestBytes)
        return expectedHash.contentEquals(actualHash)
    }

    fun encryptPayload(plaintext: ByteArray): ByteArray {
        ensureInitialized()

        val dekHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val dekAead: Aead = dekHandle.getPrimitive(Aead::class.java)
        val ciphertext = dekAead.encrypt(plaintext, byteArrayOf())

        val serializedDek = TinkProtoKeysetFormat.serializeKeyset(
            dekHandle, InsecureSecretKeyAccess.get()
        )
        val kekAead = createKekAead()
        val encryptedKeyset = kekAead.encrypt(serializedDek, byteArrayOf())

        val lenB = java.nio.ByteBuffer.allocate(4).putInt(encryptedKeyset.size).array()
        return lenB + encryptedKeyset + ciphertext
    }

    fun decryptPayload(ciphertext: ByteArray): ByteArray? {
        ensureInitialized()
        return runCatching {
            val buf = java.nio.ByteBuffer.wrap(ciphertext)
            if (buf.remaining() < 4) return null
            val keysetLen = buf.int
            if (buf.remaining() < keysetLen) return null
            val encryptedKeyset = ByteArray(keysetLen); buf.get(encryptedKeyset)
            val payload = ByteArray(buf.remaining()); buf.get(payload)

            val kekAead = createKekAead()
            val serializedDek = kekAead.decrypt(encryptedKeyset, byteArrayOf())
            val dekHandle = TinkProtoKeysetFormat.parseKeyset(
                serializedDek, InsecureSecretKeyAccess.get()
            )
            val dekAead: Aead = dekHandle.getPrimitive(Aead::class.java)
            dekAead.decrypt(payload, byteArrayOf())
        }.getOrNull()
    }

    private fun ensureInitialized() { if (!initialized) initialize() }

    private fun getOrCreateSmallSecretAead(): Aead {
        return smallSecretAead ?: synchronized(this) {
            smallSecretAead ?: KeysetHandle.generateNew(
                KeyTemplates.get("AES128_GCM")
            ).getPrimitive(Aead::class.java).also { smallSecretAead = it }
        }
    }

    private fun createKekAead(): Aead =
        AndroidKeystoreKmsClient().getAead(MASTER_KEY_URI)

    private fun ensureKekExists() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEK_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEK_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        }
    }
}
