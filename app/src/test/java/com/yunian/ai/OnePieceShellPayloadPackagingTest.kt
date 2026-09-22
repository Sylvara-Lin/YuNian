package com.yunian.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnePieceShellPayloadPackagingTest {
    private val projectRoot: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun payloadPackagingScriptUsesNativeKmsCompatibleManifestAndNeverWritesPlainPayload() {
        val script = File(projectRoot, "tools/package_shell_payload.py").readText()
        val appBuild = File(projectRoot, "app/build.gradle.kts").readText()
        val shell = File(
            projectRoot,
            "app/src/main/java/com/yunian/ai/security/YuNianShellApplication.kt"
        ).readText()

        assertTrue(script.contains("NATIVE_KMS_COMPATIBLE_MODE"))
        assertTrue(script.contains("KMS-WB-AES-CBC-METADATA-V1"))
        assertTrue(script.contains("metadata_size"))
        assertTrue(script.contains("padded_plaintext_size"))
        assertTrue(script.contains("YUNIAN_SHELL_PAYLOAD_KEY"))
        assertTrue(script.contains("shell_payload.bin"))
        assertTrue(script.contains("shell_payload_manifest.json"))
        assertTrue(script.contains("plaintext_sha256"))
        assertTrue(script.contains("ciphertext_sha256"))
        assertTrue(script.contains("zipfile.ZIP_DEFLATED"))
        assertFalse(script.contains("classes.dex.out"))

        assertTrue(appBuild.contains("packageShellPayload"))
        assertTrue(appBuild.contains("YUNIAN_SHELL_PAYLOAD_KEY"))
        assertTrue(appBuild.contains("src/main/assets/yunian_shell"))

        assertTrue(shell.contains("SHELL_PAYLOAD_ASSET"))
        assertTrue(shell.contains("assets.open(SHELL_PAYLOAD_ASSET)"))
        assertTrue(shell.contains("ciphertextSha256"))
        assertTrue(shell.contains("plaintextSha256"))
        assertTrue(shell.contains("MessageDigest.getInstance(\"SHA-256\")"))
    }
}
