# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

YuNian (予念) is an Android AI companion app built with Kotlin and Jetpack Compose. It uses a **feature-based modular architecture** with 25 Gradle modules: 1 `:app` entry, 15 `feature:*` modules, 8 `core:*` modules, and 1 `:shell` JVM test module.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires release.keystore and env vars YUNIAN_STORE_PASSWORD / YUNIAN_KEY_PASSWORD)
./gradlew assembleRelease

# Check module dependencies
./gradlew app:dependencies --configuration implementation

# Clean build (required after core module changes that affect many dependents)
./gradlew clean assembleDebug
```

Gradle wrapper uses a Tencent mirror (`mirrors.cloud.tencent.com/gradle/gradle-9.4.1-bin.zip`). Dependency repositories are configured to use Alibaba Cloud Maven mirrors first, then Google/MavenCentral.

## Architecture

### Module Layers (strict dependency direction)

```
:app
  └─→ feature:* (automation, backup, chat, coffee, companion, groupchat, mcp, memory, notification, profile, qqbot, settings, skills, wechat, worldbook)
        └─→ core:* (agent, common, database, domain, network, security, ui-common, wechat)

:shell  (JVM test module, isolated — not part of Android build)
```

**Critical rules:**
- `:app` is lightweight — only `YuNianApplication`, `MainActivity`, Compose navigation routes, and `ServiceRegistry` bindings. No business logic, ViewModels, or Repositories.
- Feature modules **must not** depend on other feature modules. Cross-feature communication uses `ServiceRegistry` via `core:domain` interfaces, or `core:database` for data.
- Core modules **must not** depend on feature modules.
- `core:domain` has zero dependencies — it defines only interfaces and data classes.
- `core:network` depends on `core:database` (reads API config).
- `core:ui-common` depends on `core:common`.
- `core:security` depends on `core:common`.

### Adding a New Module

1. Create module directory under `core/` or `feature/` with `build.gradle.kts`.
2. **Register it** in `settings.gradle.kts` (`include(":feature:newfeature")`).
3. **Add dependency** in `app/build.gradle.kts`.
4. **Add navigation route** in `app/src/main/java/com/yunian/ai/MainActivity.kt`.

### Navigation Routes

All routes are registered in `MainActivity.kt`. Current routes include:
- `home`, `contacts`, `profile`
- `chat/{companionId}` (Long)
- `group_chat/{groupId}` (Long)
- `create`, `edit/{companionId}` (Long), `create_group`
- `settings`, `memory`, `theme`, `language`, `check_update`, `frame_rate`, `about`, `agreement_view`

Pass arguments via navigation path parameters, not global state.

## Key Technical Details

### NDK / Security Module (`core:security`)

- Uses `ndkBuild` (not CMake). Build files: `core/security/src/main/cpp/Android.mk` and `Application.mk`.
- Implements six-dimension defense: CPU security (NEON key residency, 5-level key system), memory guard (mprotect, anti-dump), bridge layer (zero-trust PDP/PEP, VMP engine, 32-point detection), Kotlin orchestration (audit logging, hardware attestation).
- `ndkVersion = "30.0.14904198"` must match exactly between `app/build.gradle.kts` and `core/security/build.gradle.kts`.
- Anti-debug, integrity verification, and RegisterNatives obfuscation are **ONLY** active in release builds.

### Database (`core:database`)

- Room database, **current version 17**.
- Entities include: `Companion`, `ChatMessage`, `ChatGroup`, `GroupMessage`, `MemoryEntry`, `ApiConfig`, `KeywordEntity`, `TokenUsage`, `QuizQuestionEntity`.

### Cross-Feature Communication (`core:domain`)

- Shared interfaces (`UserProfileProvider`, `CompanionProvider`) — consumed by feature modules.
- `ServiceRegistry` in `app/YuNianApplication.kt` binds implementations, eliminating feature→feature dependencies.

### Network (`core:network`)

- `AiService.kt` — AI dialogue gateway supporting OpenAI, DeepSeek, Claude, Gemini, DashScope, local model.
- `RequestSecurityInterceptor` — TLS 1.2+1.3 pinning and request integrity.

### Cordis Agent (`core:agent`)

- `feature:localmodel` **has been retired** (local inference is now handled entirely by the Rust Cordis Agent runtime shipped as `liblianyu_agent.so`). There is no LiteRT-LM / Gemma on-device path anymore.
- `AgentDialogueCoordinator` (with `:core:agent`) is the sole owner of `ContentFilter` / `BanManager` / `ImageGenProtocol` for bridge paths.
- Memory reads/writes go through `core:domain`'s `MemoryProvider` (impl: `UnifiedMemoryProvider`).

### UI (`core:ui-common`)

- Theme is WeChat-style dark-first with liquid glass effects.
- `HardwareInfo.tier` (LOW / MEDIUM / HIGH / ULTRA) controls animation intensity. On `LOW`, disable heavy effects like liquid glass.
- Shared components: liquid glass, frosted glass nav, spring animations, shimmer skeletons, WeChat-style chat input bar.

### Background Services (`feature:notification`)

- `CompanionKeepAliveService` is a foreground service (`foregroundServiceType="dataSync"`).
- `BootReceiver` restores scheduled WorkManager tasks on reboot.
- WorkManager uses `ExistingPeriodicWorkPolicy.UPDATE` (not `REPLACE`, which is deprecated).
- All service/receiver declarations in `AndroidManifest.xml` must use **fully qualified class names**.

## Environment & Tooling

- **compileSdk = 35**, **minSdk = 26**, **targetSdk = 35**
- **Kotlin**: 2.2.10 (JVM toolchain 17)
- **JDK**: 17 (project points to `D:\\and studio\\jbr` on Windows)
- **AGP**: 9.2.1
- **Gradle**: 9.4.1
- Release signing config expects `release.keystore` in the project root and environment variables `YUNIAN_STORE_PASSWORD` / `YUNIAN_KEY_PASSWORD`.
- Version catalog is in `gradle/libs.versions.toml`.

### Environment Constraints (Do Not Modify)

The following environment configurations are **owned by the repository and must not be changed** by individual developers or agents:

| Configuration | File | Rule |
|---------------|------|------|
| JDK path | `gradle.properties` (`org.gradle.java.home`) | Fixed to the project-provided JDK (`D:\and studio\jbr` on Windows). Do not point to system JDK or other installations. |
| Gradle version | `gradle/wrapper/gradle-wrapper.properties` | Locked to the specified version. Do not upgrade or downgrade. |
| Dependency versions | `gradle/libs.versions.toml` | Centralized version catalog only. Hardcoding versions in module `build.gradle.kts` files is prohibited. |
| Maven mirrors | `settings.gradle.kts` | Use the configured Alibaba Cloud / Tencent mirrors. Do not change repositories. |

If a build fails due to environment issues, report it rather than modifying these files.

## Development Notes

- When modifying `core:*` modules, expect cascading rebuilds across all dependent feature modules. Use `./gradlew clean` for large changes.
- If the app crashes on startup with a linker error, verify NDK installation and that `core:security` compiled successfully.
- `ContentFilter` regex patterns in `core:common` are a security baseline — changes should be reviewed carefully.
