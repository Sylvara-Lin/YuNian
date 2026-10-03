# 予念源码审查与修复 — 概览

## 完成了什么

对「予念」安卓 App 全仓（app + 16 个模块 + shell）做了深度审查，优先定位并修复了导致设备闪退与启动异常的根因，并清理了全仓 AI 风格注释。

## 关键改动

1. **修复启动期原生崩溃（P0）**：`StaticApkShell.kt` 原先在 `liblianyu_shell.so` 加载失败后仍无条件调用 native 方法，抛 `UnsatisfiedLinkError` 直接闪退；现用 `runCatching` 兜底，与 Java 薄壳行为对齐。
2. **修复初始化卡死（P0）**：`YuNianApplication` 的 `markInitialized()` 原先可能因注册异常永不执行，导致主界面永久转圈；现用 `finally` 保证其必然置位。
3. **全仓注释清理**：`tools/strip_comments.py` 剥离 658 个文件、约 406KB 注释，字符串/正则/URL 无损坏。
4. **修复病娇模式不生效**：补 `<queries>` 解决 Android 11+ 包可见性（`installedApps` 恒空的根因）；`YandereModeManager` 改为数据感知触发、空缓存自动刷新、无 usage-stats 权限时回退输出已安装应用列表。

## 构建环境（已核实齐备，此前"缺 JDK"为误判）

| 项 | 实际位置 | 状态 |
|----|----------|------|
| Gradle JDK | `D:\Android Studio\jbr`（JBR 21.0.10） | 与 `gradle.properties` 一致，存在 |
| 系统 JDK | `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` | 在 PATH |
| Android SDK | `D:\Android\Sdk` | 齐备 |
| NDK | `30.0.14904198` | 与项目锁定版本一致 |
| build-tools / platforms | 34~37 / android-34~37 | 齐备 |

**已知构建坑**：`./gradlew assembleDebug` 在 `C:\Users\linruoxi\.gradle` 上随机抛 `FileNotFoundException ... (拒绝访问)`，命中 `.tmp/gradle*.bin` 与 `transforms/.internal/locks/*.lock`。非代码问题（目录可写、无进程占用）。基线跑通 170 task 中的 128 个，说明源码可编译。缓解：`--stop` + 清理 `.gradle/.tmp` 与 transforms 锁 + `--no-daemon`；若仍不稳，将 `GRADLE_USER_HOME` 重定向到工作区。

## 架构优化（数据库冻结 + 液态玻璃解耦 + 桥接遗留）

- **数据库冻结基线 v41**：新增 `app_meta` 通用 KV 表（`AppMetaEntity`/`AppMetaDao`/`AppMetaStore`）+ `MIGRATION_40_41`（纯新增无破坏）。新增功能走 KV/ExtJson，禁止加表/字段/索引。规范见 `docs/database-schema-freeze.md`。
- **液态玻璃解耦**：`GlassButton` 原只是半透明色块（非真玻璃），已重写对齐官方 Kyant0/AndroidLiquidGlass 的 `LiquidButton`（`drawBackdrop` + vibrancy/blur/lens + 按压形变），任意页面可用。
- **桥接遗留三件**：①后台起 FGS 被拒→`QQBotRestartWorker` 延迟重试；②`splitTextSimple` 恢复分句（9 个失败单测转绿）；③未登录消息立即判死不重试。
- **编译验证通过**：`BUILD SUCCESSFUL`（数据库/UI/桥接改动零错误，core:wechat 34 测试 0 失败）。
- 详见 `docs/architecture-optimization.md`。

## 遗留待决策

- P1-1：ABI 仅 arm64-v8a，与多设备适配冲突，需决策是否补 32 位 `.so`。
- P1-2/P1-3：`error()` 异常式控制流、重复 `WorkManager.initialize`。
- P2：壳层双实现分叉、废弃类、根目录杂物等可维护性问题。

详见 `缺陷审查与修复报告.md`。

---

# 微信/QQ 连接故障 — 开源方案调研概览（2026-09-09）

## 完成了什么

针对两个故障（QQ 连接一直卡"连接中"、微信发送消息无反应），先定位项目内实现，再调研 GitHub 微信/QQ 对接生态，产出带推荐排序的对比报告。

## 关键结论

1. **微信侧**：项目在用 `lith0924/wechat-ilink-sdk-java:2.3.3`。其 open Issue #15（2026-08-30）与"发送后无反应"症状完全一致（sendmessage ret=0 但对方收不到）。iLink 协议硬限制：只能回复、context_token 24h 过期——`WeChatOutboxCoordinator.resolveContextToken()` 返回空 token 时即静默不投递。
2. **QQ 侧**：`QQBotWebSocketClient` 为自研 OkHttp WS 客户端。根因候选（按概率）：① 开放平台新增机器人默认启用 IP 白名单，Android 动态 IP 导致 getGateway/token REST 失败 → 无限重连；② 后台切 Webhook 后 WS 链路被停用（WS 连上但永无 HELLO）；③ `isConnecting` 竞态 + intents 越权。
3. **推荐排序**：① simple-robot/simbot-component-qq-guild（Kotlin 官方 API，2026-09-06 仍在更新，Gateway 标准实现可对照修复）② 继续用 lith0924 SDK 并跟进 #15 ③ QQ 平台配置自查（实际优先级最高、零成本）④ zimoyin/qqbot-sdk ⑤ NebulaMao TS SDK 作协议参考 ⑥ pig-mesh JiLink 交叉验证 ⑦ OneBot 生态（NapCatQQ 等）仅备选（封号风险、无法嵌入 App）。

## 产出

- `微信QQ连接故障-开源方案调研报告.md`（含候选对比表与分阶段行动清单）

---

# OpenMinis 移植 — 技能自觉使用 + 设备接管工具（2026-09-09）

## 做了什么
参考 github.com/OpenMinis/OpenMinis（已克隆至 `.workbuddy/tmp/OpenMinis`），移植其技能触发模式并新建设备工具集，全部只用予念命名。

## 关键改动
1. **技能索引常驻注入**（feature/skills/tools/SkillTools.kt）：新增 `SkillIndexState` + `refreshSkillIndex()`，把 assets 技能清单以 `<available_skills>` XML（名称+描述，上限 20）注入 `use_skill` 工具的系统提示词——模型从"不知道有技能"变为"看到清单、匹配即主动加载"。这是"AI 不会自觉使用技能"的根因修复。
2. **设备接管工具第一批**（新建 feature/skills/tools/DeviceTools.kt，8 个）：`device_open_app` / `device_open_url` / `device_get_clipboard` / `device_set_clipboard` / `device_set_alarm`（预填由用户确认）/ `device_notify` / `device_battery_status` / `device_get_time`，注册进 ToolRegistry，随对话工具循环生效。
3. **组装**（YuNianApplication + AndroidManifest）：注册设备工具、启动时后台刷新技能索引、补 `SET_ALARM` 权限；feature:skills 补 core-ktx 依赖。
4. 顺带修复两个挡路既有问题：MainScreen.kt `backgroundViewModel` 声明顺序；AppDatabase 虚版本 42 回退 41（无 schema 变化，符合"冻结基线 v41"决策）。

## 验证
`:feature:skills:compileDebugKotlin :app:compileDebugKotlin` → BUILD SUCCESSFUL。

## QQ 路线结论（同步）
OpenClaw / Hermes Agent 的 QQ 接入均为官方 QQ Bot API v2（AppID/Secret + WebSocket Gateway），"扫码"只是凭证配置向导；予念自研客户端已是同源协议，连不上属平台配置问题（白名单/回调方式），可借鉴 OpenClaw 插件的工程实践（事件持久化、串行回复、限速、QQ 内置 ASR 语音转写、requireMention 配置）。

---

# 微信官方协议直连 + OpenMinis 移植一期（2026-09-09 下午）

## 微信侧（对标腾讯官方 OpenClaw 微信插件 2.4.8）
1. **IlinkDirectSender**（新建 core/wechat/ilink/）：文本发送直连官方 API，完整对齐 2.4.8 协议（iLink-App-Id / iLink-App-ClientVersion / X-WECHAT-UIN / bot_agent / message_type=2 / message_state=2），`ret!=0` 直接抛错——发送失败从"静默无反应"变为"可感知、可重试"。SDK 仅保留登录与收消息。
2. **channel_version 动态化**：启动时从 npm registry 拉官方插件最新版本号，官方更新予念自动跟随；离线回退 2.4.8 兜底值。
3. **设置页日志清理**：微信设置页"通道状态"卡片移除"最近错误原文 / 近期 Outbox 失败列表"开发遗留日志，保留健康概览。
4. 验证：core:wechat 40 单测全过，编译全绿。

## OpenMinis 移植第一期（全部完成，BUILD SUCCESSFUL）
1. **技能索引注入**：可用技能清单（名称+描述，上限 20）常驻系统提示词——AI 从"不知道有技能"变为"匹配即主动 use_skill"。
2. **技能自主上网安装**：SkillManager 支持外部技能目录（filesDir/external_skills），新增 `skill_install`（URL 下载 SKILL.md）/`skill_uninstall` 工具，与 web_search 形成"搜索→安装→加载执行"闭环。
3. **AI 控制手机**：YuNianAccessibilityService（读屏/手势/全局导航）+ 7 个工具（accessibility_status / screen_read / press_back / go_home 低风险直执行；screen_tap / screen_swipe / screen_click_text 需用户确认）；manifest 声明服务与描述文案。
   - **（2026-10-03 变更）** 本组工具已改由 Cordis 插件 `ui.assists`（无障碍自动化，默认蓝图装载）提供，并由 7 个扩为 **9 个**（新增 `screen_input_text` / `screen_dump_ui`，均 `appLocalOnly = true`）；服务基类改为 `com.ven.assists.service.AssistsService`，**manifest 与 `accessibility_service_config.xml` 均未改**，无障碍服务仍恰好 1 个、用户既有授权不失效。详见 [accessibility-plugin.md](accessibility-plugin.md)。
4. **Shizuku 通道**：shizuku_status 工具（安装/运行/授权三态检测 + 引导），provider 与依赖就位；特权动作（静默安装、force-stop）二期。
5. **设备工具第一批**（8 个）：打开应用/网页、读/写剪贴板、预填闹钟、发通知、电量、日期时间。

## Linux 沙盒（二期待决策）
手机跑 Alpine（OpenMinis 模式）需要 rootfs assets（APK 体积 +80~150MB）、proot/JNI 集成与终端会话管理，工程量周级；需先确认打包体积策略再启动。

---

# AI 能力中心 — 功能入口页（2026-09-09 傍晚）

## 做了什么
把今天所有 AI 能力（无障碍控制、Shizuku、技能安装、设备工具）做成用户可见可操作的功能入口。

## 关键改动
1. **新建 `feature/skills/ui/SkillsCenterScreen.kt`（AI 能力中心）**，玻璃风格与全站一致：
   - 控制通道区：无障碍卡（状态点 + "去开启"一键跳系统无障碍设置，从设置返回自动刷新）、Shizuku 卡（安装/运行/授权三态 + 引导文案）
   - 技能库区：列出全部技能，外部技能带"AI 安装"标签和删除按钮；空态提示 AI 可自主安装
   - 设备能力区：设备工具集说明卡
2. **支撑**：SkillManager 接口加 `isExternalSkill(name)`；Shizuku 状态检测抽为可复用 `checkShizukuStatus(context)`（AI 工具与页面共用）。
3. **接线**：`MainRoute.Skills` 路由切到能力中心页（原入口"实验功能 → 技能库"链路不变），入口文案更新为"AI 能力中心"。
4. 顺带解锁另一会话阻塞编译的两处半成品：CompanionEntity 漏 `ColumnInfo` import（KSP MissingType）、CreateCompanionScreen 重复 `Checkbox` import。

## 验证
`:feature:skills` + `:feature:settings` + `:app` 编译全链路 BUILD SUCCESSFUL。入口路径：设置 → 实验功能 → AI 能力中心。

---

# AI 主动性修复 + 工具调用过程气泡（2026-09-09 晚）

## 做了什么
针对反馈"AI 不主动调工具、让她调用也会拒绝、没有 OpenMinis 那种调用动画"做了根因修复。

## 根因与修复
1. **关键词闸门（主根因）**：原逻辑只有消息命中 70 个关键词之一才进入工具循环，其余消息根本不带工具 → 改为 OpenMinis 模式：**每轮对话都带工具，由模型自主决定**调用与否。
2. **提示词无行动指令**：主对话系统提示词甚至没拼接工具段落 → `ToolRegistry.agentDirectiveSection()` 注入"执行能力"准则（沾边就调、禁止反问/拒绝、先行动后汇报、敏感操作有确认门），主路径与稳定路径都生效。
3. **工具调用过程气泡（OpenMinis 风格）**：新链路 AiToolLoopRunner（工具活动事件：RUNNING → DONE/FAILED）→ ChatGenerationManager StateFlow → ChatScreen 输入栏上方玻璃动画卡（spinner/✓/✕ + 中文工具名 + 参数摘要 + 呼吸脉冲动画，AI 正文开始输出后自动收起），20+ 工具做了友好名映射。

