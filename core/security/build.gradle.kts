plugins {
    alias(libs.plugins.android.library)
}

import java.util.concurrent.TimeUnit

android {
    namespace = "com.yunian.ai.security"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    // externalNativeBuild — ndk-build from Android.mk
    externalNativeBuild {
        ndkBuild {
            path("src/main/cpp/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    kotlin {
        jvmToolchain(17)
    }
}

// Ensure dex2c stubs exist before ndk-build runs (both Debug and Release)
val dex2cOutputDir = file("src/main/cpp/generated")

tasks.register("ensureDex2cStubs") {
    description = "Ensure dex2c stub files exist for ndk-build"
    group = "security"
    outputs.dir(dex2cOutputDir)

    doLast {
        val cppFile = file("$dex2cOutputDir/dex2c_methods.cpp")
        val hFile = file("$dex2cOutputDir/dex2c_registry.h")
        if (!cppFile.exists() || !hFile.exists()) {
            dex2cOutputDir.mkdirs()
            if (!cppFile.exists()) {
                cppFile.writeText("""
#include <jni.h>
#include <stdint.h>
#include "dex2c_registry.h"
const uint32_t gDex2cTextCrc32 = 0x00000000;
JNINativeMethod gDex2cMethods[] = {};
const size_t gDex2cMethodCount = 0;
""".trimIndent())
            }
            if (!hFile.exists()) {
                hFile.writeText("""
#pragma once
#include <stdint.h>
#include <stddef.h>
extern JNINativeMethod gDex2cMethods[];
extern const size_t gDex2cMethodCount;
extern const uint32_t gDex2cTextCrc32;
""".trimIndent())
            }
        }
    }
}

tasks.matching { it.name.startsWith("configureNdkBuild") || it.name.startsWith("buildNdkBuild") }.configureEach {
    dependsOn("ensureDex2cStubs")
    // Release builds also run full dex2c transpilation
    if (name.contains("Release")) {
        dependsOn("dex2cTranspile")
    }
}

// Dex2C Transpiler Task — runs before ndkBuild (Release only)
// Only runs when minification is enabled (minifyReleaseWithR8 task exists)
val dex2cWhitelist = rootProject.file("tools/dex2c_whitelist.txt")
val dex2cTranspiler = rootProject.file("tools/dex2c_transpile.py")

tasks.register("dex2cTranspile") {
    description = "Transpile whitelisted DEX methods to C++ (Phase 2)"
    group = "security"

    val dexInput = rootProject.file("app/build/intermediates/dex/release/minifyReleaseWithR8/classes.dex")
    inputs.file(dex2cWhitelist)
    // Only depend on minifyReleaseWithR8 if minification is enabled
    // Check if minification is enabled via project property
    val disableMinify = rootProject.providers.gradleProperty("yunianDisableMinify")
        .map { it.equals("true", ignoreCase = true) || it == "1" }
        .orElse(false)
    if (!disableMinify.get()) {
        dependsOn(":app:minifyReleaseWithR8")
    }
    outputs.dir(dex2cOutputDir)
    // P2-15: always run — DEX may change without whitelist changing; security-critical
    outputs.upToDateWhen { false }

    doLast {
        dex2cOutputDir.mkdirs()
        // If minification is disabled or DEX not found, write empty stubs
        if (disableMinify.get() || !dexInput.exists()) {
            if (disableMinify.get()) {
                logger.lifecycle("Dex2C: minification disabled — transpilation skipped")
            } else {
                logger.warn("Dex2C: classes.dex not found — transpose skipped")
            }
            // Write empty stubs so NDK compilation doesn't fail
            dex2cOutputDir.mkdirs()
            file("$dex2cOutputDir/dex2c_methods.cpp").writeText("""
#include <jni.h>
#include <stdint.h>
#include "dex2c_registry.h"
const uint32_t gDex2cTextCrc32 = 0x00000000;
JNINativeMethod gDex2cMethods[] = {};
const size_t gDex2cMethodCount = 0;
""".trimIndent())
            file("$dex2cOutputDir/dex2c_registry.h").writeText("""
#pragma once
#include <stdint.h>
#include <stddef.h>
extern JNINativeMethod gDex2cMethods[];
extern const size_t gDex2cMethodCount;
extern const uint32_t gDex2cTextCrc32;
""".trimIndent())
            return@doLast
        }
        // Resolve python executable: -PyunianPython / YUNIAN_PYTHON → 项目 venv → 常见安装路径 → PATH。
        // ⚠️ System.getenv 读到的是 **Gradle daemon** 的环境（客户端 export 的变量不一定可见），
        //    因此同时支持 gradle.properties / -P 的 yunianPython。
        // 旧实现硬编码了他机路径（C:/Users/linruoxi/...），本机只剩 "python" → 命中
        // WindowsApps 商店占位符 → 退出码 9009 → Dex2C 实际从未转译（release 静默带空桩）。
        val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")
        val userProfile = System.getenv("USERPROFILE") ?: System.getProperty("user.home") ?: ""
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val candidates = mutableListOf<String>()
        (project.findProperty("yunianPython")?.toString() ?: System.getenv("YUNIAN_PYTHON"))
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates += it }
        rootProject.file(".venv/Scripts/python.exe")
            .takeIf { it.exists() }
            ?.let { candidates += it.absolutePath }
        if (isWindowsHost) {
            // 路径统一用正斜杠：Windows 同样接受，且避免 Kotlin 字符串转义（\a \P 等非法序列）
            candidates += listOf(
                "$userProfile/anaconda3/python.exe",
                "$userProfile/miniconda3/python.exe",
                "$localAppData/Programs/Python/Python313/python.exe",
                "$localAppData/Programs/Python/Python312/python.exe",
                "$localAppData/Programs/Python/Python311/python.exe",
                "C:/Python313/python.exe",
                "C:/Python312/python.exe",
                "C:/Python311/python.exe",
                "py",
                "python"
            )
        } else {
            candidates += listOf("python3", "python")
        }
        @Suppress("DEPRECATION")
        val pythonExe = candidates.firstOrNull {
            try {
                val probe = ProcessBuilder(it, "--version")
                    .redirectErrorStream(true)
                    .start()
                probe.waitFor(5, TimeUnit.SECONDS)
                probe.exitValue() == 0
            } catch (e: Exception) {
                false
            }
        } ?: if (isWindowsHost) "python" else "python3"
        logger.lifecycle("Dex2C: using python = ${pythonExe}")
        val pb = ProcessBuilder(
            pythonExe, dex2cTranspiler.absolutePath, dexInput.absolutePath,
            "--whitelist", dex2cWhitelist.absolutePath,
            "--out-cpp", file("$dex2cOutputDir/dex2c_methods.cpp").absolutePath,
            "--out-h", file("$dex2cOutputDir/dex2c_registry.h").absolutePath
        )
        pb.directory(rootProject.projectDir)
        // ⚠️ 不能用 inheritIO()：Gradle daemon 的 stdout 管道无人消费时，子进程写满
        //    管道缓冲（约 4KB）即永久阻塞——2026-09-28 本任务因此挂死 10 分钟、
        //    进程存活 599s 却只消耗 0.59s CPU。改为重定向到日志文件 + 超时兜底。
        val dex2cLog = file("$dex2cOutputDir/dex2c_transpile.log")
        pb.redirectErrorStream(true)
        pb.redirectOutput(dex2cLog)
        val proc = pb.start()
        val finished = proc.waitFor(5, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            logger.error("Dex2C transpiler 超时（5 分钟）已终止，日志：${dex2cLog.absolutePath}")
        } else if (proc.exitValue() != 0) {
            logger.error(
                "Dex2C transpiler failed with exit code ${proc.exitValue()} " +
                    "(日志：${dex2cLog.absolutePath}；检查 python：-PyunianPython=<python.exe 绝对路径> " +
                    "或 gradle.properties 的 yunianPython)"
            )
        } else {
            // 回显尾部若干行，便于在构建日志里看到 Transpiled: N 统计
            dex2cLog.readLines().takeLast(12).forEach { logger.lifecycle("[dex2c] $it") }
            logger.lifecycle("Dex2C: transpilation complete")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
