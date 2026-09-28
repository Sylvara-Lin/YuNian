package com.yunian.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseConfigurationTest {
    private val projectRoot: File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun proguardKeepsCompleteJniBridgeClassesForNativeRegistration() {
        val rules = File(projectRoot, "app/proguard-rules.pro").readText()

        assertTrue(
            "NativeBridge is registered by native code using FindClass/RegisterNatives, so the class name and all member descriptors must be kept.",
            rules.contains("-keep,includedescriptorclasses class com.yunian.ai.security.NativeBridge { *; }")
        )
        assertTrue(
            "KmsProvider uses exported Java_com_yunian_ai_security_KmsProvider_* JNI symbols, so class name and private native method names must be kept.",
            rules.contains("-keep,includedescriptorclasses class com.yunian.ai.security.KmsProvider { *; }")
        )
    }

    @Test
    fun proguardAllowsBusinessSerializationRoomAndRetrofitNamesToBeObfuscated() {
        val rules = File(projectRoot, "app/proguard-rules.pro").readText()

        val forbiddenKeepRules = listOf(
            "-keep class kotlinx.serialization.** { *; }",
            "-keep,includedescriptorclasses class com.yunian.**\$\$serializer { *; }",
            "-keep class com.yunian.ai.database.AppDatabase { *; }",
            "-keep @androidx.room.Entity class com.yunian.ai.database.model.** { *; }",
            "-keep interface com.yunian.ai.database.dao.** { *; }",
            "-keep interface com.yunian.ai.network.OpenAiApi { *; }",
            "-keep interface com.yunian.ai.network.AnthropicApi { *; }",
            "-keep interface com.yunian.ai.network.GeminiApi { *; }"
        )

        forbiddenKeepRules.forEach { rule ->
            assertFalse("Release rules must not pin business/DTO class names: $rule", rules.contains(rule))
        }

        assertTrue(
            "Room database class may be kept for shrinking safety, but its release name must remain obfuscatable.",
            rules.contains("-keep,allowobfuscation class com.yunian.ai.database.AppDatabase { *; }")
        )
        assertTrue(
            "Room entity classes must remain obfuscatable in release builds.",
            rules.contains("-keep,allowobfuscation @androidx.room.Entity class com.yunian.ai.database.model.** { *; }")
        )
        assertTrue(
            "Retrofit API interfaces must remain obfuscatable in release builds.",
            rules.contains("-keep,allowobfuscation interface com.yunian.ai.network.OpenAiApi { *; }")
        )
    }

    @Test
    fun manifestsExposeOnlyShellComponentEntries() {
        val appManifest = File(projectRoot, "app/src/main/AndroidManifest.xml").readText()
        val wechatManifest = File(projectRoot, "feature/wechat/src/main/AndroidManifest.xml").readText()
        val manifests = appManifest + "\n" + wechatManifest

        assertTrue(
            "Launcher activity must be exposed only through the shell entry.",
            appManifest.contains("android:name=\".security.SActivity\"")
        )
        assertTrue(
            "Notification keep-alive service must be exposed only through the shell entry.",
            appManifest.contains("android:name=\"com.yunian.ai.security.SService\"")
        )
        assertTrue(
            "Notification boot receiver must be exposed only through the shell entry.",
            appManifest.contains("android:name=\"com.yunian.ai.security.SReceiver\"")
        )

        assertTrue(
            "WeChat foreground polling service must be exposed only through the shell entry.",
            wechatManifest.contains("android:name=\"com.yunian.ai.security.SWechatPollingService\"")
        )
        assertTrue(
            "WeChat boot receiver must be exposed only through the shell entry.",
            wechatManifest.contains("android:name=\"com.yunian.ai.security.SWechatBootReceiver\"")
        )
        assertFalse(
            "WeChat proactive BroadcastReceiver must be removed (S6 OutboundPort).",
            wechatManifest.contains("SWechatProactiveMessageReceiver") ||
                wechatManifest.contains("SEND_PROACTIVE")
        )

        listOf(
            "com.yunian.ai.MainActivity",
            "com.yunian.ai.feature.notification.CompanionKeepAliveService",
            "com.yunian.ai.feature.notification.BootReceiver",
            "com.yunian.ai.feature.wechat.service.WeChatPollingService",
            "com.yunian.ai.feature.wechat.service.WeChatBootReceiver",
            "com.yunian.ai.feature.wechat.service.WeChatProactiveMessageReceiver"
        ).forEach { originalEntry ->
            assertFalse(
                "Original component must not be declared directly in manifests: $originalEntry",
                manifests.contains("android:name=\"$originalEntry\"")
            )
        }
    }

    @Test
    fun serviceStartHelpersTargetShellServiceClassesOnly() {
        val keepAlive = File(
            projectRoot,
            "feature/notification/src/main/java/com/yunian/ai/feature/notification/CompanionKeepAliveService.kt"
        ).readText()
        val wechatPolling = File(
            projectRoot,
            "feature/wechat/src/main/java/com/yunian/ai/feature/wechat/service/WeChatPollingService.kt"
        ).readText()

        assertTrue(
            "Keep-alive start/stop helpers must route to shell service class.",
            keepAlive.contains("com.yunian.ai.security.SService")
        )
        assertFalse(
            "Keep-alive helpers must not construct explicit intents for the original service class.",
            keepAlive.contains("Intent(context, CompanionKeepAliveService::class.java)")
        )

        assertTrue(
            "WeChat polling start/stop helpers must route to shell service class.",
            wechatPolling.contains("com.yunian.ai.security.SWechatPollingService")
        )
        assertFalse(
            "WeChat polling helpers must not construct explicit intents for the original service class.",
            wechatPolling.contains("Intent(context, WeChatPollingService::class.java)")
        )
    }

    @Test
    fun internalSerializableModelsHaveSerialNameAnnotation() {

        val entities = mapOf(
            "core/database/src/main/java/com/yunian/ai/database/model/ChatMessage.kt" to "E0",
            "core/database/src/main/java/com/yunian/ai/database/model/GroupMessage.kt" to "E1",
            "core/database/src/main/java/com/yunian/ai/database/model/CompanionEntity.kt" to "E2",
        )
        entities.forEach { (relPath, expectedCode) ->
            val source = File(projectRoot, relPath).readText()
            assertTrue(
                "$relPath must have @SerialName(\"$expectedCode\")",
                source.contains("@SerialName(\"$expectedCode\")")
            )
        }

        val ilinkModels = File(
            projectRoot,
            "feature/wechat/src/main/java/com/yunian/ai/feature/wechat/data/model/IlinkModels.kt"
        ).readText()
        for (i in 0..7) {
            assertTrue(
                "M$i must have @SerialName(\"M$i\")",
                ilinkModels.contains("@SerialName(\"M$i\")")
            )
        }
    }
}
