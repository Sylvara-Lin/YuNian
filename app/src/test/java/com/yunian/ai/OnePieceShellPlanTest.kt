package com.yunian.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnePieceShellPlanTest {
    private val projectRoot: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun onePieceShellPlanRejectsCommercialShellAndRequiresStubPayloadArchitecture() {
        val plan = File(projectRoot, "docs/security/one-piece-shell-hardening-plan.md").readText()

        assertTrue(plan.contains("商业壳不纳入主线"))
        assertTrue(plan.contains("StubApplication"))
        assertTrue(plan.contains("加密 payload"))
        assertTrue(plan.contains("InMemoryDexClassLoader"))
        assertTrue(plan.contains("root/hook/debug/frida/zygisk 命中时拒绝解密 payload"))
        assertTrue(plan.contains("不接网易易盾等商业壳"))
    }

    @Test
    fun manifestUsesRepositoryOwnedShellApplicationAndKeepsPayloadGateInMemoryOnly() {
        val manifest = File(projectRoot, "app/src/main/AndroidManifest.xml").readText()
        val shell = File(
            projectRoot,
            "app/src/main/java/com/yunian/ai/security/YuNianShellApplication.kt"
        ).readText()

        assertTrue(manifest.contains("android:name=\".security.YuNianShellApplication\""))
        assertTrue(shell.contains("class YuNianShellApplication : YuNianApplication()"))
        assertTrue(shell.contains("OnePieceShellGate.recordStartupPreflight(base)"))
        assertTrue(shell.contains("runCatching { SecurityGuard.productionPreflight(context) }"))
        assertTrue(shell.contains("fun verifyBeforePayload"))
        assertTrue(shell.contains("isTrustedForSensitiveOps"))
        assertTrue(shell.contains("InMemoryDexClassLoader"))
        assertTrue(shell.contains("decryptedPayload.fill(0)"))
    }
}
