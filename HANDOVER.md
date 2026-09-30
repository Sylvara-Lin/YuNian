# HANDOVER.md — 予念 YuNian 交接文档

> 撰写日期：2026-09-30 ｜ 分支：`master` ｜ 基线：`391f287`
> 覆盖当日四个工作流：数据丢失修复、群聊闪退修复、华为闪退修复、崩溃日志上报功能

---

## 0. 一分钟速览

工作树包含 **19 个修改文件 + 12 个新增条目**，横跨 4 个独立工作流。**均尚未提交**（建议按第 6 节拆分提交）。

| # | 工作 | 严重度 | 状态 | 验证级别 |
|---|---|---|---|---|
| ① | 余额耗尽后聊天记录+人设全部丢失 | **P1 数据销毁** | ✅ 已修 | QA 独立复现 + 真机 sha256 证明 |
| ② | 群聊偶发闪退 | P1 | ✅ 已修 | QA 独立验证 |
| ③ | 华为「半血鸿蒙」安装后启动即闪退 | P1（仅华为） | ✅ 已修 | manifest 实测 + 编译验证（**真机待确认**） |
| ④ | 崩溃日志自动上报（新功能） | 功能 | ✅ 已实现 | QA 三轮验证，含**变异测试** |

---

## 1. ① 数据丢失（最严重的一个）

### 症状
API 余额耗尽 → 闪退 → 重新进入 App → **聊天记录和人设全部消失**。

### 根因（不在余额，在 app 自己的「恢复」逻辑）
`core/database/src/main/java/com/yunian/ai/database/AppDatabase.kt`（修复前）：

```kotlin
// ① backupBeforeRecovery：按 db → -wal → -shm → -journal 顺序复制，全部命名为 *.corrupted_<ts>
// ② recoverDatabase：按「名字含 corrupted + mtime 最新」挑一个，写回主库！
?.filter { it.name.endsWith(".db") || it.name.contains("corrupted") }
?.sortedByDescending { it.lastModified() }
...
latestBackup.copyTo(dbFile, overwrite = true)      // ← 用附属文件覆盖真库
```

**因为 `-wal/-shm/-journal` 的 mtime 天然比主库新，选中的必然是一个日志/共享内存文件** → 用日志文件覆盖真库 → **数据不可逆销毁**。若选中文件 ≤1024 字节则直接 `dbFile.delete()` 整库清空。

**触发条件**：`YuNianApplication.kt:258` 启动时调用 `verifyAndRecover()`，对 `verifyDatabaseCanOpen()`（`wal_checkpoint(TRUNCATE)` + `PRAGMA user_version`）抛出的**任何异常**都判为「库损坏」。而启动期并发极重（`registerServiceProviders` / `DataCleanupManager.cleanupIfNeeded` / `autoBackupDatabase` / `HomeListCache.warm` 并行），撞上 busy/locked 这类**瞬态错误**即被误判 → 破坏性恢复。

**「余额耗尽闪退」只是导火索** —— 数据丢失发生在**下一次冷启动**。

### 修复
- 新增 `core/database/.../DatabaseRecoveryPolicy.kt`：`classify()` 只把 `SCHEMA_MISMATCH` / `CORRUPTION` 视为可恢复；**`TRANSIENT` / `UNKNOWN` 一律不 close、不恢复、原样保留主库**
- 恢复源只从 `filesDir/db_backup/backup_*/` 的**整库快照**（角色=主库 + 尺寸>512B + SQLite 魔数三重校验）
- **删除**旧 `deleteDatabaseFiles` / `recoverDatabase` / `tryRecoverFromBackup` / `tryRepairWalFiles` / `backupBeforeRecovery`
- 附属文件改用 `moveAsideNonDestructive()`：**verify-then-delete** 原子改名（copy 未验证成功则保留原文件）
- 公共入口 `restoreFromBackup` 补齐三重校验 + `name == DB_NAME` 才准写主库

### 硬约束（改 DB 代码必须遵守）
> **数据不可丢**：主库删除路径必须为 **0**；主库覆盖只允许 2 条（自动恢复、手动恢复），且都经校验。

### 验证
- QA 独立复刻旧实现 → 选中 `yunian_database-journal.corrupted_*` 写回主库，sha256 `8311d126…` → `2c632c31…`（size 8192→4096）**复现破坏**
- 真机新实现：sha256 `f3cd1fc4…ef7c` 前后**逐字节相同**
- 双方各做一次回滚实验：改回旧逻辑 → 测试变红 → 还原 → 全绿

