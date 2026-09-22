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
        // Resolve python executable: prefer project venv, then known installs, then system python
        val venvPython = rootProject.file(".venv/Scripts/python.exe")
        val candidates = listOf(
            venvPython.takeIf { it.exists() }?.absolutePath,
            "C:/Users/linruoxi/AppData/Local/Programs/Python/Python312/python.exe",
            System.getenv("YUNIAN_PYTHON"),
            "python"
        ).filterNotNull()
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
        } ?: "python"
        logger.lifecycle("Dex2C: using python = ${pythonExe}")
        val pb = ProcessBuilder(
            pythonExe, dex2cTranspiler.absolutePath, dexInput.absolutePath,
            "--whitelist", dex2cWhitelist.absolutePath,
            "--out-cpp", file("$dex2cOutputDir/dex2c_methods.cpp").absolutePath,
            "--out-h", file("$dex2cOutputDir/dex2c_registry.h").absolutePath
        )
        pb.directory(rootProject.projectDir)
        pb.inheritIO()
        val proc = pb.start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            logger.error("Dex2C transpiler failed with exit code $exitCode")
        } else {
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
