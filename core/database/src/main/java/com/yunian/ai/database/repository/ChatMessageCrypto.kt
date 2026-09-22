package com.yunian.ai.database.repository

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.GroupMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ChatMessageCrypto {
    private const val KEYSTORE_ALIAS = "lianyu_chat_message_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val PREFIX = "enc:v1:"

    fun interface KeyProvider {
        fun getKey(): SecretKey?
    }

    private object AndroidKeyStoreKeyProvider : KeyProvider {
        private val cachedKey: SecretKey? by lazy {
            runCatching { getOrCreateAndroidKeyStoreKey() }.getOrNull()
        }

        override fun getKey(): SecretKey? = cachedKey
    }

    private val defaultKeyProvider: KeyProvider = AndroidKeyStoreKeyProvider

    suspend fun encryptForStorage(message: ChatMessage): ChatMessage = withContext(Dispatchers.IO) {
        encryptForStorage(message, defaultKeyProvider)
    }

    suspend fun encryptForStorage(messages: List<ChatMessage>): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) emptyList()
        else messages.map { encryptForStorage(it, defaultKeyProvider) }
    }

    internal fun encryptForStorage(message: ChatMessage, keyProvider: KeyProvider): ChatMessage {
        return message.copy(
            content = encrypt(message.content, keyProvider),
            searchContent = message.searchContent.ifBlank { message.content },
            linkString = encrypt(message.linkString, keyProvider)
        )
    }

    suspend fun decryptFromStorage(message: ChatMessage): ChatMessage = withContext(Dispatchers.IO) {
        decryptFromStorage(message, defaultKeyProvider)
    }

    suspend fun decryptFromStorage(messages: List<ChatMessage>): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) emptyList()
        else messages.map { decryptFromStorage(it, defaultKeyProvider) }
    }

    internal fun decryptFromStorage(message: ChatMessage, keyProvider: KeyProvider): ChatMessage {
        return try {
            message.copy(
                content = decrypt(message.content, keyProvider),
                linkString = decrypt(message.linkString, keyProvider)
            )
        } catch (e: Exception) {

            val fallback = plaintextSearchFallback(message.searchContent, message.content)
            message.copy(
                content = fallback,
                linkString = ""
            )
        }
    }

    suspend fun encryptForStorage(message: GroupMessage): GroupMessage = withContext(Dispatchers.IO) {
        encryptForStorage(message, defaultKeyProvider)
    }

    suspend fun encryptForStorageGroup(messages: List<GroupMessage>): List<GroupMessage> =
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) emptyList()
            else messages.map { encryptForStorage(it, defaultKeyProvider) }
        }

    internal fun encryptForStorage(message: GroupMessage, keyProvider: KeyProvider = defaultKeyProvider): GroupMessage {
        return message.copy(
            content = encrypt(message.content, keyProvider),
            searchContent = message.searchContent.ifBlank { message.content },
            linkString = encrypt(message.linkString, keyProvider)
        )
    }

    suspend fun decryptFromStorage(message: GroupMessage): GroupMessage = withContext(Dispatchers.IO) {
        decryptFromStorage(message, defaultKeyProvider)
    }

    suspend fun decryptFromStorageGroup(messages: List<GroupMessage>): List<GroupMessage> =
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) emptyList()
            else messages.map { decryptFromStorage(it, defaultKeyProvider) }
        }

    internal fun decryptFromStorage(message: GroupMessage, keyProvider: KeyProvider = defaultKeyProvider): GroupMessage {
        return try {
            message.copy(
                content = decrypt(message.content, keyProvider),
                linkString = decrypt(message.linkString, keyProvider)
            )
        } catch (e: Exception) {
            val fallback = plaintextSearchFallback(message.searchContent, message.content)
            message.copy(
                content = fallback,
                linkString = ""
            )
        }
    }

    suspend fun encrypt(plaintext: String): String = withContext(Dispatchers.IO) {
        encrypt(plaintext, defaultKeyProvider)
    }

    private fun encrypt(plaintext: String, keyProvider: KeyProvider): String {
        if (plaintext.isEmpty()) return plaintext
        if (plaintext.startsWith(PREFIX)) return plaintext
        val encryptionKey = keyProvider.getKey()
            ?: error("AndroidKeyStore chat message key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    suspend fun decrypt(value: String): String = withContext(Dispatchers.IO) {
        decrypt(value, defaultKeyProvider)
    }

    private fun decrypt(value: String, keyProvider: KeyProvider): String {
        if (value.isEmpty() || !value.startsWith(PREFIX)) return value
        val combined = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val decryptionKey = keyProvider.getKey()
            ?: error("AndroidKeyStore chat message key unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun plaintextSearchFallback(searchContent: String, encryptedContent: String): String {
        val candidate = searchContent.trim()
        if (candidate.isNotEmpty() && !candidate.startsWith(PREFIX) && !candidate.startsWith("enc:")) {
            return candidate
        }
        return if (encryptedContent.startsWith(PREFIX) || encryptedContent.startsWith("enc:")) {
            DECRYPT_FAILED_PLACEHOLDER
        } else {
            encryptedContent.ifBlank { DECRYPT_FAILED_PLACEHOLDER }
        }
    }

    private fun getOrCreateAndroidKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    const val DECRYPT_FAILED_PLACEHOLDER = "[消息解密失败]"
}

@kotlin.jvm.JvmName("filterDecryptedChat")
fun List<com.yunian.ai.database.model.ChatMessage>.filterDecrypted(): List<com.yunian.ai.database.model.ChatMessage> =
    filter { it.content != ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER }

@kotlin.jvm.JvmName("filterDecryptedGroup")
fun List<com.yunian.ai.database.model.GroupMessage>.filterDecrypted(): List<com.yunian.ai.database.model.GroupMessage> =
    filter { it.content != ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER }
