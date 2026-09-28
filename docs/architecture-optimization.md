# 予念架构优化方案与实施记录

> 日期：2026-09-08
> 范围：数据库 schema 永久冻结 + 液态玻璃按钮解耦 + 微信/QQ 桥接遗留修复

---

## 一、数据库结构永久冻结

### 1.1 目标

- 自 v41 起，无论新增多少功能、修复多少问题，**不再改动已有的表、字段和索引**。
- 用户覆盖升级、重新部署、重新安装后，原有数据完整保留、正常读写。

### 1.2 方案

引入**通用键值扩展表** `app_meta` 作为冻结基线（这是最后一次 schema 变更），配合已有的 `ExtJson` JSON 扩展字段，让业务功能与 schema 彻底分离。

### 1.3 文件清单与改法

| 文件 | 改法 |
|------|------|
| `core/database/.../model/AppMetaEntity.kt` | **新增**。`@Entity(tableName="app_meta")`，字段 `key`(主键 TEXT) + `value`(JSON TEXT) + `updatedAt` |
| `core/database/.../dao/AppMetaDao.kt` | **新增**。`get(key)` / `put(entity)`(INSERT OR REPLACE) / `remove(key)` / `getAll()` |
| `core/database/.../repository/AppMetaStore.kt` | **新增**。泛型 `get<T>(key, serializer)` / `put<T>` / `getOrPut` / `getString` / `putString` / `remove` / `contains`，kotlinx.serialization 序列化 |
| `core/database/.../AppDatabase.kt` | ①`entities` 数组加 `AppMetaEntity::class`；②`version 40 → 41`；③加 `abstract fun appMetaDao()`；④新增 `MIGRATION_40_41`（`CREATE TABLE IF NOT EXISTS app_meta`，纯新增无破坏）；⑤`MIGRATIONS` 数组末尾加 `MIGRATION_40_41`；⑥加 `SCHEMA_FROZEN_VERSION = 41` 常量声明冻结基线 |
| `app/.../YuNianApplication.kt` | `ServiceRegistry.registerSingleton(AppMetaStore::class.java) { AppMetaStore(AppDatabase.getDatabase(app).appMetaDao()) }` |
| `docs/database-schema-freeze.md` | **新增**。冻结规范全文（扩展机制用法、幂等约定、会改库代码清单、向后兼容迁移流程、三种验证方法） |

### 1.4 关键结论

- 迁移链 **1→41 完整**，`exportSchema = true` 不变。
- 建表/索引均为 `IF NOT EXISTS`，种子均为 `INSERT OR IGNORE`（`seedApiProviderPresets`、`DefaultCompanionSeeder` 已幂等）。
- 无 `fallbackToDestructiveMigration`；已有的 `_new` 表重建迁移是**向后兼容的拷贝式重建**，非破坏式，保留。
- 新增功能落点优先级：`AppMetaStore`(KV) → `ExtJson`(JSON 字段) → 冻结评审迁移（评审通过才允许，且必须拷贝式兼容）。

---

## 二、液态玻璃按钮解耦（对齐官方 Kyant0/AndroidLiquidGlass）

### 2.1 根因

`GlassButton` 原本**根本不是液态玻璃**——只做了 `Modifier.background(color, ContinuousCapsule)` 半透明色块，没有 `drawBackdrop`。真正的液态玻璃效果（`vibrancy`/`blur`/`lens`）只实现在 `LiquidBottomTabs`（底部导航栏）里，所以"液态玻璃按钮只能存在于底部导航栏"。

### 2.2 改法

| 文件 | 改法 |
|------|------|
| `core/ui-common/.../glass/GlassButton.kt` | **重写**。对齐官方 `LiquidButton`：`drawBackdrop(backdrop, shape=ContinuousCapsule, effects={vibrancy+blur(2dp)+lens(12dp,24dp)})` + `InteractiveHighlight` 按压形变（pressProgress 弹性缩放 + offset 的 tanh 位移）；`backdrop==null` 时降级为原色块。**保留原函数签名**（`backdrop` 默认 `LocalPageBackdrop.current`、`enabled`、`height`、`horizontalPadding`、`onLongClick`），不破坏 `ContactsScreen`/`HomeScreen` 调用 |