---

## 2. ② 群聊闪退（与 ① 同源）

### 根因
`GroupChatPager.start()` 的 `collectLatest` 与 `loadMoreMessages()` **无 try/catch**，跑在 `viewModelScope`（Main，无异常处理器）。DB 连接被 ① 的竞态关闭/重建时异常冒泡 → 主线程崩溃。

**QA 查明**：`ChatGenerationManager` 的 6 处 `scope.launch` **全部带 `+exceptionHandler`** → **单聊路径不是崩溃源**。真正无兜底的是 `ApplicationScopeProvider.scope`（无 `CoroutineExceptionHandler`）。

### 修复
- `PagerTaskGuard` 包住 `start()` / `loadMoreMessages()`（**`CancellationException` 照常重抛**）
- `GroupChatViewModel` 给 try 之外的 `checkInput` 加 `runCatching`

---

## 3. ③ 华为「半血鸿蒙」闪退

### 症状
华为设备（EMUI / HarmonyOS 4.x，仍兼容 APK）安装后**启动即闪退**；其他机型正常。

### 根因（半成品集成）
HMS Push 在本工程**不可能工作**，但代码仍去初始化它：
1. 仓库内**无 `agconnect-services.json`**（且 `.gitignore` 未排除 → 确实缺失）→ HMS 拿不到 app_id
2. `app/src/shell/AndroidManifest.xml` 已移除 HMS 核心 Provider（`HMSCoreProvider` / `AGConnectInitializeProvider` 等）→ 初始化链本就断裂
3. 但主 manifest 仍声明 `HuaweiPushService : HmsMessageService`，且 HMS SDK 自带 **`com.huawei.hms.aaid.InitProvider`（ContentProvider，在 `Application.onCreate()` 之前实例化）** 仍在 → 华为设备上系统会去拉起一个注定失败的 HMS 组件 → **开机即崩**
4. `PushManager` 用 `RomUtils.isHuawei` 门控 → **只在华为设备执行**，与「仅华为复现」完全吻合

### 修复（4 处，全部可逆）
| 文件 | 改动 |
|---|---|
| `app/src/main/AndroidManifest.xml` | 注释停用 `HuaweiPushService` 声明 |
| `.../push/PushManager.kt` | 移除华为分支初始化调用 → 记录 warning |
| `app/src/shell/AndroidManifest.xml` | 追加移除 8 个 HMS 组件（含 `aaid.InitProvider`、`HmsMsgService`、`PushMsgReceiver`、`BridgeActivity`、`ServiceDiscovery` 等） |
| `.../push/vendor/HuaweiPushInitializer.kt` | 异常兜底 `Exception` → `Throwable`（保留以便将来复用） |

### 验证
- `:app:assembleDebug` SUCCESSFUL
- **成品 APK 的 33 个组件标签中，含 huawei/agconnect 的 = 0**（仅剩 `<queries>` 与 `<meta-data>` 惰性条目）
- 剩余推送组件只有 Oppo / Vivo（有意保留）

### ⚠️ 未验证项
**没有华为真机**，机制属推断。若将来仍闪退，需抓 `adb logcat -b crash`。
另外：若为 **native 崩溃**，本次改动无法捕获 —— 但见第 4 节（新功能已覆盖 native 捕获）。

### 恢复华为推送需同时
补 `agconnect-services.json` → 还原主 manifest 的 Service 声明 → 还原 `PushManager` 华为分支 → 删除 shell manifest 的 remove 段

---

## 4. ④ 新功能：崩溃日志自动上报

### 需求
> 「如果用户闪退，以及部分安卓设备在偶然闪退后进入软件，**主动给出上次闪退的日志**」（用户不会抓 logcat）

### 为什么重要
项目原本 **release 包零可观测性** —— `SecureLog` 和 `ChatDebugLog` 在 release 全是 no-op。这个功能就是在补这块短板。

### 用户可见行为
```
闪退 → 自动落盘 → 重新打开 App → 主动弹出「上次运行发生了闪退」
                                   ├─ [复制日志]  一键复制，可直接发给开发者
                                   ├─ [关闭]
                                   └─ [清除记录]
```

