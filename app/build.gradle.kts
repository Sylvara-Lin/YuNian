plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.devtools.ksp)
}

android {
    namespace = "com.yunian.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yunian.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "1.10.8"

        manifestPlaceholders["developerName"] = "苏苏"
        manifestPlaceholders["developerOrg"] = "YuNian"

        // Developer: 苏苏 / Organization: YuNian

        // Force multi-DEX output
        multiDexEnabled = true
        multiDexKeepProguard = file("tools/shell-multidex-keep.pro")

        manifestPlaceholders[
            "VIVO_PUSH_API_KEY"] = project.findProperty("VIVO_PUSH_API_KEY")?.toString() ?: ""
        manifestPlaceholders[
            "VIVO_PUSH_APP_ID"] = project.findProperty("VIVO_PUSH_APP_ID")?.toString() ?: ""

        buildConfigField("String", "HARDENING_LEVEL", "\"VMPv2.0+Keystore+AES256GCM\"")

        testInstrumentationRunner = if (providers.gradleProperty("thinShellTestRunner").isPresent) {
            "com.yunian.ai.performance.JavaThinShellInstrumentation"
        } else {
            "androidx.test.runner.AndroidJUnitRunner"
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    ndkVersion = "30.0.14904198"

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx / onnxruntime 等可能与其它 native 依赖撞名，取第一份即可
            pickFirsts += listOf(
                "lib/**/libonnxruntime.so",
                "lib/**/libsherpa-onnx-c-api.so",
                "lib/**/libsherpa-onnx-cxx-api.so",
                "lib/**/libsherpa-onnx-jni.so",
            )
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("YUNIAN_STORE_PASSWORD") ?: project.findProperty("YUNIAN_STORE_PASSWORD") as String? ?: "debug_password_placeholder"
            keyAlias = System.getenv("YUNIAN_KEY_ALIAS") ?: project.findProperty("YUNIAN_KEY_ALIAS") as String? ?: "your_alias"
            keyPassword = System.getenv("YUNIAN_KEY_PASSWORD") ?: project.findProperty("YUNIAN_KEY_PASSWORD") as String? ?: "debug_password_placeholder"
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isDebuggable = true
        }
        // Release minification can be disabled via -PyunianDisableMinify=true
// Useful when Dex2C/R8 fails (exit code 9009)
val disableMinify = providers.gradleProperty("yunianDisableMinify")
    .map { it.equals("true", ignoreCase = true) || it == "1" }
    .orElse(false)

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = !disableMinify.get()
            isShrinkResources = false  // Shell loads DEX from assets — must not strip
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "予念")
        }
    }
    sourceSets {
        getByName("debug") {
            manifest.srcFile("src/shell/AndroidManifest.xml")
        }
        getByName("release") {
            manifest.srcFile("src/shell/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // jniLibs are picked up automatically from src/main/jniLibs/
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

// Prefer a real Python interpreter. WindowsApps store stubs often fail.
fun resolvePythonExecutable(): String {
    val candidates = mutableListOf<String>()
    System.getenv("YUNIAN_PYTHON")?.let { candidates += it }
    if (isWindows) {
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val userProfile = System.getenv("USERPROFILE") ?: ""
        candidates += listOf(
            "$userProfile\\anaconda3\\python.exe",
            "$localAppData\\Programs\\Python\\Python313\\python.exe",
            "$localAppData\\Programs\\Python\\Python312\\python.exe",
            "$localAppData\\Programs\\Python\\Python311\\python.exe",
            "C:\\Python312\\python.exe",
            "C:\\Python311\\python.exe",
            "py",
            "python",
        )
    } else {
        candidates += listOf("python3", "python")
    }
    for (candidate in candidates) {
        val file = file(candidate)
        if (file.isFile) return file.absolutePath
        // bare command names (py/python/python3)
        if (!candidate.contains('\\') && !candidate.contains('/')) return candidate
    }
    return if (isWindows) "python" else "python3"
}

val pythonExecutable = resolvePythonExecutable()

val shellPayloadAssetsDir = layout.projectDirectory.dir("src/main/assets/yunian_shell")
val unsignedReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val thinShellReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-thin-shell.apk")

tasks.register<Exec>("packageShellPayload") {
    group = "security"
    description = "Encrypt release classes*.dex into in-repo one-piece shell payload assets. Requires YUNIAN_SHELL_PAYLOAD_KEY for CI smoke packaging; production should use native KMS-compatible exporter."
    dependsOn("assembleRelease")
    onlyIf { providers.environmentVariable("YUNIAN_SHELL_PAYLOAD_KEY").orNull != null }
    inputs.file(unsignedReleaseApk)
    outputs.dir(shellPayloadAssetsDir)
    commandLine(
        pythonExecutable,
        "${rootProject.projectDir}/tools/package_shell_payload.py",
        "--apk",
        unsignedReleaseApk.get().asFile.absolutePath,
        "--out",
        shellPayloadAssetsDir.asFile.absolutePath
    )
}

// FIX 1: Strip plaintext classes*.dex from release APK and replace root DEX
// with pure-Java thin shell (~KB). Business DEX is encrypted into assets/shell/*.dat.
//
// Default: thin shell is DISABLED (requires Android build-tools + Python + Dex2C).
// To enable thin shell packaging:
//   ./gradlew assembleRelease -PyunianEnableThinShell=true
// Requirements: Android build-tools, Python 3, Dex2C
// Optional env:
//   YUNIAN_PYTHON                 absolute path to python.exe
//   YUNIAN_STORE_PASSWORD         release keystore password (for --sign)
//   YUNIAN_KEY_PASSWORD           release key password (for --sign)
//   YUNIAN_KEY_ALIAS              release key alias
//   YUNIAN_THIN_SHELL_SIGN=0      skip re-sign (default: sign when keystore passwords available)
// Thin shell is ENABLED by default. To DISABLE thin shell:
//   ./gradlew assembleRelease -PyunianSkipThinShell=true
val skipThinShell = providers.gradleProperty("yunianSkipThinShell")
    .map { it.equals("true", ignoreCase = true) || it == "1" }
    .orElse(false)

tasks.register("packageThinShellRelease") {
    group = "security"
    description = "Replace release root DEX with pure-Java thin shell and encrypt business DEX into assets/shell. Requires Android build-tools + Python + Dex2C."
    dependsOn("assembleRelease")
    onlyIf { !skipThinShell.get() }

    val inputApk = releaseApk
    val outputApk = thinShellReleaseApk
    val plainBackup = layout.buildDirectory.file("outputs/apk/release/app-release-plain.apk")
    val script = rootProject.layout.projectDirectory.file("tools/package_thin_shell.py")
    val envelopeOut = layout.buildDirectory.file("outputs/apk/release/app-release-thin-shell.apk.envelope.json")

    inputs.file(inputApk)
    inputs.file(script)
    inputs.dir(layout.projectDirectory.dir("src/shell/java"))
    outputs.file(outputApk)
    outputs.file(envelopeOut)

    // Prefer env; fall back to gradle.properties project properties for local release.
    val storePass = providers.environmentVariable("YUNIAN_STORE_PASSWORD")
        .orElse(providers.provider { project.findProperty("YUNIAN_STORE_PASSWORD")?.toString() ?: "" })
    val keyPass = providers.environmentVariable("YUNIAN_KEY_PASSWORD")
        .orElse(providers.provider { project.findProperty("YUNIAN_KEY_PASSWORD")?.toString() ?: "" })
    val keyAlias = providers.environmentVariable("YUNIAN_KEY_ALIAS")
        .orElse(providers.provider { project.findProperty("YUNIAN_KEY_ALIAS")?.toString() ?: "your_alias" })
    val signOverride = providers.environmentVariable("YUNIAN_THIN_SHELL_SIGN").orNull
    val shouldSign = when (signOverride?.lowercase()) {
        "0", "false", "no" -> false
        "1", "true", "yes" -> true
        else -> storePass.orNull?.isNotBlank() == true && keyPass.orNull?.isNotBlank() == true
    }

    doLast {
        val releaseFile = inputApk.get().asFile
        val backupFile = plainBackup.get().asFile
        if (!releaseFile.exists() && !backupFile.exists()) {
            throw GradleException("Release APK not found: ${releaseFile.absolutePath}")
        }

        // Prefer a plain multi-MB APK as packaging input.
        val packageInput = when {
            releaseFile.exists() && releaseFile.length() > 2L * 1024 * 1024 -> releaseFile
            backupFile.exists() && backupFile.length() > 2L * 1024 * 1024 -> {
                println("Using plain backup as thin-shell input: ${backupFile.absolutePath}")
                backupFile
            }
            releaseFile.exists() -> releaseFile
            else -> throw GradleException("No usable plain release APK for thin-shell packaging")
        }

        val cmd = mutableListOf(
            pythonExecutable,
            script.asFile.absolutePath,
            "--apk", packageInput.absolutePath,
            "--out", outputApk.get().asFile.absolutePath,
            "--release-key",
            "--envelope", envelopeOut.get().asFile.absolutePath,
        )
        if (shouldSign) {
            cmd += listOf(
                "--sign",
                "--store-pass", storePass.get(),
                "--key-pass", keyPass.get(),
                "--alias", keyAlias.get(),
            )
        }

        println("Thin-shell packaging with python: $pythonExecutable")
        println("Input APK: ${packageInput.absolutePath}")
        // Gradle 9 removed Project.exec; use ProcessBuilder for portability.
        val process = ProcessBuilder(cmd)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { println(it) }
        }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("package_thin_shell.py failed with exit code $exit")
        }
    }
}

// Promote thin-shell APK to the canonical release artifact path so
// assembleRelease consumers never ship plaintext multi-MB root DEX by default.
tasks.register("promoteThinShellRelease") {
    group = "security"
    description = "Replace app-release.apk with thin-shell APK and keep plain backup. Requires thin shell packaging enabled."
    dependsOn("packageThinShellRelease")
    onlyIf { !skipThinShell.get() }

    val inputApk = releaseApk
    val thinApk = thinShellReleaseApk
    val plainBackup = layout.buildDirectory.file("outputs/apk/release/app-release-plain.apk")

    inputs.file(thinApk)
    outputs.file(inputApk)
    outputs.file(plainBackup)

    doLast {
        val releaseFile = inputApk.get().asFile
        val thinFile = thinApk.get().asFile
        val backupFile = plainBackup.get().asFile
        if (!thinFile.exists()) {
            throw GradleException("Thin-shell APK missing: ${thinFile.absolutePath}")
        }
        // Only backup if current release still looks like a plain Gradle APK.
        if (releaseFile.exists() && releaseFile.length() > 2 * 1024 * 1024) {
            backupFile.parentFile.mkdirs()
            releaseFile.copyTo(backupFile, overwrite = true)
            println("Plain release backup: ${backupFile.absolutePath} (${backupFile.length()} bytes)")
        }
        thinFile.copyTo(releaseFile, overwrite = true)
        println("Promoted thin-shell → ${releaseFile.absolutePath} (${releaseFile.length()} bytes)")
    }
}

// Wire thin-shell as the release packaging gate.
// Enabled by default (skipThinShell=false). To disable: -PyunianSkipThinShell=true
afterEvaluate {
    if (!skipThinShell.get()) {
        tasks.named("assembleRelease").configure {
            finalizedBy("packageThinShellRelease")
        }
        tasks.named("packageThinShellRelease").configure {
            finalizedBy("promoteThinShellRelease")
        }
        logger.lifecycle("Thin shell packaging ENABLED (use -PyunianSkipThinShell=true to disable)")
    } else {
        logger.lifecycle("yunianSkipThinShell=true — release will keep plaintext root DEX (thin shell disabled)")
    }
}






dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:ui-common"))
    // S6：OutboundPort 直接使用 core:wechat 清洗/出站类型（feature:wechat 为 implementation，不传递）
    implementation(project(":core:wechat"))
    // ★ Agent 架构：AgentFacade 编排层 + UniFFI 绑定 + 4 ABI 的 liblianyu_agent.so。
    // 必须由 :app 直接依赖，否则 feature 模块走 implementation 不会把 native 库透传到 APK。
    implementation(project(":core:agent"))

    // Feature modules
    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:wechat"))
    implementation(project(":feature:qqbot"))
    implementation(project(":feature:backup"))
    implementation(project(":feature:coffee"))
    implementation(project(":feature:automation"))
    implementation(project(":feature:worldbook"))
    implementation(project(":feature:mcp"))
    implementation(project(":feature:skills"))

    // sherpa-onnx: 离线流式语音识别，运行时由 app 模块提供
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.work.runtime.ktx)
    // 液态玻璃：Kyant Backdrop（真实模糊/折射/高光）+ Capsule（连续圆角），与 RiseDiary 同款
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.capsule)

    // 厂商 Push SDK
    // OPPO / vivo 使用本地 aar，请从各厂商开放平台下载后放置到 app/libs
    val oppoAar = file("libs/oppo-push-3.0.0.aar")
    if (oppoAar.exists()) {
        implementation(files(oppoAar))
    }
    val vivoAar = file("libs/vivo-push-4.1.5.0.aar")
    if (vivoAar.exists()) {
        implementation(files(vivoAar))
    }
    // 华为 HMS Push 使用 Maven 依赖
    implementation(libs.huawei.hms.push)

    // 小米推送：请从 https://dev.mi.com/ 下载 aar 放到 app/libs/xiaomi-push-x.x.x.aar，
    // 然后取消下面注释并同步 Gradle。
    // implementation(files("libs/xiaomi-push-6.0.1.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}