## 验证
core:domain / core:network / feature:chat 编译全过。

---

# 气泡常驻 + 居中修复 + AI 联网能力（2026-09-09 深夜，用户截图反馈）

1. **工具气泡常驻**：不再随 AI 正文输出收起，过程卡常驻展示一整轮，下一轮发送消息时才清空。
2. **状态图标居中修复**：✓/✕ 从文本符号改为 Icon（AppIcons.Check / X），消除文本基线导致的偏心。
3. **AI 联网修复（截图里"一搜就报错"的根因）**：search_web 原来必须配置 Brave API key，未配置直接报错 → 新增**免 key 必应中国兜底**（网页解析，国内直连），未配置 key 也开箱即搜。
4. **新增 `web_fetch` 工具**：抓取任意网页/GET 接口转文本（等效 OpenMinis 沙盒里的 curl，但无需虚拟 Linux）。行动指令写入技能安装标准路径：web_fetch 搜 GitHub 仓库 → 读 raw SKILL.md → skill_install 全程自主，不再向用户索要链接。

验证：core:domain + feature:chat 编译全过。

---

# 瑞幸咖啡功能下线（2026-10-03）

## 做了什么
按用户裁定**整体下线**「瑞幸咖啡」功能：模块、Cordis 代码插件、跨模块契约、导航路由与 UI 入口、蓝图条目、定位权限与测试夹具引用一并清除，并在启动时一次性清理历史版本残留的凭据文件。全程未动仓库环境配置（`gradle.properties` / `gradle/wrapper/*` / `gradle/libs.versions.toml` / `settings.gradle.kts` 的仓库镜像），未新增任何 Gradle 依赖，未改变模块依赖方向。