### 架构（`core/common/src/main/java/com/yunian/ai/common/crash/`）
| 文件 | 职责 |
|---|---|
| `CrashBreadcrumbs` | 内存环形缓冲（150 条 / 单条 300 字符 / dump 24KB） |
| `CrashBreadcrumbPersister` | 退后台时择时落盘（5s 去抖，单线程 executor，snapshot+写盘**整体**下沉） |
| `CrashLogStore` | `filesDir/crash/` 原生文件 IO（原子写 tmp+rename，上限 128K 字符） |
| `CrashReporter` | Java/Kotlin 未捕获异常处理器（幂等 + **链式转发原 handler**） |
| `CrashRedactor` | 尽力而为脱敏（`sk-` 阈值 4、`AIza` 8、内容字段） |
| `CrashReportFormat` | 报告格式 |
| `ApplicationExitMonitor` | **`ApplicationExitInfo`（API 30+）** 读系统退出原因 + trace |
| `ApplicationExitPolicy` | reason 白名单（**引用框架常量，零手抄**） |
| `core/ui-common/.../CrashLogDialog.kt` | Compose 弹窗（**零 ViewModel/Repository/DB 依赖**） |
| `app/src/shell/java/.../ShellCrashHandler.java` | 纯 Java 壳层兜底（仅 thin-shell 路径） |

### ★ 两个关键设计
**① `SecureLog` 12 个方法改为「先写 breadcrumbs（不论 isDebug），再按 isDebug 输出 logcat」**
→ **762 个既有 `SecureLog` 调用点，零改动即变 release 可诊断**。光有堆栈往往定位不了问题，得有崩溃前的上下文。

**② `ApplicationExitInfo` 覆盖 native / ANR / 被杀**
`ActivityManager.getHistoricalProcessExitReasons()`（API 30+，minSdk 26 需守卫）给出系统判定的 reason：
`CRASH_NATIVE(5)` / `CRASH(4)` / `ANR(6)` / `LOW_MEMORY(3)` / `SIGNALED(2)` / `INITIALIZATION_FAILURE(7)`。
**`getTraceInputStream()` 实测可读 → 无需 root 即可拿到 native 崩溃的 tombstone（含寄存器转储 + backtrace）。**

### 白名单（排除正常退出，零误报）
```
提示：   SIGNALED(2) / LOW_MEMORY(3)(需 importance≤125) / CRASH(4) / CRASH_NATIVE(5) / ANR(6) / INIT_INIT_FAILURE(7)
不提示： UNKNOWN(0) / EXIT_SELF(1) / PERMISSION_CHANGE(8) / EXCESSIVE(9) / USER_REQUESTED(10) / USER_STOPPED(11)
        / DEPENDENCY_DIED(12) / OTHER(13) / FREEZER(14) / PACKAGE_STATE_CHANGE(15) / PACKAGE_UPDATED(16)
```

### 验证摘要
| 项 | 结论 |
|---|---|
| Java 崩溃 | 弹窗 + 复制（QA 用「粘到 Chrome 地址栏再 uiautomator 读回」取证，**跨 App 可读**） |
| **native 崩溃** | `reason=5` + **真实 SIGSEGV tombstone**（无需 root） |
| ANR | `reason=6` + ANR trace |
| 被杀 | `reason=2 (SIGNALED)` |
| **正常退出** | **不弹窗**（force-stop ×2 / `am kill` / 划掉，反复验证） |
| 权限变更 | 修 P1 后**不再误报** |
| DB 完全损坏时 | 弹窗**仍能显示**（与 DB 解耦） |
| 崩溃处理器自身 | 链式转发成立（进程真死，未被吞）；落盘路径不可写时**不崩溃循环/不卡死** |
| **测试真伪** | **变异测试**：删掉白名单里的 `INITIALIZATION_FAILURE` → 测试**变红** → 还原后全绿 → 证明单测真在守护行为 |

---

## 5. 怎么构建 / 打包

### 官方一键打包（推荐，含完整加固流水线）
```bash
python tools/build.py                    # debug（模拟器用）
YUNIAN_STORE_PASSWORD=… YUNIAN_KEY_PASSWORD=… python tools/build.py --release   # release（真机用）
```
产物：**项目根 `YuNian-v2.apk`**（release 另复制 `YuNian-release.apk` 到桌面）
⚠️ **`BUILD.md` 里写的 `app/build/outputs/apk/<variant>/YuNian-*.apk` 已过时**，与脚本实际行为不一致

