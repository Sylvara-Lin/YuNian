package com.yunian.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellManifestRoutingTest {
    private val projectRoot: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun appManifestRoutesSystemEntryPointsThroughShellComponents() {
        val manifest = File(projectRoot, "app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:name=\".security.SActivity\""))
        assertTrue(manifest.contains("android:name=\"com.yunian.ai.security.SService\""))
        assertTrue(manifest.contains("android:name=\"com.yunian.ai.security.SReceiver\""))
    }

    @Test
    fun wechatManifestRoutesWeChatEntryPointsThroughShellComponents() {
        val appManifest = File(projectRoot, "app/src/main/AndroidManifest.xml").readText()
        val wechatManifest = File(projectRoot, "feature/wechat/src/main/AndroidManifest.xml").readText()

        // SWechatPollingService 的声明已迁移到 app/src/main/AndroidManifest.xml（specialUse），
        // 与 SService / QQBotForegroundService / CompanionKeepAliveService 同构；
        // feature:wechat 的 library manifest 只保留 SWechatBootReceiver。
        assertTrue(appManifest.contains("android:name=\"com.yunian.ai.security.SWechatPollingService\""))
        assertTrue(wechatManifest.contains("android:name=\"com.yunian.ai.security.SWechatBootReceiver\""))

        assertTrue(!wechatManifest.contains("SWechatProactiveMessageReceiver"))
        assertTrue(!wechatManifest.contains("SEND_PROACTIVE"))
    }
}
