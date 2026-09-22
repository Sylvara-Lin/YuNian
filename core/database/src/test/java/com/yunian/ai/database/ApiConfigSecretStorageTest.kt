package com.yunian.ai.database

import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.repository.ApiConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigSecretStorageTest {

    private class FakeSecretCodec : ApiConfigRepository.SecretCodec {
        var tampered = false
        var migratedPlaintext: String? = null

        override fun encrypt(plaintext: String): String = "enc:test:${plaintext.reversed()}"

        override fun decrypt(value: String): String? {
            if (tampered) return null
            return if (value.startsWith("enc:test:")) {
                value.removePrefix("enc:test:").reversed()
            } else {
                value
            }
        }

        override fun isEncrypted(value: String): Boolean = value.startsWith("enc:test:")
    }

    @Test
    fun apiKeyIsEncryptedBeforeRoomStorageAndDecryptedOnlyForUse() {
        val codec = FakeSecretCodec()
        val config = ApiConfig(
            provider = ApiProvider.OPENAI,
            name = "primary",
            apiKey = "sk-live-sensitive",
            baseUrl = ApiProvider.OPENAI.defaultBaseUrl,
            model = ApiProvider.OPENAI.defaultModel
        )

        val stored = ApiConfigRepository.encryptForStorage(config, codec)

        assertTrue(stored.apiKey.startsWith("enc:test:"))
        assertFalse(stored.apiKey.contains("sk-live-sensitive"))
        assertEquals("sk-live-sensitive", ApiConfigRepository.decryptForUse(stored, codec)?.apiKey)
    }

    @Test
    fun plaintextLegacyApiKeyIsDetectedForLazyMigrationWithoutLoggingSecret() {
        val codec = FakeSecretCodec()
        val legacy = ApiConfig(
            provider = ApiProvider.DEEPSEEK,
            apiKey = "legacy-plaintext-key",
            baseUrl = ApiProvider.DEEPSEEK.defaultBaseUrl,
            model = ApiProvider.DEEPSEEK.defaultModel
        )

        assertTrue(ApiConfigRepository.needsSecretMigration(legacy, codec))
        val migrated = ApiConfigRepository.encryptForStorage(legacy, codec)
        assertFalse(ApiConfigRepository.needsSecretMigration(migrated, codec))
        assertFalse(migrated.apiKey.contains("legacy-plaintext-key"))
    }

    @Test
    fun productionS0IsOnlyACompositeVmpWrapper() {
        val projectRoot = generateSequence(java.io.File(".").canonicalFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val source = java.io.File(
            projectRoot,
            "core/database/src/main/java/com/yunian/ai/database/repository/S0.kt"
        ).readText()

        assertTrue(source.contains("CompositeVmpRuntime.execute"))
        assertTrue(source.contains("OP_API_SECRET_ENCRYPT"))
        assertTrue(source.contains("OP_API_SECRET_DECRYPT"))
        assertFalse(source.contains("NativeBridge.encryptData"))
        assertFalse(source.contains("NativeBridge.decryptData"))
        assertFalse(source.contains("SecurityState.snapshot()"))
        assertFalse(source.contains("pkcs7Pad"))
        assertFalse(source.contains("pkcs7Unpad"))
    }

    @Test
    fun tamperedSecurityStateRefusesApiKeyDecryption() {
        val codec = FakeSecretCodec()
        val stored = ApiConfigRepository.encryptForStorage(
            ApiConfig(
                provider = ApiProvider.KIMI,
                apiKey = "moonshot-secret",
                baseUrl = ApiProvider.KIMI.defaultBaseUrl,
                model = ApiProvider.KIMI.defaultModel
            ),
            codec
        )

        codec.tampered = true

        assertNull(ApiConfigRepository.decryptForUse(stored, codec))
    }
}
