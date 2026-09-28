pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        // 厂商 Push SDK 仓库：华为 HMS
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "予念"
include(":app")
include(":core:domain")
include(":core:common")
include(":core:database")
include(":core:network")
include(":core:security")
include(":core:ui-common")
include(":core:agent")
include(":core:wechat")
include(":feature:companion")
include(":feature:chat")
include(":feature:groupchat")
include(":feature:memory")
include(":feature:notification")
include(":feature:profile")
include(":feature:settings")
// D4：feature:localmodel 已删除（本地推理统一由 core:agent 的 Rust Cordis Agent 承担）
include(":feature:wechat")
include(":feature:qqbot")
include(":feature:backup")
include(":feature:coffee")
include(":feature:automation")
include(":feature:worldbook")
include(":feature:mcp")
include(":feature:skills")
include(":shell")