### 裸 Gradle（快速迭代 / E2E，不含壳加固）
```bash
cd E:/YuNian && ANDROID_HOME="D:/Android/Sdk" ./gradlew :app:assembleDebug \
  -Dorg.gradle.java.home="D:\Android Studio\jbr" \
  -Dorg.gradle.java.installations.paths="D:\Android Studio\jbr,C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

### ★ 构建前置物（gitignored，但构建必需）
| 缺失物 | 症状 | 如何补 |
|---|---|---|
| `app/libs/sherpa-onnx-1.13.3.aar` | `:app` 无法构建 | 从 `core/network/libs/` 或 `feature/chat/libs/` 复制（`app/libs/.gitignore` 有意排除 `*.aar`，属项目约定） |
| `core/security/src/main/cpp/wb_tables.inc` | `buildNdkBuildDebug` fatal | `python tools/gen_wb_tables.py --key random -o core/security/src/main/cpp/wb_tables.inc`（走 `build.py` 会自动生成） |
| `release.keystore` | `validateSigningDebug` 失败 | **连 debug 包都需要**（`app/build.gradle.kts:79` 把 debug 绑定到了 release 签名配置）。口令在 `~/.gradle/gradle.properties` |

### ⚠️ JDK 路径坑
`gradle.properties` 的 `org.gradle.java.home` 指向**原作者机器路径** `C:\Users\27194\.jdks\corretto-17.0.14`（本机不存在）。
按 `AGENTS.md` **禁止修改该文件** → 用命令行 `-D` 覆盖，或用 `build.py` 内置的环境变量：
```bash
export YUNIAN_GRADLE_JAVA_HOME="D:/Android Studio/jbr"          # 跑 Gradle 的 JDK（21）
export YUNIAN_JDK_INSTALLATIONS="<jdk21>,<jdk17>"                # toolchain 列表（某模块需 21）
```
本机两个 SDK 都可用：`D:/Android/Sdk` 与 `C:/Users/linruoxi/Android/Sdk`

### 模拟器配方（已跑通）
```bash
cd D:/Android/Sdk/emulator && ./emulator.exe -avd Unpack_Device \
  -no-snapshot-load -no-audio -no-boot-anim -gpu swiftshader_indirect -skin 1080x2400
