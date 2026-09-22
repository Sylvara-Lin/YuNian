package com.yunian.ai.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import kotlin.test.*

@Ignore("Requires Android device with native KMS/WB-AES libraries")
class SecurityOrchestratorIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        SecurityGuard.init(context)

        assertTrue(SecurityGuard.isSafe(context),
            "Device must be in clean state for integration tests")
        assertTrue(KmsProvider.isReady,
            "KMS must be initialized")
    }

    @Test
    fun `encrypt then decrypt returns original data`() {
        val original = "Hello YuNian Security! 零信任测试数据 12345!@#".toByteArray()

        val encrypted = SecurityOrchestrator.encrypt(context, original)
        assertTrue(encrypted is EncryptionResult.Success,
            "Encryption should succeed: $encrypted")
        val ciphertext = (encrypted as EncryptionResult.Success).data

        assertFalse(original.contentEquals(ciphertext),
            "Ciphertext must not equal plaintext")

        assertTrue(ciphertext.size > original.size,
            "Ciphertext must include metadata+IV (got ${ciphertext.size}B for ${original.size}B input)")

        val decrypted = SecurityOrchestrator.decrypt(context, ciphertext)
        assertTrue(decrypted is EncryptionResult.Success,
            "Decryption should succeed: $decrypted")
        val plaintext = (decrypted as EncryptionResult.Success).data

        assertTrue(original.contentEquals(plaintext),
            "Decrypted data must match original")
    }

    @Test
    fun `1000 round-trips with random data`() {
        val rng = java.security.SecureRandom()

        repeat(1000) { i ->
            val size = rng.nextInt(1, 1024)
            val original = ByteArray(size).also { rng.nextBytes(it) }

            val encrypted = SecurityOrchestrator.encrypt(context, original)
            assertTrue(encrypted is EncryptionResult.Success,
                "Iteration $i: encrypt failed")

            val ciphertext = (encrypted as EncryptionResult.Success).data
            val decrypted = SecurityOrchestrator.decrypt(context, ciphertext)
            assertTrue(decrypted is EncryptionResult.Success,
                "Iteration $i: decrypt failed")

            val plaintext = (decrypted as EncryptionResult.Success).data
            assertTrue(original.contentEquals(plaintext),
                "Iteration $i: data mismatch (${original.size}B)")
        }
    }

    @Test
    fun `empty plaintext encryption`() {
        val result = SecurityOrchestrator.encrypt(context, ByteArray(0))
        assertTrue(result is EncryptionResult.Success,
            "Empty encryption should succeed (PKCS7 pads to 1 block)")
    }

    @Test
    fun `single byte encryption`() {
        val result = SecurityOrchestrator.encrypt(context, byteArrayOf(0x42))
        assertTrue(result is EncryptionResult.Success,
            "Single byte encryption should succeed")
        val decrypted = SecurityOrchestrator.decrypt(
            context, (result as EncryptionResult.Success).data)
        assertTrue(decrypted is EncryptionResult.Success)
        assertEquals(1, (decrypted as EncryptionResult.Success).data.size)
        assertEquals(0x42.toByte(), decrypted.data[0])
    }

    @Test
    fun `exactly one block 16 bytes`() {
        val data = ByteArray(16) { it.toByte() }
        val encrypted = SecurityOrchestrator.encrypt(context, data)
        assertTrue(encrypted is EncryptionResult.Success)

        assertEquals(48, (encrypted as EncryptionResult.Success).data.size)
    }

    @Test
    fun `large data 1MB`() {
        val data = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val encrypted = SecurityOrchestrator.encrypt(context, data)
        assertTrue(encrypted is EncryptionResult.Success)
        val decrypted = SecurityOrchestrator.decrypt(
            context, (encrypted as EncryptionResult.Success).data)
        assertTrue(decrypted is EncryptionResult.Success)
        assertTrue(data.contentEquals((decrypted as EncryptionResult.Success).data))
    }

    @Test
    fun `tampered ciphertext fails decryption`() {
        val original = "tamper this!".toByteArray()
        val encrypted = SecurityOrchestrator.encrypt(context, original)
        assertTrue(encrypted is EncryptionResult.Success)
        val tampered = (encrypted as EncryptionResult.Success).data.copyOf()

        tampered[tampered.size - 8] = (tampered[tampered.size - 8].toInt() xor 0xFF).toByte()

        val decrypted = SecurityOrchestrator.decrypt(context, tampered)

        assertTrue(decrypted is EncryptionResult.Error || decrypted is EncryptionResult.Breach,
            "Tampered ciphertext should fail: $decrypted")
    }

    @Test
    fun `truncated ciphertext fails`() {
        val result = SecurityOrchestrator.decrypt(context, ByteArray(10))
        assertTrue(result is EncryptionResult.Error,
            "Truncated ciphertext should return Error")
    }

    @Test
    fun `audit chain integrity at startup`() {

        SecurityOrchestrator.encrypt(context, "audit test data".toByteArray())

        val chainOk = AuditLogger.verifyAuditChain(context)
        assertTrue(chainOk, "Audit chain should be intact after normal operations")
    }

    @Test
    fun `audit chain covers crypto lifecycle events`() {
        val data = "lifecycle test".toByteArray()

        SecurityOrchestrator.encrypt(context, data)

        assertTrue(AuditLogger.verifyChain(context),
            "Audit chain should be intact after crypto lifecycle")
    }

    @Test
    fun `concurrent encryption 100 threads`() {
        val data = "concurrent test payload".toByteArray()
        val threads = mutableListOf<Thread>()
        val results = java.util.concurrent.ConcurrentLinkedQueue<EncryptionResult>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()

        repeat(100) { i ->
            val thread = Thread {
                try {
                    val result = SecurityOrchestrator.encrypt(context, data)
                    results.add(result)
                    if (result is EncryptionResult.Success) {
                        val decrypted = SecurityOrchestrator.decrypt(context, result.data)
                        results.add(decrypted)
                        if (decrypted !is EncryptionResult.Success ||
                            !data.contentEquals((decrypted as EncryptionResult.Success).data)) {
                            errors.add("Thread $i: decrypt mismatch")
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Thread $i: ${e.message}")
                }
            }
            threads.add(thread)
            thread.start()
        }

        threads.forEach { it.join(10_000) }

        assertTrue(errors.isEmpty(),
            "Concurrent test had errors: $errors")
        assertEquals(200, results.size,
            "Expected 200 results (100 encrypt + 100 decrypt)")
        assertTrue(results.all { it is EncryptionResult.Success },
            "All concurrent operations should succeed")
    }

    @Test
    fun `security startup order is correct`() {

        assertTrue(KmsProvider.isReady, "KMS should be ready after init")

        val ztState = NativeBridge.zeroTrustGetState()
        assertTrue(ztState in 0..2, "Zero-trust state should be valid: $ztState")

        val wbTest = NativeBridge.wbAesSelftest()
        assertEquals(0, wbTest, "White-box AES self-test should pass")

        val cryptoLevel = SecurityGuard.getCryptoLevel(context)
        assertTrue(cryptoLevel.ordinal >= SecurityGuard.CryptoLevel.C2.ordinal,
            "Crypto level should be at least C2: $cryptoLevel")
    }
}