## 删了什么
1. **模块**：整目录 `feature/coffee/`（13 个 Kotlin 文件 + `res/values/strings.xml` + `AndroidManifest.xml` + `build.gradle.kts`，共 16 个文件），并从 `settings.gradle.kts` 删 `include(":feature:coffee")`、从 `app/build.gradle.kts` 删 `implementation(project(":feature:coffee"))` 各一行。
2. **Cordis 代码插件**：`coffee.luckin`（6 个 `luckin_*` 工具）从 `app/src/main/assets/blueprints/default.json` 移除（JSON 仍合法，其余 7 项顺序不变）；`YuNianApplication` 里 `pluginHost.register(CoffeePlugin(...))` 与其 `ServiceRegistry.registerSingleton(CoffeeOrderProvider::class.java)` 绑定、`import CoffeeOrderProvider` 一并删除，首批代码插件清单注释同步订正。
3. **跨模块契约**：`core/domain/.../CoffeeOrderProvider.kt` 删除。删前全仓复核：唯一消费者是 `YuNianApplication`。
4. **路由与 UI 入口**：`MainRoute` 的 `Coffee` / `CoffeeProduct` / `CoffeeSettings` / `CoffeeToken` / `CoffeeOrderQuery` / `CoffeeOrderQueryWithId` 六个路由对象及 `fromRoute` 的 6 个分支；`MainNavGraph` 的 5 条 import、`onCoffeeClick` 实参与 6 个 `composable` 块；`feature/profile` 的 `ToolsSettingsScreen.onCoffeeClick` 形参（含 `GeneralSettingsScreen` 默认值）、「瑞幸咖啡」菜单项与 `coffee_title` / `coffee_desc` 两条字符串。工具分类入口图标 `AppIcons.Coffee` **保留**（共享图标名，仍是「工具设置」分类的图标）。
5. **权限**：`app/src/main/AndroidManifest.xml` 的 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` 与配套注释删除。删前复核：全仓源码（排除 `build/` 与嵌套参考检出 `_ref_master/`）搜 `ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|LocationManager|getLastKnownLocation`，命中仅 `feature/coffee/.../CoffeeScreen.kt`（已删）与 app manifest 自身（唯一声明点），复核通过。
6. **测试夹具**：6 个测试文件把陈旧插件 id `coffee.luckin` 换成仍存在的真实 id `automation.core`（排序 / 大小写断言同步改成新 id 的正确期望值，未放宽、未删除任何断言）；`CapabilityGrantTestFixtures` 的生产确认工具镜像删掉 `luckin_create_order` 一条，7 → 6，类级 KDoc 与辅助函数 KDoc 的「7 个」表述同步订正。

## 残留凭据清理
历史版本的瑞幸 MCP Token 存在 DataStore 文件 `filesDir/datastore/luckin_coffee_prefs.preferences_pb`。功能下线后该文件不再有任何读取方，但 Token 属明文凭据，故在启动时一次性清除（`YuNianApplication` 内，紧邻 `McpToolRegistrar.syncTools()` 那段）：
- **幂等**：`takeIf { it.exists() }?.delete()`，文件不存在时静默返回，不抛、不报错；
- **best-effort**：整体 `runCatching`，失败只走 `SecureLog.e`，绝不阻断启动；
- **不占主线程**：放在既有的 `bgScope.launch { }` 后台协程里；且**独立 launch** 而非并入 `syncTools` 的协程体——并入会让清理排在 `syncTools` 之后（它抛异常则清理永不执行），也会给既有启动时序增加耦合。独立 launch 保证零干扰，未改变任何既有启动时序 / 放行屏障。

**不涉及 Room**：本地数据走 DataStore，未新增 Entity / DAO / Migration，AppDatabase 版本 45 与实体清单一行未动；未动 `ContentFilter` / FGS / 心跳 / 消息管线 / 网络层。

## 验证
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**（459 actionable tasks，`app:compileDebugKotlin` 零错误）。
- `./gradlew :core:agent:testDebugUnitTest :core:ui-common:testDebugUnitTest` → **BUILD SUCCESSFUL**；`:core:agent` **303 tests / 0 failures**，`:core:ui-common` **15 tests / 0 failures**。
- 全仓（排除 `build/`、`docs/`、`.git/`、嵌套参考检出 `_ref_master/`）grep `coffee|luckin|瑞幸`：2152 个源文件扫描，剩 74 处命中，均为**保留项或历史记录**，无功能性残留（清单见下）。
- 构建期两次因**并行任务占用** `core/domain|core:database` 的 `classes.jar`（`java.nio.file.FileSystemException: 另一个程序正在使用此文件`）失败，非本次改动所致；待并行 Gradle 守护进程空闲后重跑即通过。

## 剩余引用与保留理由
| 位置 | 数量 | 保留理由 |
|---|---|---|
| `core/ui-common/.../AppIcons.kt` + `feature/profile/.../GeneralSettingsScreen.kt` | 2 | `AppIcons.Coffee` 是共享图标名，「工具设置」分类仍在使用 |
| `YuNianApplication.kt` | 4 | **本次新增**的凭据清理代码自身（路径字面量 `luckin_coffee_prefs` 与日志文案），属必需 |
| `.device-backup/logcat_*.txt` | 9 | 设备日志历史回显，非当前态断言 |
| `README.md` / `AGENTS.md` / `CLAUDE.md` | 7 | 仓库文档，不在本次授权范围 |
| `core/agent` / `core/ui-common` 源码 KDoc（`CapabilityGrantCodec.kt`、`CapabilityGrantStoreImpl.kt`、`PluginBlueprintParser.kt`、`PluginSettingsSection.kt`、`Plugin.kt`） | 7 | 插件 id 的**示例注释**（形如「如 `coffee.luckin`」），非功能引用 |
| `core/agent` 测试里把 `luckin_*` 当**任意夹具工具名** | 25 | 纯字符串夹具，测试全绿；不影响编译与行为 |
| `feature/chat`：`ToolActivityBar.kt` / `ChatScreen.kt` / `ChatToolIntent.kt` | 9 | ⚠️ **功能性残留（非本次授权范围）**：`luckin_*` 工具友好名映射、确认标题映射，以及 `ChatToolIntent` 关键词闸门里的「咖啡 / 瑞幸 / luckin」——工具已不存在，这些映射成为死条目（关键词会让「瑞幸」触发一次无结果的工具意图判定）。建议后续清理 |
| `feature/settings`：`CapabilityGrantBoard.kt` / `CapabilityGrantBoardTest.kt` / `PluginSettingsBoardTest.kt` | 6 | ⚠️ 属**并行子代理**领地，未动 |
| `feature/profile` 4 个 locale 的 `settings_category_tools_desc` | 4 | ⚠️ **用户可见残留（非本次授权范围）**：文案仍为「瑞幸咖啡等工具能力」/「Luckin Coffee and other tools」/「瑞幸コーヒーなどのツール」。建议改为「自动化等工具能力」等 |
| `.idea/gradle.xml` | 1 | IDE 工程配置里仍列 `$PROJECT_DIR$/feature/coffee`（本地 IDE 元数据，非构建输入） |

