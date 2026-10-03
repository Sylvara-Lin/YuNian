# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

YuNian (予念) is an Android AI companion app built with Kotlin and Jetpack Compose. It uses a **feature-based modular architecture** with 24 Gradle modules: 1 `:app` entry, 14 `feature:*` modules, 8 `core:*` modules, and 1 `:shell` JVM test module.

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
  └─→ feature:* (automation, backup, chat, companion, groupchat, mcp, memory, notification, profile, qqbot, settings, skills, wechat, worldbook)
        └─→ core:* (agent, common, database, domain, network, security, ui-common, wechat)

:shell  (JVM test module, isolated — not part of Android build)
```

**Critical rules:**
- `:app` is lightweight — only `YuNianApplication`, `MainActivity`, Compose navigation routes, and `ServiceRegistry` bindings. No business logic, ViewModels, or Repositories.
- Feature modules **must not** depend on other feature modules. Cross-feature communication uses `ServiceRegistry` via `core:domain` interfaces, or `core:database` for data.
- Core modules **must not** depend on feature modules.
- `core:domain` has zero project-module dependencies. It defines shared contracts and data types; external coroutine and serialization libraries are declared in its build script.
- `core:network` depends on `core:database` (reads API config).
- `core:ui-common` depends on `core:common`.
- `core:security` depends on `core:common`.

### Adding a New Module

1. Create module directory under `core/` or `feature/` with `build.gradle.kts`.
2. **Register it** in `settings.gradle.kts` (`include(":feature:newfeature")`).
3. **Add dependency** in `app/build.gradle.kts`.
4. **Add navigation route** in `app/src/main/java/com/yunian/ai/MainRoute.kt` and register it in `app/src/main/java/com/yunian/ai/MainNavGraph.kt`.

### Navigation Routes

All routes are registered in `MainNavGraph.kt` (`MainNavHost`, invoked from `MainScreen.kt`) — **that file together with `MainRoute.kt` is the source of truth**, so the full list is not duplicated here. Representative routes:
- `home`, `contacts`, `profile`
- `chat/{companionId}` (Long), `chat_detail/{companionId}` (Long), `voice_call/{companionId}` (Long)
- `group_chat/{groupId}` (Long), `create`, `edit/{companionId}` (Long), `create_group`
- `settings`, `general_settings`, `settings_tools`, `settings_permissions`, `plugin_settings`, `memory`, `theme`, `language`, `frame_rate`, `check_update`, `about`, `agreement_view`, `data_backup`
- `tts_settings`, `token_usage`, `role_manager`, `background_settings`, `profile_settings`, `yandere_mode`, `worldbook`, `skills`, `mcp_settings`, `automation`

Pass arguments via navigation path parameters, not global state.

## Key Technical Details

### NDK / Security Module (`core:security`)

- Uses `ndkBuild` (not CMake). Build files: `core/security/src/main/cpp/Android.mk` and `Application.mk`.
- Implements six-dimension defense: CPU security (NEON key residency, 5-level key system), memory guard (mprotect, anti-dump), bridge layer (zero-trust PDP/PEP, VMP engine, 32-point detection), Kotlin orchestration (audit logging, hardware attestation).
- `ndkVersion = "30.0.14904198"` must match exactly between `app/build.gradle.kts` and `core/security/build.gradle.kts`.
- Anti-debug, integrity verification, and RegisterNatives obfuscation are **ONLY** active in release builds.

### Database (`core:database`)

- Room database, **current version 45**.
- Entities are declared in the `@Database(entities = [...])` block of `AppDatabase.kt` (32 today) — **that block is the source of truth for both the version and the entity list**, so entities are not enumerated here. Representative entities: `CompanionEntity`, `ApiConfig`, `MemoryEntry`, `Message` / `MessageBody`, `AgentSkillEntity`, `WorldbookEntity`.

### Cross-Feature Communication (`core:domain`)

- Shared interfaces (`UserProfileProvider`, `CompanionProvider`) — consumed by feature modules.
- `ServiceRegistry` in `app/YuNianApplication.kt` binds implementations, eliminating feature→feature dependencies.

### Network (`core:network`)

- `AiService.kt` — AI dialogue gateway. Providers are defined by the `ApiProvider` enum in `core:database`'s `ApiConfig.kt` (**source of truth**, 14 entries, each with a `displayName`); representative: OpenAI, Claude (`ANTHROPIC`), Gemini, DeepSeek, 通义千问 (`DASHSCOPE`), Kimi, 讯飞星火 (`IFLYTEK`). There is no `Codex` provider and no local-model provider (`feature:localmodel` is retired).
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

- `CompanionKeepAliveService` is a foreground service with `foregroundServiceType="specialUse"` (plus a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property). All three app-side FGS are `specialUse` (`SService`, `QQBotForegroundService`, `CompanionKeepAliveService`); `dataSync` appears **only in module manifests** (`feature:wechat`, `feature:qqbot`). Do not copy a module's type onto an app-side service — targetSdk 35 enforces FGS type requirements.
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

## 新功能开发规范

### 1. 冲突预审（必做）

添加任何新功能前，必须先审核与原有功能是否会产生冲突。审查清单：

| 审查维度 | 检查项 |
|----------|--------|
| 保活链路 | 是否影响 FGS / Worker / 进程内常驻的存活时序？Doze 冻结下是否可恢复？WakeLock 续租是否正常？ |
| 心跳机制 | 是否在会话建立后立即稳定发送心跳？ack 超时是否检测？重连封顶/退避是否正常？ |
| 消息管线 | 是否改变发送→typing→AI 生成时序？typing 显示前是否插入同步阻塞？ |
| 网络层 | 是否与 ilink SDK / QQ Gateway / OkHttp 超时冲突？连接互斥锁是否正常？ |
| 安全模块 | 是否与 ContentFilter / pipeline / Bayesian 分类冲突？加密路径是否正常？ |
| 数据库 | 是否新增 Entity / Migration？是否修改 DAO 签名？ |

清单未通过 → 禁止提交。

### 2. 架构最小变更原则

- 不产生冲突的情况下，**禁止改动原有项目架构与规范**。
- 新增独立类优先于扩展已有耦合类。
- 涉及保活/心跳/消息时序的功能，PR 描述必须标注 **"已验证不影响熄屏保活 / typing 时序 / 重连循环"**。

### 3. 已修复回归记录（2026-08-07）

| 问题 | 根因 | 教训 |
|------|------|------|
| QQ 机器人一直重连 | `startHeartbeat` 的 `while(isConnected.get())` 在 Hello 阶段即退出，心跳永不发送 | 心跳标志与 `isConnected` 解耦，Hello 后立即发 |
| 微信熄屏 2 分钟掉线 | e7165c2 把保活改成"FGS+Worker 兜底"，Worker 在 Doze 下不可靠 | FGS 被杀后立即自重启（现状：FGS 立即自重启 + Worker 仅作兜底，不可依赖） |
| typing 延迟十几秒 | typing 被 2.5s 合并窗口 + pipeline 阻塞 | typing 乐观显示，入队即置 |
