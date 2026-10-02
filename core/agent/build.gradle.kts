plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yunian.ai.agent"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    // 仅依赖契约层：工具/循环定义由 Rust 侧产出，Kotlin 侧只做绑定 + 回调适配
    implementation(project(":core:domain"))
    // SkillStore 实现需要 Room 索引（混合存储之"索引"侧）与协程
    implementation(project(":core:database"))
    // 角色串线防线（ScriptTurnStripper）：微信/QQ/语音桥接路径在落库与记忆提取前，
    // 剥离模型续写的「用户回合」脚本（与主聊天路径同源缺陷）。
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    // UniFFI 生成的 Kotlin 绑定依赖 JNA（com.sun.jna.Pointer 等）。
    // Android 上必须取官方 AAR（含各 ABI 的 libjnidispatch.so），普通 jar 会在运行期加载 native 失败。
    // 版本统一由 libs.versions.toml 管理（不用 `jna:5.13.0@aar` 硬编码形式）。
    implementation(variantOf(libs.jna) { artifactType("aar") })

    // Eval 断言引擎等纯逻辑单测
    testImplementation(libs.junit)
    // AiActivityBus 的 StateFlow 状态机单测（runBlocking / flow）
    testImplementation(libs.kotlinx.coroutines.core)
    // 本模块源码走 org.json。Android 的 org.json 只是抛 Stub! 的空壳，
    // JVM 单测必须挂真实实现，否则 JSONObject/JSONArray 一调用就崩。
    testImplementation(libs.org.json)
}
