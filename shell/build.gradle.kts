// Shell module — build with plain Kotlin (JVM target)
// Not an Android library; used only for class structure reference.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
    // 本模块仅作「类结构参考」，不参与任何构建产物，也没有任何模块依赖它。
    // 其中的 Android 依赖源码（com/stub/**、com/yunian/ai/security/**）使用了
    // android.* API，无法在纯 JVM target 上编译；予以排除，避免
    // :shell:compileKotlin 失败导致 `gradlew test` / `gradlew build` 不可用。
    sourceSets.named("main") {
        kotlin.exclude("com/stub/**", "com/yunian/ai/security/**")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
}