### 2.3 为什么现在任意页面都能用

`MainScreen.kt` 已用 `ProvidePageBackdrop(mainBackdrop)` 包住整个 `MainNavHost`（含 Home/Contacts/聊天页等所有导航页），所以任何页面里的 `GlassButton` 都能通过 `LocalPageBackdrop.current` 拿到 backdrop，真正显示液态玻璃，不再局限于导航栏。

### 2.4 官方参考

- 本地：`H:\挑战赛项目软件\AndroidLiquidGlass`（github.com/Kyant0/AndroidLiquidGlass）
- 对齐对象：`app/src/commonMain/.../components/LiquidButton.kt`（按钮）、`LiquidBottomTabs.kt`（导航栏）。

---

## 三、微信/QQ 桥接遗留三件修复

| 编号 | 问题 | 文件 | 改法 |
|------|------|------|------|
| #1 | Android 12+ 后台起 FGS 被拒，静默失败 | `feature/qqbot/.../service/QQBotForegroundService.kt` | `start()` 改返回 `Boolean`，单独 catch `IllegalStateException`（后台限制），被拒时 `scheduleBackgroundRetry()` 用 WorkManager 一次性任务延迟 60s 重试 |
| #1 | 同上（重试任务） | `feature/qqbot/.../service/QQBotRestartWorker.kt` | **新增**。`Worker`，`doWork()` 里重调 `start()` 一次即返回 |
| #2 | `core:wechat` 9 个失败单测 | `core/wechat/.../map/WeChatOutboundSegmenter.kt` | 恢复 `splitTextSimple()` 分句实现（空行分段 → 句末符分句 → 软上限 3 段），对齐测试保护的"分句成多气泡"行为 |
| #3 | 未登录消息退避重试 5 次（最长卡 5 分钟） | `core/wechat/.../outbox/WeChatOutboxCoordinator.kt` | `updateFailure()` 加 `isNonRetryable()`，`NON_RETRYABLE_MARKERS=["未登录"]`，命中即 `OUTBOX_DEAD` 判死，不再重试 |

---

## 四、验证方法

### 4.1 数据库升级不丢数据

见 `docs/database-schema-freeze.md` 第六节：自动化迁移测试（`MigrationTestHelper` 走 40→41 + 断言旧数据）、真机覆盖安装、冷启动幂等性。

### 4.2 液态玻璃

1. 编译 `:core:ui-common` 通过。
2. 真机：打开首页/联系人页，观察 `GlassButton` 有模糊/透镜效果（不再是纯色块），按压有弹性形变。

### 4.3 微信/QQ 三件

1. `./gradlew :core:wechat:testDebugUnitTest` → 9 个失败转为全绿。
2. `./gradlew :feature:qqbot:compileDebugKotlin :feature:wechat:compileDebugKotlin` 通过。
3. 真机：未登录微信时发消息，立即判死不再长时间转圈；QQ 后台被系统拒绝起服务后，60s 内 WorkManager 自动重试一次。

---

## 五、编译验证（已通过）

```
./gradlew :core:database:compileDebugKotlin :core:ui-common:compileDebugKotlin \
          :core:wechat:compileDebugKotlin :core:wechat:testDebugUnitTest \
          :feature:qqbot:compileDebugKotlin :feature:wechat:compileDebugKotlin
→ BUILD SUCCESSFUL in 4m 10s（89 tasks: 14 executed, 75 up-to-date）
```

- 数据库、液态玻璃、微信/QQ 三件改动的模块全部零编译错误（仅历史遗留的 deprecation warning）。
- `core:wechat` 单测 **34 个用例 0 失败**（`WeChatOutboundSegmenterTest` 10 项、`WeChatOutboxCoordinatorTest` 6 项等全部转绿），此前 9 个失败已修复。

### 构建加速说明

- 根因定位：①`settings.gradle.kts` 的仓库顺序是 `google()`/`mavenCentral()` 在前、阿里云镜像在后，导致先请求被墙的官方源、超时才回退——已纠正为阿里云镜像优先；②H 盘工作区缓存的 Gradle daemon 启动卡死——改回 C 盘默认缓存。
- 后续构建建议直接 `./gradlew <task>`（C 盘默认缓存，无需重定向 `GRADLE_USER_HOME`）。
