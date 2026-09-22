plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yunian.ai.uicommon"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Iconsax 线性图标库：全局统一图标方案
    // 排除 KMP 的 org.jetbrains.compose 传递依赖（Android 端用项目自己的 androidx.compose BOM，
    // 避免其引入 androidx.core:1.17.0 要求 compileSdk 36）
    implementation(libs.iconsax.compose) {
        // 排除 KMP compose 与高版本 androidx.core 传递依赖（Android 端用项目自己的
        // androidx.compose BOM + core-ktx 1.13.1，避免 androidx.core 1.17.0 要求 compileSdk 36）
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "androidx.core")
    }
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    // api：玻璃组件公开 API 签名引用了 kyant Backdrop 类型，需透传给所有依赖方
    api(libs.kyant.backdrop)
    api(libs.kyant.capsule)
}