# ★ -skin 必带：AVD 内 skin.name=pixel_6a 会让 emulator 直接 FATAL
# adb 在 D:/Android/Sdk/platform-tools/adb.exe（不在 PATH）；boot ~50s
```
- AVD：`Unpack_Device`（android-34 / google_apis / x86_64 / 4GB，**可 root**）、`Pixel_10_Pro`（android-37 / playstore / 2GB）
- **系统镜像只有 x86_64**（x86 主机跑不了 arm64 镜像）
- `:app` 默认只打包 `arm64-v8a` → 模拟器需加夹具：`-PyunianEmulatorAbis=x86_64`（**不传属性时行为逐字节不变**；回滚=删掉该属性）

### 无 API Key 的 E2E
宿主起 OpenAI 兼容 mock，模拟器经 `10.0.2.2:<port>` 访问。
⚠️ **mock 必须 unsandboxed 启动**，否则沙箱网络隔离导致宿主机与 `10.0.2.2` 都不通。
协议真相：文本路径由 **Rust `AgentRuntime.runTurn` 直连**（`native_gateway.rs` 直读 SQLite `api_configs WHERE isEnabled=1`，`{baseUrl}/chat/completions`，`Bearer <key>`）；`shouldSignRequest` 只对 `*.lianyu.ai` 签名 → `10.0.2.2` 不需签名、TLS pinning 不触发。**不是** Kotlin OkHttp 路径。
主回合特征：`tools_count=10`（chat）/ `44`（groupchat）

---

## 6. 建议：把工作树拆成 4 个提交
当前 19 个修改 + 12 个新增混入同一工作树，建议按工作流拆分（提交前请自行 `git diff` 复核）：

1. **fix(db)**: `AppDatabase.kt` + `DatabaseRecoveryPolicy.kt` + `core/database` 下 5 个测试文件
2. **fix(groupchat)**: `GroupChatPager.kt` + `GroupChatViewModel.kt` + `PagerTaskGuardTest.kt`
3. **fix(push)**: 华为相关 4 文件（`PushManager` / `HuaweiPushInitializer` / 两个 manifest）+ `PushMessageDispatcher.kt`
4. **feat(crash)**: `core/common/.../crash/`（8 类）+ `SecureLog.kt` + `CrashLogDialog.kt` + `ShellCrashHandler.java` + `MainActivity` / `YuNianApplication` / `StaticApShell` 的接入 + `core/common/src/test/.../crash/`
   - 另含密钥清理：`AiService` / `SettingsViewModel` / `SttService` / `SiliconFlowStt` / `SiliconFlowTts` / `PushMessageDispatcher`

---

## 7. 已知问题 / 残余风险

### P3（可接受，仅记录）
1. `REASON_LOW_MEMORY` 在 `IMPORTANCE_VISIBLE(200)` 窗口理论漏报（窗口极窄；本 App 常见 125）
2. 崩溃游标用墙钟，时钟回拨时可能漏报一次
3. 游标文件损坏 → 最坏重弹一次
4. `:app` **6 个既存单测失败**（`OnePieceShell*` ×4、`ReleaseApkBlackboxAudit`、`ReleaseConfiguration`）→ **属「壳/发布架构已演进、测试没跟上」的陈旧测试**（断言 `YuNianShellApplication`、`CompositeVmpRuntime.execute`、`SHELL_PAYLOAD_ASSET` 等已不存在的旧架构符号；`OnePieceShellPlanTest` 还依赖 git 未跟踪的 `docs/security/one-piece-shell-hardening-plan.md`）→ **建议由 shell 维护者处理**
5. 纯 Java `ShellCrashHandler` 只单独 `javac` 过，**未跑通完整 thin-shell APK 端到端**（Gradle 路径不编译它）

### 结构性提醒
- `core/security/src/main/cpp/{g_ss_config.h, g_vmp_config.h, g_vmp_payload_data.cpp, g_vmp_shell_names.json, vm-engine.h}` 会在跑 `tools/build.py` 时**被重新随机生成**（反逆向设计，非 bug）。提交前若只想交业务改动，先还原这 5 个文件：
  ```bash
  git checkout -- core/security/src/main/cpp/g_ss_config.h core/security/src/main/cpp/g_vmp_config.h \
    core/security/src/main/cpp/g_vmp_payload_data.cpp core/security/src/main/cpp/g_vmp_shell_names.json \
    core/security/src/main/cpp/vm-engine.h
  ```

---

## 8. 环境速查

| 项 | 值 |
|---|---|
| 应用 | `applicationId=com.yunian.ai`、`versionCode=25`、`versionName=2.0.5`、label 予念 |
| SDK | compileSdk/targetSdk 35、minSdk 26 |
| Kotlin / AGP / Gradle | 2.2.10 / 9.2.1 / 9.4.1 |
| 模块 | 25 个（`:app` + 15 `feature:*` + 8 `core:*` + `:shell`） |
| Android SDK | `D:/Android/Sdk` 或 `C:/Users/linruoxi/Android/Sdk` |
| JDK | 21 = `D:\Android Studio\jbr`；17 = `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` |
| Room DB | version 19 |

### 关键链路（消息发送 → AI 生成）
```
ChatScreen → ChatIntent.SendText → ChatViewModel:161 → ChatGenerationManager.sendText()
 → 落库(enqueueChat) → messageQueue.trySend ← AI 点火
 → startMessageConsumer()（2500ms 合并窗口）→ startSendMessage()（四道门）
 → startAiResponse() → runTurnWithConfirmation → AgentFacade.runTurn（Rust Cordis Agent）
 → 事件流 bubble → AiResponseFinalizer.deliverResponse → 落库
```
`MessageWriteCoordinator` 是**全 App 唯一的消息写入闸门**（chat / group / wechat / qqbot / automation / imagegen 都过它）—— 改动它影响面覆盖全部上述路径。

### 崩溃机理速查（排障优先看）
| 作用域 | 未捕获异常后果 |
|---|---|
| `ChatGenerationManager` 的 6 处 `scope.launch`（`:109/259/387/439/453/918`，**全部带 `+exceptionHandler`**） | 只记日志，不崩 |
| `ApplicationScopeProvider.scope` / `bgScope`（`YuNianApplication.kt:179`，**无 handler**） | **进程闪退** ⚠️ |

> **`SupervisorJob` 不等于异常安全** —— 它只决定兄弟协程是否被取消，**不决定异常是否上报**。这是本项目最容易误判的点。

### 正式签名密钥
证书 SHA-256：`8D:53:5C:73:C9:15:44:AA:74:FA:7F:8C:E5:49:A5:78:52:CA:E2:2B:BE:CD:9F:12:9A:C9:49:05:75:4B:B6:2C`
别名 `your_alias`，口令在 `~/.gradle/gradle.properties`。机器上 4 份同指纹备份：`E:/签名/`（首选）、`E:/微信文件/`、`E:/master/LianYu/`、`D:/susu/`
⚠️ `E:/LianYu/release.keystore` 指纹 `7D:F2:88:26:…` **不是**当前正式密钥
