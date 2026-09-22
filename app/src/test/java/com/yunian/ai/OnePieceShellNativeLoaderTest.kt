package com.yunian.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnePieceShellNativeLoaderTest {
    private val projectRoot: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun shellPayloadDecryptsThroughNativeKmsOnlyAndRejectsFilesystemDexLoaders() {
        val shell = File(
            projectRoot,
            "app/src/main/java/com/yunian/ai/security/YuNianShellApplication.kt"
        ).readText()
        val vmp = File(
            projectRoot,
            "core/security/src/main/java/com/yunian/ai/security/CompositeVmpRuntime.kt"
        ).readText()

        assertTrue(shell.contains("CompositeVmpRuntime.execute"))
        assertTrue(shell.contains("OP_SHELL_RECORD_STARTUP_PREFLIGHT"))
        assertTrue(shell.contains("OP_SHELL_VERIFY_BEFORE_PAYLOAD"))
        assertFalse(shell.contains("OP_SHELL_CREATE_PAYLOAD_LOADER"))
        assertFalse(shell.contains("KmsProvider.decryptWithMetadata"))
        assertFalse(shell.contains("SecurityGuard.productionPreflight"))
        assertFalse(shell.contains("SecurityState.snapshot()"))
        assertFalse(shell.contains("InMemoryDexClassLoader"))
        assertFalse(shell.contains("dalvik.system.DexClassLoader"))
        assertFalse(shell.contains("optimizedDirectory"))
        assertFalse(shell.contains("codeCacheDir"))

        assertTrue(vmp.contains("OP_SHELL_CREATE_PAYLOAD_LOADER"))
        assertTrue(vmp.contains("OP_API_SECRET_ENCRYPT"))
        assertTrue(vmp.contains("OP_API_SECRET_DECRYPT"))
        assertTrue(vmp.contains("KmsProvider.decryptWithMetadata"))
        assertTrue(vmp.contains("SecurityGuard.productionPreflight"))
        assertTrue(vmp.contains("SecurityState.snapshot()"))
        assertTrue(vmp.contains("InMemoryDexClassLoader"))
        assertTrue(vmp.contains("decryptedPaddedPayload.fill(0)"))
        assertTrue(vmp.contains("encryptedPayload.fill(0)"))
        assertTrue(vmp.contains("KmsProvider.decryptWithMetadata"))
        assertTrue(vmp.contains("SecurityGuard.productionPreflight"))
        assertTrue(vmp.contains("SecurityState.snapshot()"))
        assertTrue(vmp.contains("InMemoryDexClassLoader"))
        assertTrue(vmp.contains("decryptedPaddedPayload.fill(0)"))
        assertTrue(vmp.contains("encryptedPayload.fill(0)"))
    }
}
