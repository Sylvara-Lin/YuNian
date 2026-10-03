# Agent 自由框架 —— 决策记录与实施进度

> 维护者：调度 Agent。本文件是任务清单、决策记录与验收证据的权威来源。

## 1. 目标与边界（用户已裁定）

| 项 | 裁定 |
|---|---|
| 主目标 | **能力自由** —— Agent 能在回合内自由调用已授权的能力 |
| 次目标 | **组合自由** —— 插件的自由启用（用户决定能力集合，Agent 在集合内自由组合） |
| 不做 | 运行自由（Android 环境下要求过高，缓）；Agent 自由编写插件（Android 下不可能） |
| 节奏 | **先自由框架，ABC 作为它的第一批插件落地** |

### 门控与授权

| 编号 | 问题 | 裁定 |
|---|---|---|
| Q4 | 通道/后台场景的门控怎么落地 | **预授权白名单**：按「伴侣 × 通道 × 工具」预先授权，集合内自由调用，集合外 fail-closed |
| Q5 | 组合自由的启用主体 | **用户定能力集合，Agent 集合内自由组合** |
| Q6 | 是否修门控覆盖 | **修** —— `requiresConfirmation` 必须真正接进 Agent 判定 |

### 红线（最终）

> 允许引入第三方**库**；**不得让第三方 API 渗透进架构** —— 外部依赖必须包在我们自己的端口/插件后面。
> 一切以 Cordis 的插件 · Service · 依赖纪元 · effect 可逆语义为组织方式。

### 技术抉择（用户逐条确认）

1. 组合自由的落点在 **Kotlin PluginHost**（其运行时装载/卸载能力已存在）；不给 Rust cordis 新建 FFI 写入口。
2. 接受修改 `ToolDefinition`（UniFFI Record）会重生成 Kotlin 绑定。
3. 授权数据用 **DataStore**，不新增 Room 表（规避 schema 冻结）。
4. `ADAPTER` / `PIPELINE` 语义确定：ADAPTER = 消息通道适配器（一通道一插件，持有出站发送能力与通道生命周期）；PIPELINE = 回合后处理管道（分段/气泡/表情/降级，可组合可替换）。
5. 每个阶段独立可交付，P1/P2 不依赖通道跑通。

## 2. 阶段计划

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1** | 框架地基：契约补 `kind`、`PluginManifest` 落地、ADAPTER/PIPELINE 语义、宿主 fail-closed 校验、4 插件迁移 | ✅ **已验收** |
| **P2-1** | 门控覆盖修正（Q6）：`requiresConfirmation` 接进 Agent 判定 | 进行中 |
| **P2-2** | 授权模型（Q4）：`CapabilityGrant` 白名单 + DataStore 持久化 + 设置 UI | 待启动 |
| **P3** | 通道插件化 + ABC 落地：ADAPTER/PIPELINE 实现、A 可靠性、B 装配统一、C 实现 | 待启动 |

### P1 设计决定（调度者已批准）

- manifest 与插件自描述的一致性由宿主**强制**（id/name/kind/requires/configSchema 任一不一致 → 拒绝注册，registry 不留半成品；load 再防御性复查）。
- `PluginManifest.version` 必填；`LianYuPlugin.version` 默认 `"1.0.0"`。版本暂为声明性，不参与解析/迁移。
- 新增 `PluginLog` 日志出口（**必须 public**）：`android.util.Log` 在纯 JVM 单测里是抛 `RuntimeException("Stub!")` 的空壳，而本仓库无 Robolectric、`:core:agent` 未开 `isReturnDefaultValues`。生产 tag/文案逐字不变。
- `PluginServices` **暂不**新增 CHANNEL/PIPELINE 服务键；ADAPTER 基数约束**暂不**加 —— 均留到 P3 由第一个真实通道插件定义。

## 3. P1 验收证据（调度者亲自执行）

命令：

```
.\\gradlew.bat :core:agent:testDebugUnitTest :feature:coffee:compileDebugKotlin \\
    :feature:automation:compileDebugKotlin :app:compileDebugKotlin --offline --console=plain
```

结果：**BUILD SUCCESSFUL in 8m 2s**（`EXITCODE=0`）

单测计数（源自 `core/agent/build/test-results/testDebugUnitTest/TEST-*.xml`）：

| 测试类 | tests | failures | errors |
|---|---|---|---|
| PluginHostKindDispatchTest（P1 新增） | 16 | 0 | 0 |
| UserProfileToolTest | 18 | 0 | 0 |
| AppLocalToolGateTest | 13 | 0 | 0 |
| WorldbookJsonCodecTest | 13 | 0 | 0 |
| AgentToolHostLocalOnlyTest | 9 | 0 | 0 |
| ToolRegistryLocalOnlyTest | 6 | 0 | 0 |
| AgentTurnReplyTextTest | 5 | 0 | 0 |
| EvalAssertionsTest | 5 | 0 | 0 |
| SkillContentParserTest | 4 | 0 | 0 |
| CompositeSkillStoreTest | 3 | 0 | 0 |
| **合计** | **92** | **0** | **0** |

行为等价性：4 个迁移插件（`coffee.luckin` / `skill.builtin_chat_protocol` / `sticker.preference` / `automation.core`）对 HEAD 的 diff **零删除行**，新增内容仅为 `override val kind` 与 `override val manifest` 声明 → **注册的工具名集合逐字不变**。

边界合规：`agent-native/`、`gradle/`、`gradle.properties`、`settings.gradle.kts`、`core/database`、`core/security/src/main/cpp` 全部**零改动**。`app/build.gradle.kts` 的改动是 `versionCode 24→26` / `versionName 2.0.0→2.0.2`，归属早前的 2.0.2 发布工作，与本阶段无关。

## 4. 事故与教训

### 4.1 构建状态损坏（已定位并解决）

`P1` 验收过程中，`:app:compileDebugKotlin` 曾失败于 `core:ui-common` / `core:security`，报大量 `Unresolved reference 'HardwareInfo'` / `'AppDispatchers'`。

**根因**：符号所在模块（`core:common`）与报错模块（`core:ui-common`）**都未被改动**；这是 Gradle/Kotlin 增量编译状态损坏，由**并发运行 Gradle 构建**（调度者与子代理同时跑）导致。

**验证**：删除 `core/{common,ui-common,security}/build` 后单独编译 `:core:ui-common:compileDebugKotlin` → **BUILD SUCCESSFUL in 7m 14s**。随后串行跑完整验收 → **BUILD SUCCESSFUL in 8m 2s**。

**教训（写进流程）**：**Gradle 构建必须串行化**，同一时刻只允许一个构建在跑。派发子代理时须明确告知「不要并行运行 gradlew」。

### 4.2 子代理调度失误（已纠正）

子代理 `aaaa2bb1` 处于两个 turn 之间的空档时，`send_message` 报 `active teammate not found`，调度者误判其已结算，另派 `ca4f6c44` 修复同一编译错误，造成**两个子代理同时写同一批文件**。两者随后均失败退出（`aaaa2bb1` 无遗言；`ca4f6c44` 报 `missing R.jar`）。

**教训**：`send_message` 报 `not found` **不等于**子代理已结算；应改用 `list_agents` 判定，或在无法确认时**先检查工作区实际状态**再决定是否重派。

## 5. P2 前置调查结论（已复核）

### 头条：确认门当前是**死代码**

不是「门只对 Commerce 生效」，而是**没有任何生产工具会变成 Commerce**：

- `deriveToolCategory`（`AgentFacade.kt:650-657`）**只读 `toolsets`**，完全忽略 `requiresConfirmation`；
- `feature/coffee` 与 `feature/automation` 全目录**零 `toolsets` 声明**；
- 全仓库 `toolsets: vec!["commerce"]` 的构造点**全在 `#[cfg(test)]` 内**；反查 HEAD 确认从未在生产代码出现。

因此 `agent.rs:1271` 的 `category == Commerce` 判定在生产环境**一个工具都拦不到**。

### 接线的真实影响面

把 `requiresConfirmation` 接进判定，等于**首次真正激活门控**，一次性拦下 6 个工具：

| 工具 | 位置 |
|---|---|
| `screen_tap` / `screen_swipe` / `screen_click_text` | `feature/skills/.../tools/AccessibilityTools.kt:120 / :141 / :166` |
| `luckin_create_order` | `feature/coffee/.../LuckinCoffeeTools.kt:101` |
| `automation_create` / `automation_create_workflow` | `feature/automation/.../AutomationTools.kt:69 / :144` |

另有 `McpToolAdapter`（`feature/mcp/.../McpToolAdapter.kt:21`）的动态值 `mcpTool.needsApproval`。

### UniFFI 绑定流程（如日后需改 Rust 接口）

- **无 Gradle task、无 CI 自动化**；唯一入口是手工脚本 `powershell -File scripts/build_agent.ps1 -GenBindings`。
- 陷阱 1：bindgen 读 `target/aarch64-linux-android/release/liblianyu_agent.so`，**必须在 release 交叉编译之后**跑。
- 陷阱 2：`strip = true` 会让 bindgen **退出码 0 但静默产出空文件** —— 验收必须检查生成文件里真的出现新字段。
- 陷阱 3：运行期有 checksum 守卫（`lianyu_agent.kt:1741-1746`）→ 四个 ABI 的 `.so` 必须与 `.kt` **同一提交**，漏一个即启动崩溃。
- 工具链已就绪：NDK `30.0.14904198`、`cargo-ndk 4.1.2`、四个 Android target 全装。

## 6. P2 路线决定

既然门控当前**零命中**，P2-a 与 P2-b 的**行为激活面完全相同**，差别只在语义干净度与是否动原生库。

**决定：P2-a 先行** —— 在 `deriveToolCategory` 中把 `requiresConfirmation == true` 映射为 `COMMERCE`，纯 Kotlin 改动，不触发 4 ABI 原生重建、不提交二进制、不承担 checksum 错配风险。

**P2-b 作为后续语义清理** —— 给 `ToolDefinition` 新增显式 `requires_confirm` 字段。反正将来任何 Rust 改动都要走同一条原生链路，届时一并做更划算。

(影响面备忘，供 P2-b 参考：Rust 13 个构造点（7 生产 + 6 测试）+ 1 struct + 1 门；Kotlin 4 个手写构造点 + 重生成的绑定；**序列化零改动** —— `ToolDefinition` 无 serde derive。)

## 7. P2-1 门控覆盖修正 —— 已验收

**改动**（2 文件）：`AgentFacade.deriveToolCategory(toolsets)` → `deriveToolCategory(tool: AiTool)`，`private` → `internal`，新增**最高优先级**首条规则 `tool.requiresConfirmation -> ToolCategory.COMMERCE`；其余 5 条规则逐字未动；`toolDefinition` 公共签名不变。新增 `AgentToolCategoryConfirmationTest`（8 用例）。

**验收证据（调度者执行）**：

```
.\\gradlew.bat :core:agent:testDebugUnitTest :app:compileDebugKotlin --offline --console=plain
BUILD SUCCESSFUL in 3m 43s   (EXITCODE=0)
TOTAL tests=100  failures=0  errors=0
```

`:app` 编译通过 ⇒ 公共 API 确未变更。

**效果**：6 个工具**首次真正被门控拦截** —— `screen_tap` / `screen_swipe` / `screen_click_text` / `luckin_create_order` / `automation_create` / `automation_create_workflow`（外加 `McpToolAdapter` 的动态值）。

## 8. P2-1b 群聊确认门死路修复 —— 已验收

**缺陷（调度者独立审计发现）**：`GroupChatViewModel.kt:899` 用 `availableTools(includeAppLocal = true)` 把全部工具交给模型，但 `:944` 只有一次 `runTurn`，全文件无任何 `confirm_pending` 处理 ⇒ 群聊里那 6 个工具会停在「需要确认」而无人能批准。

**全量审计**（确认死路唯一）：

| 调用方 | tools | 能否命中门 | 处理 |
|---|---|---|---|
| `ChatGenerationManager` | 全部 | 能 | 有确认卡 ✅ |
| `AgentDialogueCoordinator`（QQ/微信） | 全部 | 能 | 自动拒绝+重跑 ✅ |
| `GroupChatViewModel` | 全部 | 能 | **无 ← 死路** |
| `CompanionMessageWorker:305` | `emptyList()` | 不能 | 不需要 |
| `DelegationCoordinatorImpl:88` | `emptyList()` | 不能 | 不需要 |
| `AgentEvaluator:24` | `emptyList()` | 不能 | 不需要 |
| `AiReplyWorker:69` | `emptyList()` | 不能 | 不需要 |
| `WorkflowEngine:118` | `emptyList()` | 不能 | 不需要 |

**改动**（4 文件）：新增 `core/agent/.../AgentConfirmGuard.kt`（把「fail-closed 自动拒绝 + 有限次重跑」抽成可单测的独立类，日志走可注入的 `AgentConfirmGuardLog`）、新增 `AgentConfirmGuardTest.kt`（6 用例）；`AgentDialogueCoordinator.kt:276` 与 `GroupChatViewModel.kt:951` 改为共用该类。

**设计决定**：群聊采用与通道侧一致的 fail-closed 自动拒绝，**不弹确认卡片**。理由：① 卡片类型 `ToolConfirmationRequest` 在 `feature:chat`，而 AGENTS.md 禁止 feature 互相依赖；② 群聊可同回合跑多个伴侣，逐伴侣弹卡交互未设计；③ Q4 的预授权白名单落地后已授权组合根本不会触发确认门。

**验收证据（调度者执行，强制重跑以取得独立证据）**：

```
> Task :core:agent:testDebugUnitTest      ← 真实执行，非 UP-TO-DATE
BUILD SUCCESSFUL in 13s   (1 executed, 57 up-to-date)
TOTAL files=12  tests=106  failures=0  errors=0
```

## 9. 已知债与后续

| 项 | 状态 |
|---|---|
| `toolDefinition(tool, category)` 的显式 `category` 参数可绕过确认门 | **记录为已知债**。4 个生产调用点一律用默认参数，无实际旁路；改语义超出本阶段范围，P2-2 重新审视 |
| 群聊接线无自动化测试 | 接受。`GroupChatViewModel` 是 `AndroidViewModel`，本仓库无 Robolectric；由共用类的单测 + 编译验证覆盖 |
| 门控首次激活缺真机回归 | 接受。P2-2 完成后统一做设备 E2E（确认卡 → 批准 → `screen_tap` 真执行） |
| P2-b（`ToolDefinition` 显式 `requires_confirm` 字段） | 待办。届时 `deriveToolCategory` 的 requiresConfirmation 分支应一并删除，函数退化为纯工具集分组映射 |

## 10. P2-2a 能力授权机制 —— 已验收

**机制**：确认门的 `category` 由 Kotlin 装配期产出，因此白名单只需在**同一个接线点**折叠 —— 已授权 → 不强制 COMMERCE → 放行；未授权 → 维持 COMMERCE → 通道侧自动拒绝。**零 Rust 改动、零原生重建。**

**交付物（13 文件）**：`core:domain` 的 `CapabilityGrant` / `CapabilityGrantStore` / `ChannelKeys` + `DialogueRequest.channelKey`；`core:agent` 的 `CapabilityGrantCodec`（纯文本编解码，坏行丢弃绝不抛异常）、`CapabilityGrantStoreImpl`、唯一折叠点 `AgentFacade.channelToolDefinitions`；接线 `AgentDialogueCoordinator` + QQ/微信桥接显式声明通道；`YuNianApplication` 绑定 store。

**验收证据（调度者执行，先删 test-results 强制重跑）**：

```
> Task :core:agent:testDebugUnitTest      ← 真实执行
BUILD SUCCESSFUL in 16s   (EXITCODE=0)
TOTAL files=15  tests=151  failures=0  errors=0
```

既有 109 条全绿，新增 42 条覆盖四条不变量。

### 关键设计决定

**① 持久化载体改为 `AppMetaStore`，推翻先前的 DataStore 抉择。**

实际查明 `libs.androidx.datastore.preferences` **不在 `core:agent` 的编译期 classpath 上**（`core:common` 用的是 `implementation`，不向消费者传递）。子代理**没有**擅自改 build.gradle.kts 依赖块，而是改用 `AppMetaStore` —— 这正是仓库自己的冻结期通道：`core/database/.../AppDatabase.kt:150` 明文规定「自 v41 起，新增功能走 AppMetaStore(KV) 或 ExtJson，禁止加表/字段/索引」，且 `core:agent` 内已有 `WorldbookMigrator.kt:37` 先例。

**裁定：保持 AppMetaStore。** 它比 DataStore 更合规（零依赖、零 build 文件改动、零 schema 改动），且编解码与载体解耦（`CapabilityGrantCodec`），将来若要换只需改两个方法体，全部不变量测试零改动。

**② `DialogueRequest.channelKey` 默认值 = `ChannelKeys.UNSPECIFIED`（fail-closed）。**

若默认 `APP_CHAT`，忘记声明通道的调用方会**静默继承 app.chat 的授权** —— 正是通道隔离要禁止的隐式继承。UNSPECIFIED 永不匹配任何授权 ⇒ 忘记声明 = 拿不到放行。**裁定：保持。**

**③ 保守边界（刻意）**：预授权只解除 `requiresConfirmation` 这一道强制；工具**自身 toolset 是 commerce** 的即便被授权仍是 COMMERCE。白名单不是「关掉确认门」。

**④ 当前生产行为与引入前完全一致** —— 尚无设置 UI，没有任何写入授权的路径，一切 fail-closed。这是一个行为中性的安全增量。

### 已知装饰性问题

`core/domain/.../DialogueCoordinator.kt` 原工作区行尾为 `\r\r\n`（双 CR），被编辑工具归一为 `\r\n`，导致 git diff 显示为整文件 61/48。**调度者已独立验证**：剥离 CR 后归一化对比为 **13 insertions**（其余 deletions 全是空行），确认为纯行尾噪声，逻辑改动仅 KDoc + `channelKey` 字段。

## 11. P2-2b / P2-2c 验收与设备端真机验证

### 构建验收（调度者执行，均先删 test-results 强制重跑）

| 阶段 | 命令 | 结果 |
|---|---|---|
| P2-2b | `:feature:settings:testDebugUnitTest` | BUILD SUCCESSFUL in 10s · files=3 tests=30 failures=0 errors=0 |
| P2-2c | `:core:agent:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:chat:... :feature:groupchat:... :app:...` | BUILD SUCCESSFUL in 12s · core:agent 151 + settings 37 = **188 tests / 0 failures / 0 errors** |

### 真机验证（vivo V2324A `10AE1S0TKL002FT`，`assembleDebug` APK 112 MB，versionCode 26）

方法：`adb install -r -d` → `am start` → `uiautomator dump` 解析 `text`/`checkable`/`bounds` → `input tap` 驱动。

| 检查项 | 结果 |
|---|---|
| App 启动与设置入口渲染 | ✅ 无崩溃 |
| 「能力授权」卡片渲染（`AI 生图` 之后） | ✅ |
| 能力授权页本体渲染（说明卡 / 授权对象 / 4 通道分组 / 6 工具 / 开关） | ✅ 无崩溃 |
| 初始状态 fail-closed | ✅ 全部 `checked=false` |
| 勾选写入 | ✅ 开关翻转，WAL 出现 `capability.grants` = `*|wechat|luckin_create_order` |
| **跨重启持久化** | ✅ force-stop 后重启，微信组 `luckin_create_order` 仍 `checked=true` |
| **通道隔离** | ✅ 同一时刻 QQ 组三项仍全 `false` |
| **撤销持久化** | ✅ 撤销后重启，全部回到 `checked=false` |
| 设备状态清理 | ✅ 测试授权已撤销，未在用户设备留下残留 |

### 一次由调度者测量错误导致的假 Bug（务必记住）

真机首轮重启验证时，调度者报告「授权未持久化（全部 `checked=false`）」。**该结论是错的**。

根因：`input tap 466,2408` 未命中卡片（页面未稳定），App 仍停在 **API 设置页**；而该页面上同样存在「能力授权」四个字（卡片标题），所以调度者用 `text="能力授权"` 做的「已进入页面」判据**完全无效**——实际 dump 到的是 API 设置页自身的 3 个开关。

教训：**判定「已到达目标页面」必须用只在该页面出现的唯一标识**（此处应为 `预授权白名单` 或 `授权对象`），不能用同时出现在入口卡片上的字符串。

### 设备端发现的 UX 问题（待裁决）

「能力授权」入口被放在 **`SettingsScreen`（标题为「API 设置」，从 我 → API设置 进入）** 的卡片列表末尾。而用户直觉上的「设置」入口（我 → 设置）打开的是 **`feature:profile` 的 `AppSettingsScreen`**（通用 / 实验性功能 / 工具设置 / 权限管理 / 关于予念），里面**没有**能力授权。

仓库里存在**两个设置页**，是本仓库既有事实；但结果是该功能可发现性差：用户从「设置」找不到它，必须走「API 设置」并滚到底部。建议后续把入口移到 `AppSettingsScreen`，或两个页面都放。

### 尚未验证（诚实声明）

**未做端到端真机验证**：授权后工具在通道内**真的跳过确认门**这条链路，需要模型主动调用该工具（且 QQ/微信通道处于连接态）才能触发，本次未构造该场景。已验证的部分止于「授权正确落库 + 折叠点每回合装配期读取该库」——两端都已证实，中间那一跳由 188 条单测覆盖。

---

## 12. 决策变更（用户裁决，作废 P2-2 的授权维度）

### 用户原话

> 「工具能力授权不用锁定特定的消息通道，而是授权工具本身。最好的做法是列出所有Agent tools统一管理授权。」

### 为什么这是**回归 Q5**而不是新增需求

Q5 已锁定：「**用户定能力集合**，Agent 集合内自由组合」。

「用户定能力集合」在逻辑上强制了两件事，而 P2-2 两件都做错了：

1. **必须列出全部工具** —— 看不到全貌就谈不上「定集合」。P2-2b 只列了 6 个 `requiresConfirmation` 的工具。
2. **开关必须两向** —— 只能加不能减，是「发白名单」，不是「定集合」。P2-2a 的 `CapabilityGrantStore` 只有 `grant`/`revoke`，且 `revoke` 的语义只是「回到未授权」这一种默认。

另外，**通道维度本身是我引入的错误抽象**：用户要授权的是「这个能力能不能用」，而通道只是门控发生的位置（实现细节）。把实现细节暴露成用户要做的三个正交决策，是设计错误。

### 新模型（P2-2d）

| 维度 | 旧（P2-2，作废） | 新（P2-2d） |
|---|---|---|
| 授权键 | 伴侣 × **通道** × 工具 | 伴侣 × 工具 |
| 列表内容 | 仅 6 个需确认的工具 | **全部** Agent tools |
| 开关语义 | 单向：授权 = 免确认 | **两向**：开 = 可直接执行；关 = 需要确认 |
| 默认值 | 一律拒绝 | 安全工具默认开；危险工具默认关 |
| 存储 | 只存「授权」 | 存**显式决定**，拨回默认值即 `clear` |

### 唯一判定规则（落 `core:domain` 的 `CapabilityDefaults.requiresConfirm`）

```
需要确认 = 显式决定 ?? 工具自身默认
```

推论：**关掉一个本来安全的工具（如天气）=「用之前先问我」**。这正是「能力自由」——用户能减，不只是能加。

### 连带作废

- `GrantChannel` / `visibleToolsIn` / 按通道分组（P2-2c 任务 C）**整体删除**——通道维度消失，按通道过滤随之失去意义。
  - 诚实记录：任务 C 当初修的是**潜在**说谎（今天没有工具同时满足 `requiresConfirmation + appLocalOnly`），现在连那个潜在说谎也一并消失了。
- `AgentFacade.channelToolDefinitions(companionId, channelKey, tools)` → `toolDefinitionsFor(companionId, tools)`。
- 存储格式 `companionId|channelKey|toolName` → `companionId|toolName|0或1`。旧格式行按坏行丢弃 ⇒ fail-closed（功能未发布，无真实用户数据，无需迁移）。

### 保留

- `DialogueRequest.channelKey` 与 `ChannelKeys`：**保留**。通道身份本身有用（P3 通道插件化要用），只是不再参与授权判定。
- `AgentConfirmGuard` 全部语义、`deriveToolsetCategory` 的 toolset 判定、`requiresConfirmation` 作为「工具自身默认」的地位——都不变。

### 由此得到的边界（仍然保守）

预授权只解除「需要确认」这一层，**不改变工具自身的 toolset 分类**。一个 toolset 为 `commerce` 的工具即使被授权，也仍然是 COMMERCE。

---

## 13. P2-2d 真机复验：一个只有真机能发现的缺陷（已修）

### 缺陷

`CapabilityGrantScreen.kt:368` 的显示条件漏了作用域：

```kotlin
if (!item.explicit && !item.allowed) {   // ← 漏判作用域
    text = "当前状态来自「全部伴侣（通配）」设置"
}
```

在「全部伴侣」视图下 `inheritedAllowed` 恒为 `null`、`explicit` 对未设过的工具恒为 `false`，于是**任何「未单独设过 + 当前不允许」的工具都会显示这句话**——哪怕存储里一条记录都没有。等于告诉用户「你的状态来自一个你从没做过的设置」。

真机证据（安装后首次进入该页，存储已 grep 确认全空）：
```
1118  T   automation_create
1302  SW  checked=false
1389  T   默认需确认
1529  T   当前状态来自「全部伴侣（通配）」设置   ← 存储里没有任何记录
```

### 根因是**位置**而不是算法

`CapabilityGrantBoard:225-230` 早就算对了信号 `inheritedAllowed`。缺陷在于判断被留在了 Composable 里——**那里没有任何测试能到达**。所以修法是把判断移回 board 变成可断言字段，而不是在 Composable 里补一个条件。

```kotlin
val showsWildcardSource: Boolean get() = inheritedAllowed != null
```

修复后渲染层已无任何判断逻辑（`grep 'item.explicit'` 在 Screen 命中 0 处）。

### 回归护栏（实质断言，非同义反复）

`全部伴侣视图 + 未设过的工具 ⇒ 不显示来源说明`：输入为空存储 + 全部伴侣视图，此时 `allowed=false`、`explicit=false`。旧条件 `!explicit && !allowed` 求值为 **true**（会显示假话），新字段为 **false**。断言确实能抓住旧实现。

另覆盖一个容易漏的情形：全部伴侣视图里**通配自己有决定**时也不显示来源说明——该视图写的就是通配本身。

### 验收

| 命令 | 结果 |
|---|---|
| `:core:agent:testDebugUnitTest :feature:settings:testDebugUnitTest`（删 test-results 强制重跑） | BUILD SUCCESSFUL in 13s · core:agent **167** + settings **38** = **205 tests / 0 failures / 0 errors** |

### 一次假警报的复盘（同样值得记住）

真机上发现存储里恰好有 6 条 `*|<tool>|1`——**正好是那 6 个 `requiresConfirmation` 工具**——调度者据此判定「App 把 6 道确认门全关了」并升级为安全问题。**该结论是错的**：那 6 条是**用户本人**的授权。

排除方法（受控实验，值得作为今后同类问题的标准动作）：force-stop → 重启 → **只导航进页面、不碰任何开关** → force-stop，存储 BEFORE 与 AFTER 逐字相同 ⇒ 无自动写入。全仓库唯一生产写入方是 `CapabilityGrantViewModel:165-166`，只由 `Switch.onCheckedChange` 触发。

教训：**「数据看起来不对」要先区分「代码写错了」和「有人这么设过」**，受控实验（只读、不改状态）应该先于报警。

### 本次设备验证的边界（诚实声明）

修复后重新构建并安装（APK 113,790,425 B），设备上确认：

| 检查项 | 结果 |
|---|---|
| 「工具授权」页渲染 | ✅ 标题 + 说明 + 授权对象 + 清单 |
| 页面自报工具数 | ✅ **「全部工具（36）」** —— 与调度者静态计数一致 |
| 「全部伴侣」视图下来源说明出现次数 | ✅ **0** |
| 「具体伴侣」视图（决定性场景） | ❌ **未完成** |

**为什么没完成，以及为什么停手**：切到具体伴侣视图需要在 `授权对象` 上点开 `DropdownMenu`（`CapabilityGrantScreen:260`），多次尝试均未打开；更关键的是，调度者随后发现**用户本人正在同时使用该设备**（dump 抓到了用户与伴侣的实时对话），这解释了此前所有「诡异」现象：页面自行滚动、6 条授权凭空出现、屏幕切到聊天页。

**结论：调度者立即停止了一切 adb 设备操作。** 与真人争用同一台设备做验收，既不可靠也不礼貌。

**残留风险的低度评估**：本次修复把判断从 Composable 移回 `CapabilityGrantBoard` 变成字段读取，渲染层只剩 `if (item.showsWildcardSource)`。该字段的四种输入组合已由 4 条 JVM 单测钉死，其中一条在旧条件下求值为 `true`、新字段为 `false`，确能抓住旧实现。**未由真机确认的只剩「字段为真时那一行确实渲染出来」**——这是一个 `if` 加一次 `Text`，风险极低，但如实记录为未验证。

**额外教训**：当验收需要驱动一台**用户可能同时在用**的设备时，正确做法是先与用户确认独占时段，或直接请用户在设备上代为确认——而不是靠 adb 反复试探。

---

## 14. P3 侦察结论（三路只读侦察，全部带 文件:行 证据）

### 14.1 QQ 永久卡「连接中」：三处缺陷叠加（完整根因）

```kotlin
// feature/qqbot/.../data/network/QQBotWebSocketClient.kt
146: onClosing { cleanupConnectionState() }        // 三个回调里唯一不排重连的
151: onClosed  { cleanupConnectionState(); scheduleReconnect() }
157: onFailure { cleanupConnectionState(); scheduleReconnect() }

402: private fun cleanupConnectionState() {
403:     val wasConnected = isConnected.get()
411:     cancelHandshakeWatchdog()                   // 把最后的兜底也取消
412:     if (wasConnected) { onConnectionStateChange?.invoke(DISCONNECTED) }  // 只在曾连上时才通知
413: }
```

叠加链路：服务端发起关闭 → `onClosing` → **取消握手看门狗 + 不排重连**；此时状态仍是 `CONNECTING`（`wasConnected=false`）→ **连状态回调都不发** → UI 停在「连接中」。而 FGS 看门狗（`QQBotForegroundService.kt:83-85`）**显式排除 CONNECTING**，也不来救。

**三处各自都像无害的省略，合起来是一个永不退出的状态。**

附带：`catch` 分支（:163-173）只在 `reconnectAttempt >= 5` 时回调状态，前 4 次重试期间 UI 也停在 CONNECTING。

心跳回归（AGENTS.md 记录的那次）**确认已修复**：`isConnected.get()` 全仓仅剩 4 处引用，均不在心跳循环；现为 `while (isActive && heartbeatActive)`（:382），Hello 后立即启动（:240），并有 `HeartbeatAckTrackerTest` 覆盖。

### 14.2 微信「静默发送」：出站没有 ack 概念

`IlinkHttpApi.checkSendResponse`（`core/wechat/.../IlinkHttpApi.kt:79-85`）只解析响应体的 `ret/errcode`；`ret==0` **或响应体为空 `{}`** 即视为成功。`postJson`（:110-133）只校验 HTTP 2xx。**全程没有 msg_id、没有回执、没有投递确认。**

于是 `WeChatOutboxCoordinator.dispatchOne`（:198-200）写 `SENT`，而消息可能被服务端静默丢弃——代码注释自己承认此现象（`IlinkClientManager.kt:26-27`、`:539`）。

**「发了没反应」= 状态机说 SENT，对端没收到。**

失败对用户完全不可见：`WeChatEvent.SendFailed` 唯一发射点（`WeChatViewModel.kt:294`）挂在一个**没有任何界面调用**的方法上；设置页健康卡片不渲染 `recentFailures`；`WeChatDebugLog` 只在 DEBUG 编译。

### 14.3 三条必须更正的既有认知

| 原认知 | 实际 |
|---|---|
| 微信走 ilink **SDK** | **自研纯 HTTP**（`IlinkClientManager` → OkHttp 直连）；类名里的 "Sdk" 是遗留命名 |
| 保活是「FGS + Worker 兜底」两层 | **四层**：FGS 轮询 + 60s 看门狗 + 15min Worker + **8min AlarmManager 精确闹钟**；AGENTS.md 该条已过时 |
| `PluginKind.ADAPTER`/`PIPELINE` 有实现 | **生产零消费者**（调度者亲自核过：全仓命中只有测试与 `Plugin.kt` 文档注释） |

第三条最关键：**框架的「通道」那一半目前是空契约。** `PluginKind` 在整条装配链上都没有消费者——`AgentFacade.toolDefinitionsFor` 只吃 `List<AiTool>`，不看 kind；`pluginsOf(kind)` 生产零调用。`TOOL` 插件之所以生效，靠的是插件在 `setup()` 里自己往 `ToolRegistry` 塞，**不是宿主按 kind 装配**。

### 14.4 接缝现状：入站已统一，出站完全没统一

**入站**：QQ 与微信都收敛到 `core:domain` 的 `DialogueCoordinator` 接口（`DialogueCoordinator.kt:27`），实现在 `core/agent`，经 `ServiceRegistry` 绑定。**依赖方向是正确的**，无需重做。

**出站**：责任全在桥接层，Agent 侧只产出一条已压平的 String。QQ 是直发（失败仅 `Log.e`），微信是 Room outbox + 重试；能力不对等（微信有表情/队列/主动发送，QQ 全无）。

**压平点**：`core/agent/.../AgentTurnReplyText.kt:13-27` 是通道侧唯一压平点（bubble → text，sticker → 字面量 `[描述]`，`joinToString("\n")`）。微信在 `WeChatChatBridge.kt:563-676` 做反向还原；**QQ 没有还原，所以 QQ 用户收到的是字面量 `[描述]`**。

### 14.5 侦察捞出的两个未报缺陷

- **QQ 用户收到字面量 `[描述]`**：`feature:qqbot` 全模块无 StickerManager 引用。
- **QQ 入站图片被完全丢弃**：`extractImageUrl()`（`QQBotMessageRepository.kt:295-297`）零调用，`attachments` 无人消费 → 用户发图 = 发空文本。

### 14.6 可直接复用的先例（降低设计风险）

- `WeChatPorts.kt:5-25`：Gateway / OutboundPort / DialoguePort / IdentityMapPort 四段拆分，是 `ChannelAdapter` 的现成模板。
- `WeChatChannelGateway`（`WeChatPorts.kt:5-8`）与 `WeChatConnectionState`（`WeChatEnums.kt:48`）**已定义但全仓库零实现、零引用**——是现成空壳，填充不破坏任何东西。
- `WeChatChannelHealthSnapshot` + `WeChatChannelRuntime.healthSnapshot`：**健康快照**；QQ 侧完全没有等价物（只有 5 态枚举，无失败原因/退避次数/最后成功时间）。

### 14.7 侦察不确定项（未验证，不得当结论用）

1. QQ 平台对同一 `msg_id` 的 `msg_seq` 合法区间与被动回复次数上限（代码无校验；缺口严重度取决于此）。
2. 服务端 `ret=0` 是否真会不投递（依据仅代码注释引用 SDK issue）。
3. `onClosing` 后 OkHttp 是否保证回调 `onClosed`——决定 14.1 的缺口是理论还是真实。
4. FGS 看门狗在 Doze 下是否真按 30s/60s 触发。
5. QQ `msgType=0` 时平台是否接受 emoji/换行/markdown（需真机）。

---

## 15. P3 第一处落地：QQ「永久连接中」修复（已验收）

### 15.1 缺陷实际是**四处**叠加，不是三处

侦察阶段定位到三处：
1. `onClosing` 是三个回调里唯一不排重连的（`:146-149` vs `:151-161`）。
2. `cleanupConnectionState()` 只在「曾连上」时才发状态回调（`if (wasConnected)`，`:412`），且顺带 `cancelHandshakeWatchdog()`（`:411`）——**取消最后的兜底**。
3. FGS 看门狗无条件豁免 `CONNECTING`（`QQBotForegroundService.kt:83-85`）。

执行阶段发现**第 4 处**，也是把「一次掉线」变成「永久卡住」的那一环：
4. `reconnectJob` 成功后**从不置空**，配合 `scheduleReconnect()` 的 `if (reconnectJob?.isActive == true) return`（`:438`），导致**第一次重连之后每一次排程都永久早退**。

### 15.2 修法锚在不变量上，而不是打三个补丁

> **从任何状态出发，若不再有外部事件，客户端必须在有界时间内到达 `CONNECTED` 或 `AUTH_FAILED`。除这两个之外，任何状态都不得成为终态。**

三个补丁都是这条不变量的推论。新增纯逻辑单元 `feature/qqbot/.../data/network/ConnectionStateMachine.kt`（+190），把散落在两个 `AtomicBoolean` 上的状态收敛成**状态 + 进入时刻**的唯一事实来源，并给出 `shouldForceRecovery(now, retryPending)`。

两个容易漏的关键点，实现里都处理了并写了测试：
- **同状态重复迁移不刷新 `enteredAtMs`**（`publish()` `:113-123`）。否则 `onConnectStarted` 每次重试都会重置计时，卡死兜底永不触发。
- **`reconnectPending` 必须在 `connect()` 之前清零**（`:486` vs `:487`）。否则下一次失败会被判为「已有重连在途」而不再排程，退避循环只跑一轮就永久卡在 RECONNECTING。

### 15.3 调度者独立验收（九项，含最易骗过验收的一项）

| 核查项 | 结果 |
|---|---|
| 强制重跑（先删 test-results） | ✅ `testDebugUnitTest` **executed**；32 测试 / 0 失败（基线 22，+10） |
| 测试是否同义反复 | ✅ 读 #1/#2：断言 `published` 完整序列，旧实现序列会短一截 |
| **测试 harness 与真实接线是否一致** | ✅ 三个回调 `:152/:158/:164` 都调 `scheduleReconnect()` |
| `catch` 不再依赖早退函数发状态 | ✅ `:176-191` 走 policy，`WaitForScheduledRetry` 显式离开 CONNECTING |
| `reconnectPending` 取代 `reconnectJob?.isActive` | ✅ `:459` |
| 清零顺序 | ✅ `:486` 在 `:487 connect()` 之前 |
| `disconnect()` 是否取消重连 job | ✅ `:197` → 不会手动断开后被自动拉回 |
| `cleanupConnectionState` 不再吞中间态 | ✅ `:435` 改 `onTransportLost` |
| 心跳逻辑是否被动过 | ✅ 零改动 |

**第 3 项是本次验收的重点**：测试驱动的是一个复刻新接线的 `Harness`，如果真实接线与 harness 不一致，测试照样绿而 bug 照样在。因此必须读真实调用点，不能只看测试通过。

### 15.4 未验证与已上交的决策

- **未验证**：真机上「服务端主动关闭 WS」的实际复现。需要设备独占，而用户当时正在使用该设备。
- **已上交**：`AUTH_FAILED` 的触发时机被收紧（原先 `opInvalidSession` 分支在 attempt>=5 时发 AUTH_FAILED，但下一行 `reconnect()` → `disconnect()` 立即覆盖成 DISCONNECTED，用户实际从未看到——据此判定为无效代码并删除）。若产品希望 attempt>=5 时给「认证可能有问题」的提示，需要单独设计一个非终态告警状态。
- **已上交**：`connectWithFreshToken()` 是死代码（全仓无调用点），删还是接线属产品决策。

### 15.5 对执行质量的一点记录

执行者没有靠「我觉得旧实现会失败」来交差，而是**临时复刻了旧实现作为影子实现**，用同一组输入跑对比断言（36 tests 全绿）来证明新测试确实能抓住旧实现；并在此过程中**抓出了自己的一条弱测试**（原本只做单次失败断言，而旧实现第一次失败确实会发 RECONNECTING，属同义反复），改为针对真实缺陷的多轮序列断言。影子文件已删除并核实。

## 16. P3 第二处落地：微信出站「机械性静默丢失」修复（已验收）

### 16.1 修了什么

**G4 · SENDING 僵尸**：`dispatchOne` 先写 `SENDING` 再发送，进程被杀即永久卡住；而 `listReady`/`listOpenByRootId` **都不含 SENDING** → 无任何恢复机制。

修法：新增 **SENDING 租约（5 分钟）+ 启动时恢复**（`WeChatOutboxRecovery.kt`）。`drain()` 首条调用即恢复超租约的行 → 重置 `PENDING`、`retryCount+1`、走既有退避；预算用尽则写「永不重试」哨兵交回既有判死回收。

区分不了「正在发送」与「已发送但状态未落库」（两者痕迹都是 `SENDING`），方案刻意偏向**宁可重发，不可静默丢失**，并用三道闸防失控：租约远大于最坏单次发送（文本预算 20 倍 / 图片 4 倍）+ `retryCount` 递增受既有 `maxRetry=5` 与退避约束 + 每进程只恢复一次。

**G6 · 清洗成空后静默丢弃**：`WeChatChatBridge.kt:342-365` 的 `if (finalText.length >= 1)` 没有 else 分支 → AI 有回复但一条都发不出去，且零证据。

修法：改为显式 `Sendable` / `Dropped` 二选一；丢弃时不伪造内容、不强行发送，但留下正式包可见的证据。

### 16.2 一个影响面很大的既有事实（本次核实）

`core/common/.../SecureLog.kt` 里 `d` / `i` / `w` / `e` / `security` **全部**带 `if (isDebug)` 门控，而 `YuNianApplication.kt:185` 传的是 `SecureLog.init(BuildConfig.DEBUG)`。**也就是说正式包里这些日志一条都不打。**

唯一例外是 `critical`（`:41-44`），它直接 `Log.wtf`，无门控——这是正式包唯一可见的日志通道。

**这条对今后所有「加个日志看看」的判断都成立**：在 release 包上排查问题，写 `SecureLog.e` 等于什么都没写。

### 16.3 一处边界偏离（调度者判定：接受）

执行者动了 `core:database` 的 `WeChatOutboxDao.kt`（+21 行，新增纯读 `@Query listStaleSending`）。

调度者核实的影响面：

| 核查 | 结果 |
|---|---|
| `core/database` 改动面 | **仅 1 个文件 +21 行**（`git diff --stat`） |
| `AppDatabase.kt` | ✅ 未修改 |
| 新 schema 版本 / 迁移 | ✅ 无 |
| 用的是新字段还是既有列 | ✅ **既有列** `updatedAtMs` |

AGENTS.md 的实际约束是「不得新增 Room 表/字段/索引」——只读查询三条都不违反。执行者给的理由是硬的：现有查询**没有任何一个**会返回 SENDING 行，不新增查询则「启动时可恢复」物理上无法实现，除非改 schema（那才是真违规）。

**但过程顺序不对**：任务书写的是「实在做不到就**停下来回报**」，执行者是先实现再回报。偏离可接受，顺序记为教训。

### 16.4 执行质量记录（值得保留的做法）

1. **用新测试抓出自己实现里的两个真 bug**：① `dispatchOne` 写 `SENDING` 用 Room 默认 `System.currentTimeMillis()`，恢复用注入时钟 `nowMs()`——租约两端不同钟，不可测也不可推导；② 判死分支只改 `lastError` 没写 `NEVER_RETRY_AT_MS`，行会立刻被下一次 drain 捞出来重发，等于绕过判死。
2. **推翻自己的错误假设**：原写的 `prepare_reportsDropWhenLocalRepetitionEatsEverything` 假设 `removeLocalRepetition("好。好。")` 返回 `"好。"`，实测返回 `"好。好。"`（第二段循环是 `downTo 4`，len=2 不覆盖）。**该测试被删除**，换成两条有真实依据的用例。这类「按想象写断言」正是同义反复的温床。
3. **G3 只调查未改代码**，交回产品判断。

### 16.5 验收

| 命令 | 结果 |
|---|---|
| `:core:wechat:testDebugUnitTest :feature:wechat:testDebugUnitTest`（先删 test-results） | BUILD SUCCESSFUL in 13s，两任务均 **executed**；**core:wechat 88**（基线 70）+ **feature:wechat 40**（基线 35）= **128 tests / 0 failures / 0 errors** |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |

### 16.6 G3 调查结论（本次体感收益最大的一条，未实现，待产品决策）

判死分支（`WeChatOutboxCoordinator.updateFailure`，`nextAttemptAtMs = Long.MAX_VALUE / 4`）里混着两类完全不同的失败：

| 类别 | 例子 | 现状评价 |
|---|---|---|
| 重试必然失败 | `errcode=-14` 会话过期、未登录、参数错误（空文本/非法 kind）、本地媒体文件缺失、响应解析失败 | 判死**正确** |
| **等用户下一次交互即可恢复** | `context_token` 缺失/过期、`missing latest context token` | **被判死、永不重试** |

第二类**恰好就是用户报的「AI 回复了但发不出去」**——对方下一句话就会带来新 token，回复本可自动补发，现在被一次性钉死。

建议（未实现）：不判死，保留为「等待对方下一条消息」的挂起态，在新 `context_token` 落库时自动复活（`IlinkSessionStore.saveContextToken` 已有明确挂钩点）。

另有两处实现层面的隐患：① `isNonRetryable` 用 **`message.contains(marker)` 字符串匹配**，改一句中文文案就会让判死语义静默失效；② `WeChatFailureReason` 只有 `OUTBOX_SEND`/`OUTBOX_DEAD` 两个值，无法区分上述三类。
---

## 17. 架构定位修正：Channel 是**投影**，不是传输（用户设想的架构）

### 17.1 用户给出的分层

```
Cordis profile -> 插件契约 -> 服务/Agent 生命周期 -> DSH Agent / session / tool services
  -> Channel 投影 (session/event -> Channel) -> 界面渲染 -> 输出（微信 / QQ / TUI）
```

关键论断（用户原话）：**「Channel 插件负责将 Agent 内部产生的事件流，转换并呈现给用户。同样，当用户在微信、QQ 或 TUI 中发送消息时，也是由对应的 Channel 插件接收，并将其转换为 Agent 能够理解的事件，从而驱动整个 Agent 的运行。」**

即：通道是 Cordis 插件树里的**前端/入口节点**，经 `inject` 与**事件系统**与 Agent 核心服务通信。**不是一条带 `send()` 的管子。**

### 17.2 已核实的 Cordis 真实 API（`cordis-rs-0.6.2` 源码）

```rust
// context.rs
pub fn effect<F>(&self, label, dispose) -> Result<EffectHandle>   // :315
pub fn provide<T>(&self, name, value) -> Result<EffectHandle>     // :343
pub fn on<F>(&self, name, listener) -> Result<EffectHandle>       // :416
pub fn emit(&self, ...)                                            // :476
pub async fn parallel(...)   // :485      pub async fn serial(...)  // :494
pub fn bail(...)             // :503      pub fn waterfall<F>(...)  // :512
pub fn plugin<P,C>(...) -> Fiber                                   // :539
pub fn inject<F>(&self, inject: Inject, callback: F) -> Fiber      // :562
```

五种派发模式（`emit` / `parallel` / `serial` / `bail` / `waterfall`）是 Cordis 事件模型的完整形态。

### 17.3 断点一：Kotlin 侧 `PluginContext` **没有 `emit`**

`core/domain/.../plugin/Plugin.kt:73-85`：

```kotlin
interface PluginContext {
    fun <T : Any> provide(key: String, service: T)
    fun <T : Any> inject(key: String): T
    fun effect(disposer: () -> Unit, label: String)
    fun on(event: String, handler: (Any) -> Unit)      // ← 只有订阅
}
```

**只有 `on`，没有 `emit`**，也没有任何派发模式。

后果直接命中用户架构：**Channel 作为「入口」，必须能发出事件来驱动 Agent——而现在它只能听，不能说。**

### 17.4 断点二：Agent 的事件流**太粗**，通道无物可投影

Rust 侧 `agent-native/src/cordis_bridge.rs` 已有事件总线，但只有两个事件：

```rust
ctx.on("agent.turn.start", ...)     // :86
ctx.on("agent.turn.end", ...)       // :96, :164
pub fn emit_turn_start(&self, scope: &str)   // :266
pub fn emit_turn_end(&self, scope: &str)     // :271
```

载荷**只有一个 scope 字符串**（`"companion:42"` / `"group:7"` / `"global"`），**没有任何内容**——没有文本、没有气泡、没有表情、没有 typing。

且它只在 Rust 内部流转，到 Kotlin 只经 `snapshot_json()` 输出诊断快照。

**所以「把 Agent 的事件流投影给用户」这件事，今天在数据上就不成立——事件流里没有可投影的东西。**

### 17.5 由此得到一个结构性诊断：**投影发生在错误的地方**

当前 `core/agent/.../AgentTurnReplyText.kt:13-27` 是通道侧**唯一压平点**：

```kotlin
object AgentTurnReplyText {
    fun resolve(events, finalText, finishedReason): String? {
        "sticker" -> event.text.takeIf{isNotBlank}?.let { "[$it]" }   // :17
        // ... joinToString("\n")                                       // :20
    }
}
```

即：**Agent 在 `core:agent` 里就把富事件（气泡 / 表情 / typing）压成了一个 String**，通道拿到的已经是扁平文本。

这解释了两个已核实的真实缺陷：

| 现象 | 证据 |
|---|---|
| 微信必须**反向还原**结构 | `WeChatChatBridge.kt:563-676` 从扁平文本里再解析出表情/分段 |
| **QQ 用户收到字面量 `[描述]`** | `feature:qqbot` 全模块无 StickerManager 引用，无人还原 |

**在用户的架构里，投影应当在通道内完成**：Agent 发出结构化事件，每个通道按自身能力投影。压平过早，就是把通道的表达能力提前砍掉了。

### 17.6 对已派发任务的判定

P3-2a 的 `ChannelAdapter` 契约（`start(): ChannelSession` + `ChannelSession.send(outbound)`）是**传输契约**，与上述架构**形状不符**：

- `send(outbound)` 把通道当被调用方；而投影模型里通道是**订阅方**——它监听 Agent 事件并主动呈现。
- 缺少 `on` / `emit` 两侧的事件契约。
- 缺少「Agent 想主动发一条」的请求/应答语义——在 Cordis 里那是 `waterfall` / `bail` 的用途，而不是一个公开的 `send()`。

**处置**：契约需按投影模型重写。已核实的事实（`PluginKind.ADAPTER` 生产零消费者、`pluginsOf` 零生产调用、无任何工具能触发通道发送）仍然成立，装配链路那一半的工作不作废。

### 17.7 修正后的落地顺序

1. **`PluginContext` 补 `emit` 与派发模式**（`core:domain`）——没有它，通道无法成为入口。
2. **把 Agent 的回合事件流做细并跨 UniFFI 暴露**（Rust + `core:agent`）——让事件里真的有内容。
3. **把投影从 `core:agent` 移到通道内**——Agent 发结构化事件，通道各自投影。
4. **通道插件订阅 + 发出事件**（`kind = ADAPTER`），装配链路由 P3-2a 那一半承担。

顺序不可颠倒：**1 与 2 是 3 与 4 的前提**——没有 `emit` 与可用的事件流，通道插件写出来也只是装饰。

---

## 18. 架构纠正后的复核（调度者亲自执行 + 两路只读核查）

### 18.1 本轮新增事实（均已独立复核，带 文件:行）

| 事实 | 证据 |
|---|---|
| 结构化回合事件**已经**跨 UniFFI，无需为保留 events 改 Rust/绑定 | `lianyu_agent.kt:7650-7659`（`AgentEvent(kind,text,extra)`）、`:7867-7892`（`AgentTurnResult.events` 反序列化）、`agent.rs:109-115` |
| 真正的压平点是 `AgentDialogueCoordinator` 的私有 `runTurn` 返回 `String?` | `AgentDialogueCoordinator.kt:253-298`，末行 `AgentTurnReplyText.resolve(...)` |
| Kotlin `PluginContext` 的监听表是**每插件实例私有**，不是共享总线 | `PluginContextImpl.kt:24-25,41-43,57-63`；`PluginHostImpl.kt:106` 每插件新建 `PluginContextImpl(HashMap(baseServices))` |
| Rust 侧确用真 cordis-rs（Context + Loader + emit），但 Kotlin 只有快照出口 | `cordis_bridge.rs:17-21,219-272`；`lianyu_agent.kt:2556` 仅 `corePluginSnapshot()` |
| `PluginServices.AGENT` 是**已声明但无人 provide** 的键 | 全仓仅定义命中；`YuNianApplication.kt:640-644` 只预置 APP_CONTEXT/TOOLS/CHANNELS |
| 群聊**不经过** `DialogueCoordinator` | `GroupChatViewModel.kt:904-967` 直接调 `AgentFacade` |
| 原生事件种类多于注释声明的四种 | `agent.rs:1229-1249` 还会产 `usage`/`reasoning`；`confirm_request.extra` 是工具参数 JSON |
| `runTurnStream` 的 `on_done` 是**每次模型 HTTP 流结束**，不等于整个多轮回合完成 | `native_gateway.rs:1021`；一个回合可多次 `send_stream`（`agent.rs:1190-1193`） |

### 18.2 对 P3-2a 的正式结论：**装配骨架保留，但不得验收为「通道插件化完成」**

保留（已验证可用）：

- `ChannelAdapter` / `ChannelSession` / `ChannelRegistry` / `MutableChannelRegistry` 契约；
- `ChannelRegistryImpl` 的「注册回查宿主 + 查询侧过滤装载态」不变量；
- `channel.qqbot` 进入默认蓝图与宿主注册；
- `core:agent` 183 项、`feature:qqbot` 47 项单测全绿（调度者强制复跑，见 18.3）。

未落地（不得声称完成）：

- **运行控制**：生产收发仍由 FGS/ViewModel 直连仓库驱动（`QQBotForegroundService.kt:57-72`、`QQBotViewModel.kt:77-90,320-330`），与插件 load/unload 无联动；
- **跨插件事件**：监听表私有，通道无法订阅 Agent 事件，Agent 也无法收到通道事件；
- **结构化投影**：通道拿到的仍是 `AgentTurnReplyText` 压平后的 String。

风险（下批必须处理）：

- `ChannelRegistryImpl.kt:96-100`：注册后 setup 抛异常时条目**残留在 `entries`**，占位阻止其他 pluginId 接管同 key。查询侧过滤只保证「不可见」，**不是回滚**（`ChannelRegistryHostWiringTest.kt:323-346` 只断言了不可见）；
- 停用插件不能只映射为 `host.unload` + `stopService`：微信 `WeChatPollingService.kt:88-102` 在已登录时会自重启并重排 Worker；QQ `QQBotRestartWorker.kt:9-11` 无登录/启用校验。需要**同一个 desired-enabled 闸门**，且不得破坏既有自恢复修复；
- `QQBotChannelPlugin.kt:22-23` 注释称「构造适配器不触碰仓库」，但 `setup()` 经 `adapterFactory()` 实际已构造 `QQBotMessageRepository`（`YuNianApplication.kt:659-665`）。注释与事实不符，须更正。

### 18.3 本轮验收证据（调度者亲自执行，非子代理自述）

```
.\gradlew.bat :core:agent:testDebugUnitTest :feature:qqbot:testDebugUnitTest --offline --console=plain
EXITCODE=0    BUILD SUCCESSFUL in 1m 38s   (95 tasks: 9 executed, 86 up-to-date)
core/agent  TEST-*.xml  17 files  tests=183  failures=0  errors=0  skipped=0
feature/qqbot TEST-*.xml 4 files  tests=47   failures=0  errors=0  skipped=0

.\gradlew.bat :app:compileDebugKotlin --offline --console=plain
GRADLE_EXIT=0  BUILD SUCCESSFUL in 3m 22s  (203 tasks: 16 executed, 187 up-to-date)

git diff --check  -> 退出 0（仅 CRLF 提示，无空白错误）
```

执行前已删除 `test-results` 目录，日志确认 `:core:agent:testDebugUnitTest` 与 `:feature:qqbot:testDebugUnitTest` 均为 **executed**（非 UP-TO-DATE），计数取自原始 `TEST-*.xml`。

### 18.4 由此确定的 P3-3 范围（由已锁定的技术抉择推出，不是新决策）

已锁定抉择第 1 条写明：「组合自由的落点在 **Kotlin PluginHost**；**不给 Rust cordis 新建 FFI 写入口**」。因此本批**不改 `agent-native/`、不重生成 UniFFI 绑定**：

| 批次 | 内容 | 依赖 |
|---|---|---|
| **P3-3a** | Kotlin 宿主级事件总线：`PluginContext.emit` + 派发模式；监听表提升到跨插件共享；预置 `PluginServices.AGENT` | 无 |
| **P3-3b** | 结构化回合输出：领域 DTO + `AgentEvent` 映射（在 `AgentConfirmGuard` 与安全过滤**之后**） | 无（与 3a 文件不相交） |
| **P3-3c** | 通道投影：QQ / 微信消费结构化事件 | 3b |

**明确不在本批**：把 Android 通道挂进 **Rust** Cordis 插件树（需要新增跨语言生命周期与事件桥，属另一量级）；实时 token 流式投影（现有 `StreamSink` 无 AgentEvent 回调，且提前外发会绕过整段输出过滤）。

### 18.5 仍未闭合

- **Q4（SDK 替换）——已定案：本批不做替换**（用户裁定：「先不做替换，优先完成投影契约」）。依据：`simbot-component-qq-guild` 面向 QQ **频道**，与本仓使用的群聊/单聊接口不匹配（对应组件应为 `simbot-component-qq`）；且 simbot 自带插件与事件模型，与 Cordis 定位重叠。替换只影响「字节怎么发出去」，不影响「发什么」，故不阻塞投影契约。
- **优先级（用户裁定）**：**投影契约优先** —— P3-3b（结构化回合输出）与 P3-3c（通道投影）优先于任何 SDK 相关工作。
- **并发构建禁令**：P3-3a 与 P3-3b 文件不相交可并行推进，但**同一时刻只允许一个 Gradle 构建**（见 4.1 节事故）。因此 P3-3b 按「只写实现与测试、不跑构建」派发，编译与单测由调度者在统一构建窗口串行执行。
- 停用/启用（组合自由）的真实语义需要 **desired-enabled 闸门**，与既有四层保活共存——需单独设计后落地。
---

## 19. P3-3a / P3-3b 验收（调度者强制复跑，2026-09-29）

### 19.1 本批交付

| 批次 | 内容 | 文件 |
|---|---|---|
| P3-3a | Kotlin **宿主级** Cordis 事件总线 + `PluginServices.AGENT` 预置 | `core/domain/.../plugin/Plugin.kt`（改）、`core/agent/.../plugin/PluginEventBus.kt`（新）、`PluginContextImpl.kt`（改）、`PluginHostImpl.kt`（改）、`YuNianApplication.kt`（改）、`PluginHostEventBusTest.kt`（新，24 用例） |
| P3-3b | 结构化回合输出契约 + `AgentEvent` 映射器 | `core/domain/.../dialogue/DialogueTurnSnapshot.kt`（新）、`core/agent/.../DialogueTurnMapper.kt`（新）、`DialogueTurnMapperTest.kt`（新，13 用例）、`DialogueCoordinator.kt`（改）、`WeChatModels.kt`（改）、`AgentDialogueCoordinator.kt`（改）、`WeChatDialoguePortImpl.kt`（改） |

**两个断点均已打通**：断点一（`PluginContext` 无 `emit`，且监听表是**每实例私有**→ 宿主为每个插件新建上下文，A 的 `emit` 物理上到不了 B）由「订阅表提升到宿主级 `PluginEventBus`」解决；断点二（事件流在 `core:agent` 内被压平成 String）由 `DialogueTurnMapper` 解决。

### 19.2 验收证据（调度者亲自执行，非子代理自述）

统一构建窗口（删净 `test-results` 后单次串行调用，4 个测试任务日志均**无** `UP-TO-DATE`）：

```
.\gradlew.bat :core:agent:testDebugUnitTest :feature:qqbot:testDebugUnitTest :feature:wechat:testDebugUnitTest :core:wechat:testDebugUnitTest :app:compileDebugKotlin --offline --console=plain
-> EXITCODE=0   BUILD SUCCESSFUL in 49s   243 tasks: 7 executed, 236 up-to-date
```

原始 `TEST-*.xml` 逐文件统计：

| 模块 | files | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| `core:agent` | 19 | **220** | 0 | 0 | 0 |
| `feature:qqbot` | 4 | 47 | 0 | 0 | 0 |
| `feature:wechat` | 9 | 40 | 0 | 0 | 0 |
| `core:wechat` | 11 | 88 | 0 | 0 | 0 |

`220 = 基线 183 + PluginHostEventBusTest 24 + DialogueTurnMapperTest 13`（两个新测试类已单独确认存在且 failures=0）。

`:app:compileDebugKotlin` 首次为 `UP-TO-DATE`（Gradle 按输入哈希判定，即当前源码树此前已成功编译），为杜绝歧义又执行了一次 `--rerun` 强制重编译：`EXITCODE=0`，`BUILD SUCCESSFUL in 1m 23s`，3 executed。

### 19.3 调度者复审（读代码，非采信回报）

- **范围纪律**：`git status` 与本批声称的 13 个文件完全一致，禁动文件零触碰；`agent-native/**` 未动（符合锁定抉择 #1：不给 Rust cordis 新建 FFI 写入口）。
- **5 个既有插件未被触碰**：mtime 为 `09-29 01:11` / `06:11`，均早于本批窗口（18:26 起）。它们在 `git status` 中的 `M` 来自更早的 P1/P2 工作。
- **`Plugin.kt` 的 491/182 diff 是行尾噪声**：该文件原为 `\r\r\n`（双 CR），编辑工具归一为 `\r\n`，`--ignore-cr-at-eol` 只忽略一个 CR 故未能滤除。用 `-w`（忽略全部空白）得到**真实净改动 321 增 / 12 删**，且 12 行「删除」**全部是 KDoc 注释被扩写替换，无一行代码被删**。
- **`PluginServices.AGENT` 接线正确**：`YuNianApplication.kt:614` 构造一次，`:615` 的 `ServiceRegistry` 工厂与 `:650` 的宿主预置是**同一个实例**；`AGENT` 的 KDoc 已从旧的「AgentFacade 能力」更正为 `DialogueCoordinator`。
- **失败分支合并行为等价**：`git diff` 逐字比对，`textTurnFailedReply()` / `visionTurnFailedReply()` 的文案与 `blocked` 值与原内联 `DialogueResult(...)` 完全一致（原单个 `?:` 同时兜住「回合没跑」与「无可见文本」，现拆为两处但指向同一结果）。
- **3b 兜底来源等价**：`outcome.replyText` 即 `AgentTurnReplyText.resolve(...)`（`:311`），其内部已做 `.ifBlank { finalText }`，故映射器收到的 `aiText` 已含 `finalText` 兜底且经 `sanitizeImageGen` + `checkOutputSafety`。
- **安全顺序成立**：投影点在 `agentConfirmGuard.drive`（`:303`）之后、`sanitizeImageGen`（`:139`）与 `checkOutputSafety`（`:142`）之后；映射器对每段进契约文本**再跑一遍**同一套清洗与过滤；全部 blocked 站点（`:78` / `:84` / `:98` / `textTurnFailedReply` / `blockedReply`）**都不传 `turn`**，快照为 null。

### 19.4 事实更正：一处**虚假的安全理由**（已修）

`QQBotChannelPlugin.kt:22-23` 与 `YuNianApplication.kt:664-665` 原文声称「仓库供应函数是惰性的，只在 `ChannelAdapter.start` 被调用时求值，因此装载本插件零影响」。**该理由是错的**：

- `QQBotChannelPlugin.setup()` 在 `:69` **立即**调用 `adapterFactory()`；
- App 传入的 lambda 当场执行 `QQBotServiceLocator.messageRepository(app)`，**装载期就构造 `QQBotMessageRepository`**（连带 `QQBotTokenStore` / `QQBotApiClient`）。

**结论仍成立、理由不成立**：已核实 `QQBotMessageRepository` 的构造体**没有 `init` 块**（`:39-71` 全为字段初始化），`connect()` 才是建连入口，故连接 / 心跳 / 重连 / 收发时序不受影响；但它**会**建立进程级单例与一个协程作用域。两处注释已改写为经核实的表述，并显式标注「此前说法是错的」。

> 教训：**虚假的安全理由比没有理由更危险**——后续改动会基于它推断。凡「零影响 / 无副作用」类声明，必须落到「构造体有没有 `init` 块、建连入口是哪个函数」这一级证据。

### 19.5 本批仍未验证 / 不在本批范围

- 无真实插件声明 `requires = [AGENT]`；`AGENT` 预置目前**只有单测覆盖**，端到端注入尚未被真实通道插件消费（属 P3-3c）。
- 通道侧尚未消费结构化事件：QQ / 微信仍在从 `replyText` 反向解析（属 P3-3c）。
- 未做真机 / 仪器化验证。
- `parallel` 的调度器选择（显式 `Dispatchers.Default`）是设计判断，非规范要求；3a 已用「同时在跑」峰值探针复现过原实现在单线程调度器上的退化为顺序执行。
---

## 20. P3-3c 验收（通道投影，调度者强制全量复跑，2026-09-29）

### 20.1 交付

| 文件 | 类型 | 说明 |
|---|---|---|
| `feature/qqbot/.../data/QQBotOutboundProjection.kt` | 新 | 纯投影（`internal object`），决策表 + 消费判定 |
| `feature/qqbot/.../data/QQBotOutboundProjectionTest.kt` | 新 | 11 用例 |
| `feature/wechat/.../data/WeChatTurnProjection.kt` | 新 | 纯投影（`internal data class`） |
| `feature/wechat/.../data/WeChatTurnProjectionTest.kt` | 新 | 9 用例 |
| `feature/qqbot/.../data/QQBotChatBridge.kt` | 改 | 换文本来源 + 修正过时注释 |
| `feature/wechat/.../data/WeChatChatBridge.kt` | 改 | 投影接线 + `extractStickerTags` 增 `pinnedStickerLabels` 形参 |
| `app/.../wechat/WeChatDialoguePortImpl.kt` | 改 | `stickerLabels` 改由事件投影 + 修正过时注释 |

`git status` 确认恰好 7 个文件，禁动文件零触碰（`core/domain` / `core/agent` / `agent-native` / `groupchat` / `qqbot|wechat` 的 `service/**` 与 `channel/**` / `gradle/**` 全未动）。契约一行未改。

### 20.2 验收证据（调度者亲自执行）

删净 4 个模块的 `test-results` 后，用 `--rerun-tasks` 强制全量重跑（零缓存）：

```
.\gradlew.bat :core:agent:testDebugUnitTest :feature:qqbot:testDebugUnitTest :feature:wechat:testDebugUnitTest :core:wechat:testDebugUnitTest :app:compileDebugKotlin --rerun-tasks --offline --console=plain
-> EXITCODE=0   BUILD SUCCESSFUL in 8m 23s   243 actionable tasks: 243 executed
```

**243/243 全部真执行**，无一个 `UP-TO-DATE`。原始 `TEST-*.xml` 逐文件统计：

| 模块 | files | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| `core:agent` | 19 | 220 | 0 | 0 | 0 |
| `feature:qqbot` | 5 | **58** | 0 | 0 | 0 |
| `feature:wechat` | 10 | **49** | 0 | 0 | 0 |
| `core:wechat` | 11 | 88 | 0 | 0 | 0 |

合计 **415 项测试 / 0 失败**。`feature:qqbot` 58 = 既有 47 + 新 `QQBotOutboundProjectionTest` 11；`feature:wechat` 49 = 既有 40 + 新 `WeChatTurnProjectionTest` 9。既有断言零删除、零弱化。

### 20.3 复审：防双发是**结构性**保证，不是约定

- QQ：`QQBotOutboundProjection.kt:72-73` 的 `result.turn?.let { textOf(it.events) } ?: result.replyText` —— elvis 左侧非空时 `replyText` **在语法上不可达**；桥接层此后所有出站只用该返回值。
- 微信：`WeChatTurnProjection.kt:48-63` 的 `if (turn == null) … else …` 严格互斥；`turn != null` 分支只读 `turn.events`。
- 两处都把「发什么」收敛成**单一返回值**，因此不存在「events 发一遍、replyText 再发一遍」的代码路径。这一点比测试更有说服力。

### 20.4 本批唯一的行为增量：微信表情发现口径（**待用户裁定**）

`turn != null`（成功回合的常态）时，微信表情**只认 `Sticker` 事件**；旧的「文本正则发现」与「文本里找不到标签就按 `StickerManager` 规则随机配一个」**双双关闭**。

**调度者补充查证的事实**（用于判断该增量是否合理）：

1. `agent-native/src/agent.rs:333` 的 `send_sticker` 工具描述：「tags 必须为 1~3 个简短情绪标签，且**必须从系统提示给出的可用标签中选择**」；`prompt_orchestrator.rs:630`：「你必须通过 `emit_bubble` 工具输出气泡；**表情包用 `send_sticker`**」。→ **通道路径的表情机制是工具调用**，不是内联 `[标签]` 文本。
2. 要求模型写内联 `[表情包描述]` 的提示词只存在于 `GroupChatViewModel.kt:778`，**只属于群聊**；群聊不走 `DialogueCoordinator` / `WeChatChatBridge`，本批未触碰。
3. `agent.rs:1811-1830` 与 `:421` 记录了 Rust 侧的明确教条与一个真机 bug：「空表情库且预选失败 → **绝不产出 sticker 事件**」，因为旧行为「按描述模糊匹配内置表情，于是恒发同一张默认图」。

→ **评估**：Kotlin 侧那条随机兜底是 Rust 决策引擎落地**之前**的遗留，与 Rust 侧「绝不发送模型未要求的表情」的既有教条相冲突。3c 的判断与运行时自身设计一致。

**但它确实是用户可见的变化**（微信会明显少发表情），因此列为待裁定项，不默认为既成事实。

### 20.5 仍未验证 / 不在本批范围

- `extractStickerTags` 的 pinned 分支无单测：它入口即调 `StickerManager.getInstance(context)`，而 `:feature:wechat` 测试源集只有 junit、无 Robolectric。已用纯投影测试覆盖其输入（标签来源），分支本身仅经编译验证。
- 未做真机 / 仪器化验证。
- 通道插件的运行时启停（`desired-enabled` 闸门）仍未设计。
---

## 21. 新需求：Agent 主动通过通道发消息（事实勘察，2026-09-29）

**需求（用户原话）**：「Agent 能直接调用微信、QQ 的 SDK，向微信和 QQ 发消息。要求微信和 QQ 成为 cordis 独立插件，用户在 yunian 里聊天时，如涉及『你给我QQ发条消息』『你在QQ群里@我一下』这种消息，则自主调用对应的消息通道。」

本节只记录**已核实的事实**，不构成设计决策（访谈尚未收敛，未进入规划）。

### 21.1 Agent 工具装配：运行期可变，通路已通

- `core/domain/.../AiTool.kt:77` `object ToolRegistry` 是**运行期可变**注册表（`ConcurrentHashMap` + `register`/`unregister`）。
- 标准 TOOL 插件范式见 `feature/coffee/.../LuckinCoffeeTools.kt:185-198`：`setup()` 里 `ctx.inject<ToolRegistry>(PluginServices.TOOLS)` → `registry.register(tool)` → `ctx.effect({ registry.unregister(tool.name) }, ...)`。
- **关键**：`AgentDialogueCoordinator.runTurn:280-286` **每回合**都调 `ToolRegistry.availableTools()`，因此插件注册的工具下一回合即被模型看见，卸载即失效。**无需新建 FFI 写入口。**
- 工具契约 `AiTool` 字段：`name` / `description` / `parametersJsonSchema` / `toolsets` / `appLocalOnly`(:32 默认 false) / `isAvailable()`(:39 默认 true) / `execute(argumentsJson): String` / `systemPrompt()` / **`requiresConfirmation`(:45 默认 false)** / `summarizeArguments()`。

→ `requiresConfirmation` 是现成的确认门开关，无需发明新机制。

### 21.2 渠道隔离：现成两道闸，正好满足需求

存在**两条互相独立**的 Agent 回合路径：

| 路径 | 可见性闸 | 执行闸 | 用于 |
|---|---|---|---|
| `feature/chat/.../ChatGenerationManager.kt:710, :737` + `:1010-1012` | `availableTools(includeAppLocal = true)` | `AgentToolHost(..., allowAppLocalTools = true)` | **App 内单聊** |
| `feature/groupchat/.../GroupChatViewModel.kt:884, :911, :946` | `includeAppLocal = true` | `allowAppLocalTools = true` | App 内群聊 |
| `core/agent/.../AgentDialogueCoordinator.kt:280, :294` | `availableTools()`（= false） | `AgentToolHost(context)`（= false） | **QQ / 微信 / 通话 / 无人值守 Worker / 委派 / 评测** |

- `AgentToolHost.kt:41-53` 的 `allowAppLocalTools` 是**不可变构造参数**，且 `AgentToolHost` **刻意不接受 `contextJson`**（`:339`）——模型自述渠道（哪怕写 `"channel":"app"`）不作为授权依据。
- 执行侧第二道闸：`AppLocalToolGate` / `dispatchRegistryTool`（`:310-349`），未开闸时 `appLocalOnly` 工具**不调用其 execute**，返回稳定拒绝文本。

→ **把发送工具标记为 `appLocalOnly = true`，即可做到「只在 App 内单聊/群聊可见可执行，在 QQ/微信会话里连名字都看不到」**，无需新增机制。这正好满足「用户在 yunian 里聊天时自主调用」，同时阻断「微信联系人借模型之手操纵 QQ 通道」。

### 21.3 QQ 出站：REST 端点已支持任意目标，缺口在仓库层

- `feature/qqbot/.../data/network/QQBotRestApi.kt:19-27` 已有：`@POST("v2/users/{openid}/messages")` 与 `@POST("v2/groups/{group_openid}/messages")`（`:55-63` 另有一对）。
- **但仓库层只暴露被动回复**：`QQBotMessageRepository.kt:131` `suspend fun sendTextMessage(event: QQInboundEvent, text: String)` —— 入参是**入站事件**，内部 `msgId = event.raw.id`(:137) + `msgSeq = msgSeqCounter.getAndIncrement()`(:138)。
- 全仓**没有**面向目标 ID 的发送方法。

### 21.4 QQ 目标标识：用户 openid 被取到后丢弃，群 openid 从未持久化

- `feature/qqbot/.../ui/QQBotViewModel.kt:196-204` 绑定完成分支：`val openid = pollBody.data.userOpenid`（:202）→ 下一行只 `tokenStore.saveAccount(QQBotAccount(appId, secret))`（:204）。**`openid` 赋值后从未使用。**
- `QQBotTokenStore.kt:30-40` 持久化的 11 个键：autoReply / notifyEnabled / forwardEnabled / defaultCompanionId / userCompanionMap / customBotName / accessToken / tokenExpireAt / sessionId / lastSequence / **botOpenId（机器人自己的）**。**没有用户 openid，没有任何 group_openid。**
- 入站模型侧字段是有的：`QQBotModels.kt:55-56` `user_openid` / `member_openid`，`:76` `:112` `group_openid`。

→ 「给我QQ发条消息」在技术上可行，但需要先补「目标标识的来源与持久化」；**平台硬约束**是 openid 只能来自机器人见过的人/群，不能凭空构造。

### 21.5 可用的持久化手段

- `AppMetaStore`（`core/database` 的 `app_meta` KV 表，`getString`/`putString`）是**schema 冻结下的既定新增路径**（`AppDatabase.kt:150`：「自 v41 起，新增功能走 AppMetaStore(KV) 或 ExtJson，禁止加表/字段/索引」）。`CapabilityGrantStoreImpl.kt:45-54` 是既有先例。

### 21.6 尚未查明（交给并行审计）

- 微信侧的发送目标如何确定、能否主动发起会话。
- QQ 群消息是否支持 @ 某人（代码是否实现 / 协议需要什么字段）。
- 主动发送的前置状态（登录 / WebSocket 连接）与失败可见性。
### 21.7 微信侧：传输层已面向任意目标（补充勘察）

- `core/wechat/.../transport/WeChatTransportPort.kt:4`：`suspend fun sendText(toUserId: String, text: String, contextToken: String?)` —— **目标就是参数**，且 `contextToken` 可空。
- 实现链：`IlinkClientManager.kt:244` → `SdkWeChatTransport.kt:11` → `WeChatMessageRepository.kt:101` `sendTextMessage(...)` / `:127` `enqueueTextOutbound(...)`。
- `feature/wechat/.../ui/WeChatViewModel.kt:293` 已在用 `repository.sendTextMessage(toUserId, text)`——**微信侧早已能对指定 `toUserId` 发送**，无需像 QQ 那样先补目标持久化。

→ **两侧不对称**：微信是「面向目标」的传输契约；QQ 是「面向入站事件」的回复契约。

### 21.8 QQ 主动发送与出站 @：模型层已支持前者，后者不存在

`feature/qqbot/.../data/model/QQBotModels.kt:88-95` 的 `SendTextRequest` 字段：

```
content / markdown / msg_type / msg_id / msg_seq / message_reference
```

- **`@SerialName("msg_id") val msgId: String? = null`（:92）**——`msg_id` 可空，说明**主动发送在请求模型层已经是结构化支持的**，只是 `QQBotMessageRepository` 从不省略它（:137 恒传 `event.raw.id`）。缺口纯粹在仓库层。
- **请求体里没有 `mentions` / `at` / `at_user` 任何字段**（`SendMediaRequest` :118-124 同样没有）。全仓出站路径也无 @ 实现；`QQBotModels.kt:83` 的 `mentions: List<QQUser>?` 与 `QQInboundEventMapper.kt:74` 的 `wasBotMentioned` 都是**入站**语义。
- **平台是否支持出站 @：未能核实。** 官方文档 `bot.q.qq.com/wiki/...` 是 VuePress 单页应用，HTTP 返回的 HTML 不含正文（两个候选 URL 均返回同一个 23756 字节空壳）；搜索引擎在该查询下只返回 QQ 官网/邮箱等无关结果。

→ **用户例子「你在QQ群里@我一下」中的 @ 部分当前不可行**，需先确认平台能力；「发条消息」部分可行。此项列为**待确认事实**，不得在设计里默认成立。

### 21.9 三条前置状态约束（尚未查证，规划前需补）

- QQ 主动发送是否需要 WebSocket 已连接 / token 有效；未就绪时的失败表现。
- 微信发送是否需要 ilink SDK 已登录、轮询服务在跑。
- 两侧出站失败对用户是否可见（当前是否只是记日志）。
---

## 22. P4 批次：Agent 主动发送（规划与纠正，2026-09-29）

### 22.1 访谈收敛（用户裁定）

| 问题 | 裁定 |
|---|---|
| Q1 收件人 | **用户本人**（QQ/微信机器人协议里都有「绑定宿主 id」）；微信不支持给任意他人发，QQ 最新 SDK 支持 |
| Q2 群 | 属于 QQ 侧；QQ Bot SDK 能列出机器人所在的群聊 |
| Q3 授权 | **默认需确认，用户在「工具授权」页显式授权后允许自主** |
| Q4 范围 | **先做 A**：先交付「Agent 能发」+ 微信补插件壳，通道插件的运行时启停单独一批 |

### 22.2 调度者的错误与纠正（重要）

**第一版 P4-1 任务书要求「扩 `ChannelOutbound` 加 `Proactive`，让发送工具走 `ChannelSession.send()`」——这直接违背用户已记录的架构裁定 §17.6。**

§17.6（本文件 :680-688）原文：

> P3-2a 的 `ChannelAdapter` 契约（`start(): ChannelSession` + `ChannelSession.send(outbound)`）是**传输契约**，与上述架构**形状不符**……缺少「Agent 想主动发一条」的请求/应答语义——在 Cordis 里那是 **`waterfall` / `bail`** 的用途，**而不是一个公开的 `send()`**。
> **处置**：契约需按投影模型重写。

该任务书**从未执行**（`send_message` 返回 `not found`；`list_agents` 只有 lead；19:28 核查时最近改动仍是 19:00 的 P3-3c 窗口，`ChannelAdapter.kt` 与 `QQBotMessageRepository.kt` 均无改动）→ **零损害**，已按投影模型重写并重新派发。

**教训**：派发前必须回读本文件里与该模块相关的既有裁定。§17.6 早就写明了「Agent 想主动发一条」的正确形状，我却在 21 节勘察后直接跳到了 `send()`。

### 22.3 两路只读审计的关键发现（已核实，带行号）

#### 必须遵守的约束

| # | 约束 | 证据 | 对本功能的影响 |
|---|---|---|---|
| C2 | **确认门在无 UI 通道是死路** | `core/agent/.../AgentConfirmGuard.kt:41-62`，`MAX_AUTO_REJECT = 3`，fail-closed 自动拒绝 | `requiresConfirmation = true` 的工具在 QQ/微信上**永远被拒**。**不能用它做渠道隔离** |
| C3 | **`appLocalOnly` 才是渠道隔离机制**（双重闸） | 可见性 `AiTool.kt:135-139`；执行 `AgentToolHost.kt:347`（`:52` 不可变构造参数，`:339` 刻意不收 contextJson）。先例 `core/agent/.../tools/UserProfileTool.kt:44` | **发送工具标 `appLocalOnly = true`** → 只在 App 内单聊/群聊可见可执行，QQ/微信连名字都看不到 |
| C6 | `isAvailable()` 有 **30s TTL + 60s flake 容忍** | `AiTool.kt:84/:87` | **不得**用它表达「通道是否已连接」；必须在 `execute()` 内实时再判 |
| C7 | **工具名即对外契约，不得改** | `core/domain/.../plugin/Plugin.kt:31-32` | 工具名一次定死 |
| C8 | schema 冻结 | `AppDatabase.kt:150`；先例 `CapabilityGrantStoreImpl.kt:45-54` | 目标标识持久化必须走 `AppMetaStore`(KV) 或模块自有 DataStore |
| C9 | `requiresConfirmation` 只在装配期有消费者 | `AgentFacade.kt:651-656` | 必须经 `deriveToolCategory` 折成 `COMMERCE` 才生效 |
| C10 | `toolDefinition(tool, category)` 显式 category 可绕过确认门 | `AgentFacade.kt:635-645` | 必须走 `toolDefinitionsFor` |
| C12 | `ChannelRegistryImpl.kt:96-100` 已知残留缺陷 | 见本文件 §18.2 | 只保证不可见、不是回滚 |

#### 新增确认的事实

- **装配通路零 FFI**：Rust 侧 `register_session_tools` 每回合 `map.clear()` 后重建（`agent-native/src/agent.rs:988` + `:312-318`）；Kotlin 侧三个装配点每回合读取。
- **`QQBotMessageRepository.kt:62` 的 `sendMutex` 全仓零使用** → 出站无串行化；`msgSeqCounter`(:64) 是进程级全局自增，无重置、不持久化。
- **微信侧主动发送已在生产**：`WeChatProactiveSync.enqueue(companionId, messageId, finalContent)`（`core/domain/.../wechat/WeChatProactiveSync.kt:22-42`）被 5 处调用（`ChatGenerationManager.kt:1454`、`AiResponseFinalizer.kt:294`、`ImageGenServiceImpl.kt:155`、`GroupChatViewModel.kt:1182`、`CompanionMessageWorker.kt:262`）。
- **但微信的失败语义是坏的**：`WeChatOutboundPortImpl` 未登录/无映射时**静默返回 `SKIPPED`（空串）**，调用方分不清「成功入队」与「静默跳过」；且送达受 **24h `context_token`** 硬约束（`WeChatOutboxCoordinator.kt:227-230/:265-274/:350-356`），缺失/过期 → **non-retryable 判死、不重试**；不指定 `wechatUserId` 时会**广播**给该伴侣绑定的所有微信用户（`WeChatOutboundPortImpl.kt:130-137`）。
- **`_tmp_atlr_orig.kt`（仓库根）** 是一份 `AgentToolHost` 旧副本（含现行版本没有的 `requiresConfirmation && confirmationGate != null` 逻辑），疑似未清理的临时文件。

### 22.4 修正后的 P4-1 设计（已派发）

| 部分 | 内容 |
|---|---|
| A | `core:domain`：通道无关的出站请求/结果类型（`channelKey` / `target: String?`（null = 绑定宿主）/ `text`）。结果**不得**表达「对方已收到」（两通道 `deliveryReceipt` 都是 false） |
| B | `core:agent`：一个 `PluginKind.TOOL` 插件拥有发送工具（`appLocalOnly = true` + `requiresConfirmation = true`），`execute()` 经 `ctx.bail(...)` 把请求**派发为插件事件**；文本先过 `ContentFilter.checkOutputSafety`；**无订阅者时如实失败** |
| C | `feature:qqbot`：`QQBotChannelPlugin.setup()` 用 `ctx.onBail(...)` **订阅**该事件（`ctx.effect` 注销），**不匹配自己的 channelKey 时必须放行**；实现面向目标的主动发送（省略 `msg_id`）；持久化用户 openid（绑定流程 :202 那个被丢弃的值 + 入站补齐） |
| D | 联网核实 **QQ 平台是否支持省略 `msg_id` 的主动发送**——本任务唯一阻塞性不确定项。核实不到就明确写「未能核实」，**禁止编造端点/字段名** |

### 22.5 尚未处置

- **§17.6 的「契约需按投影模型重写」本身**（重写 `ChannelAdapter` / `ChannelSession`）不在本批范围内，本批**不动** `ChannelAdapter.kt`。
- 微信通道插件 + 微信发送订阅（P4-2）未派发。
- `_tmp_atlr_orig.kt` 未清理。
- `ChannelRegistryImpl` 的 setup 失败残留缺陷未修。
---

## 23. 阻塞性核查（2026-09-29）——⚠️ 23.1–23.3 的结论已被 §23.7 推翻，权威事实见 §23.8

### 23.1 权威来源

官方文档站点 `bot.q.qq.com/wiki/` 是 VuePress SPA，HTTP 返回的 HTML 不含正文（多个候选 URL 均返回同一个 23756 字节空壳），**搜索引擎也取不到**。

绕行成功：文档源码仓库 **`github.com/tencent-connect/bot-docs`**（README 自述「本仓库是 QQ 机器人文档项目，基于 vuepress 构建。对应文档网站是 https://bot.q.qq.com/wiki/」）。默认分支 `main`，`pushed_at = 2025-04-21T09:12:22Z`。

原文：`docs/develop/api-v2/server-inter/message/send-receive/send.md` 第 1-6 行：

```
# 发送消息

::: danger 注意
主动推送能力于 2025 年 4 月 21 日起不再提供，接口调用时会收到错误信息。
公告信息：[【关于QQ机器人消息推送策略调整通知】](https://q.qq.com/miniapp#/news/detail/974e66a946a5e54c441ca983585a7aab)
:::
```

该公告位于页面顶部、覆盖全部四个场景（QQ单聊 / QQ群聊 / 文字子频道 / 频道私信）。

### 23.2 剩余能力（同文件第 14-34 行）

| 场景 | 主动消息 | 被动消息（回复类） |
|---|---|---|
| QQ 单聊 | 每月 4 条（**现已撤回**） | 有效期 **60 分钟**，每条消息最多回复 **5 次** |
| QQ 群聊 | 每月 4 条（**现已撤回**） | 有效期 **5 分钟**，每条消息最多回复 **5 次** |
| 文字子频道 | 每天每子频道 20 条 | 有效期 5 分钟 |
| 频道私信 | 每天每用户 2 条 / 每天累计 200 条 | 有效期 5 分钟 |

另：「QQ 用户可以在 QQ 客户端主动设置是否接收机器人发送的主动消息，如果设置了关闭，主动消息一律发送失败。」（第 15 行）

### 23.3 对需求的直接影响

用户答 Q1(b) 时说「微信目前不支持，但是 QQ 的最新 SDK 已经支持了」——**官方文档显示情况相反：QQ 的主动推送已于 2025-04-21 撤回**，距今约 17 个月。

因此：

- **「你给我QQ发条消息」只能在被动回复窗口内成立**：用户本人最近 60 分钟内给机器人发过消息（单聊）才发得出去；群聊窗口只有 **5 分钟**。
- 不带 `msg_id` 的请求 = 主动推送 = **会被平台拒绝**。`SendTextRequest.msgId` 类型可空（`QQBotModels.kt:92`）**只说明客户端模型允许**，不代表平台接受。
- **微信同理**：`context_token` 24 小时硬门（`WeChatOutboxCoordinator.kt:350-356`）。

→ **两个通道本质都是「只能回复」，没有一个能冷启动一段新会话。** 这不是本仓缺陷，是两侧平台的共同约束。

### 23.4 由此得到的设计修正

「目标标识持久化」应当存的是 **`(目标 → 最后一条入站 msg_id + 时间戳)`**，而不只是 openid：
- 发送 = 以该 `msg_id` 为锚的**被动回复**；
- 超出窗口（单聊 60 分钟 / 群聊 5 分钟）→ **如实失败并说明平台原因**，不得假装成功；
- `msg_seq` 与 `msg_id` 联合使用，相同 `msg_id + msg_seq` 重复发送会失败（第 68 行）——既有进程级自增计数器恰好满足此约束。

### 23.5 另外两条易漏的平台要求（同文件）

- 第 38 行：「消息内容包含 URL 的说明：如开发者需要在消息内容发送含有 url 信息的消息，请先在 q.qq.com 后台-开发设置-消息URL配置 预先配置，**否则会发送失败**。」→ 出站文本含 URL 时必须预期失败。
- 第 39 行：「调用发消息 http 接口的 timeout 建议设置最低为 5 秒，避免出现实际消息已发送成功，但没接收到同步的结果返回。」→ 既有 HTTP 超时配置需对照检查（**未核实本仓 OkHttp 超时值**）。

### 23.6 未能核实

文档仓库自 2025-04-21 起未再更新。**「2025-04-21 之后 QQ 是否恢复过主动推送能力」无法核实**——站点是 SPA、公告链接是 miniapp。若用户掌握更新的信息，需要用户提供来源。
### 23.7 ⚠️ 本节 23.1–23.3 的结论**已被推翻**（同日修正，调度者的错误）

**错误**：我在 23.1–23.3 依据 `github.com/tencent-connect/bot-docs` 判定「QQ 主动推送已被平台撤回」，并据此告诉用户「你的需求前提有一半不成立」。

**根因**：该 GitHub 仓库是**过期镜像**——`pushed_at = 2025-04-21`，之后再未更新。而**线上文档仍在活跃维护**：资源版本 `v1.33.0`（`qq-ai.cdn-go.cn/web/bot-docs/-/v1.33.0/`），变更记录页最后更新 **2026-09-16**，条目含 20260810 / 20260812 / 20260903 / 20260916。

**我漏掉的关键**：该站是 VuePress SPA，但**页面正文就内联在返回的 HTML 里**（只是被标签包裹）。用户直接给出 autogen 参考页 URL 后，剥掉标签即可读到全文。此前我因为看到「SPA 空壳」就放弃了这条路径，**没有尝试提取**。

**用户是对的，我是错的。**

### 23.8 线上文档的权威事实（2026-09-16 版，逐字摘自 autogen 参考页）

来源：`https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_messages.post.html` 与 `.../v2_groups_group_openid_messages.post.html`

#### 单聊 `POST /v2/users/{user_openid}/messages`

- 被动消息有效时间 **60 分钟**，每个消息最多回复 **4 次**（注：过期仓库写的是 5 次）
- **主动消息频控规则**（→ 主动消息**是支持的**）：
  - Bot 维度（发送方）：企业认证/个人身份证认证 **10/qps**；未认证 **5/qps** 且 **30/qpm**
  - 单关系维度（接收方）：**20/qpm**，每个好友 1 天最多接收 **1000** 条
- **互动召回消息**：「在用户主动与机器人对话之后，机器人在未来 **30 天**内可下发互动召回消息给用户……每个周期内可下发一条。分别为：当天、1-3 天、3-7 天、7-30 天，合计 **4 个周期**。在发消息接口中使用 **`is_wakeup`** 字段声明使用该能力。」
- 请求体字段：`msg_type` / `content` / `markdown` / `keyboard` / `msg_id` / `event_id` / `msg_seq` / `media` / `message_reference` / **`is_wakeup`** / `input_notify`
  - `msg_id`：「被动回复的消息 ID。从 `C2C_MESSAGE_CREATE` 等事件的 `d.id` 获取，**5 分钟内有效**」
  - `event_id`：「被动回复的事件 ID……**与 msg_id 二选一**」
  - `is_wakeup`：「指明发送消息为互动召回消息，**与 msg_id，event_id 互斥使用**」
- 接口频率限制 **100 QPS**

#### 群聊 `POST /v2/groups/{group_openid}/messages`

- 被动消息有效时间 **5 分钟**，每个消息最多回复 **5 次**
- **主动消息频控规则**：Bot 维度认证 **60/qpm**、未认证 **30/qpm**；单关系 **20/qpm**，每个群 1 天最多 **1000** 条
- **群聊页不含 `is_wakeup`**（已逐字核验：`indexOf("is_wakeup") < 0`）→ 互动召回仅限单聊
- **群聊页不含任何 @ / mention / 艾特 字段**（唯一的 `@` 出现在键盘按钮说明「自动在输入框插入 @bot」）→ **出站 @ 在群聊 API 中不存在**
- 「注意: 群消息不支持流式参数」

### 23.9 对已落盘实现的影响：**实现是对的**

P4-1 落盘的 `QQBotMessageRepository.sendProactiveText` 选择「省略 `msg_id`」，其 KDoc 引用「官方文档：`msg_id` 为「否（非必填）」」——**依据正确，结论正确**。

我当时判定它「实现了已被撤回的能力」，同样是基于过期仓库的误判。**该实现不需要回退。**

### 23.10 顺带核实（无需改动）

- 变更记录 20260810：「接口调用域名统一为 `api.bot.qq.com`」。本仓 `QQBotApiClient.kt:153-154` 的 `AUTH_BASE_URL` / `API_BASE_URL` **已是** `https://api.bot.qq.com/`，且注释明确引用该次变更 → **本仓在跟踪线上文档**。
- 变更记录 20260903 新增「群管理接口 / 群成员管理」：获取群成员列表、获取群成员信息、群成员批量移除、群黑名单查询/操作。

### 23.11 仍未能核实

**「列出机器人所在的群聊」的 HTTP 端点未能找到。** 按 autogen 命名规则试了 `v2_users_me_groups.get` / `v2_bot_groups.get` / `v2_groups.get` / `v2_users_me_guilds.get` 四个候选路径，**全部返回兜底页**（「启动接入」）。`/wiki/develop/api-v2/server-inter/group/` 是 2023-11-22 的旧页，只讲「进群破冰消息配置」。→ 用户答 Q2 时说的「QQ Bot SDK 已经能列出来 QQBot 所在的群聊」，**需要在 HTTP API 之外找依据**。
---

## 24. 权威平台事实终版 + P4-1 双派发撞车（2026-09-29）

### 24.1 官方文档的正确读法（用户两次给 URL 才走通）

- `bot.q.qq.com/wiki/` 是 VuePress SPA，但**正文就内联在返回的 HTML 里**（被标签包裹）。剥掉标签即可读全文。资源版本 `v1.33.0`（`qq-ai.cdn-go.cn/web/bot-docs/-/v1.33.0/`）。
- **GitHub 镜像 `tencent-connect/bot-docs` 是过期源**（`pushed_at = 2025-04-21`），据此得出的「主动推送已撤回」结论**完全错误**，已在 §23.7 作废。
- 官方概述页 `https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html`（自述最后更新 **2026-07-21**）是权威汇总。

### 24.2 主动消息：无条件支持（逐字）

概述页「主动消息与被动消息」表：

| 类型 | 特征 | 说明 |
|---|---|---|
| **主动消息** | **无任何条件** | 机器人主动触达用户，用户可在客户端关闭「允许主动发送」开关，关闭后主动消息将发送失败 |
| 互动召回消息 | `is_wakeup=true` | 用户主动与机器人对话之后每个周期内可下发 1 条召回消息 |
| 被动消息（回复用户） | 携带 `msg_id` | 对用户消息的回复 |
| 被动消息（响应事件） | 携带 `event_id` | 对事件的回复 |

→ **省略 `msg_id` 即主动消息，平台无条件支持。** 用户答 Q1(b)「QQ 最新 SDK 已经支持」是**正确的**。

### 24.3 频率与时效（概述页逐字）

| 场景 | 被动消息有效期 | 每条可回复次数 |
|---|---|---|
| 单聊 | **60 分钟** | **4 次** |
| 群聊 | **5 分钟** | **5 次** |
| 频道 | 5 分钟 | — |

主动消息（HTTP 接口）：

| 场景 | 认证 | Bot 维度 | 单关系维度 | 每日上限 |
|---|---|---|---|---|
| 单聊 | 企业/个人认证 | 10/qps | 20/qpm | 1000 条/用户 |
| 单聊 | 未认证 | **5/qps & 30/qpm** | 20/qpm | 1000 条/用户 |
| 群聊 | 企业/个人认证 | 60/qpm | 20/qpm | 1000 条/群 |
| 群聊 | 未认证 | **30/qpm** | 20/qpm | 1000 条/群 |

互动召回（**仅单聊**）：用户对话后 **30 天**内、分「当天 / 1-3 天 / 3-7 天 / 7-30 天」**4 个周期各 1 条**，用 `is_wakeup` 声明，**与 `msg_id`、`event_id` 互斥**。

### 24.4 另外三条（概述页新增，本批会用到的）

- **消息去重**：「相同 `msg_id` 可能多次推送，请结合 `msg_seq` 去重。被动回复时，相同的 `msg_id + msg_seq` 重复发送会失败，**可递增 `msg_seq` 实现对同一消息的多次回复**。」→ 仓库既有 `msgSeqCounter` 做法正确。
- **撤回**：「机器人可撤回自己发送的消息（**发送超过 2 分钟不可撤回**）」。
- **富媒体**：需先上传取 `file_info`（分片上传推荐 / 整文件上传），`file_info` 有 `ttl`，**单聊与群聊上传接口不互通**。

### 24.5 仍未找到：列出机器人所在的群聊

概述页「收发场景」表只有：单聊（`C2C_MESSAGE_CREATE`）、群聊（`GROUP_AT_MESSAGE_CREATE` / `GROUP_MESSAGE_CREATE`）、频道。**没有任何「列出机器人所在群聊」的接口。** 按 autogen 命名规则试的 4 个候选路径全部返回兜底页（§23.11）。变更记录 20260903 的「群成员管理」需要**先知道 `group_openid`**。

→ 结论：`group_openid` **只能来自入站事件**（同 `user_openid`）。用户答 Q2 的说法需要用户提供具体入口，否则「发到指定群」只能由用户在 App 内粘贴 `group_openid`。

### 24.6 P4-1 双派发撞车（调度者的操作事故）

**经过**：
1. 我派发 P4-1（`fab60f8c`），任务书要求「扩 `ChannelOutbound` 加 `Proactive`、走 `ChannelSession.send()`」——**违背 §17.6**。
2. 我误判它「从未运行」（依据是 `send_message` 返回 `not found` + `list_agents` 只有 lead），**又派发了修正版 `ad5e9626`**。
3. 事后证实 `not found` 对 `subagent` 子代理是**常态**，不表示已结算 → **`fab60f8c` 一直活着**，19:30–19:36 持续写入，走的正是被禁止的形状。
4. `ad5e9626` 表现很好：发现工作区已有半成品、**拒绝在有并发写入者时动手**、零写入、回报并请我裁决。它还**独立核实了 QQ 主动消息可行**，结论与我一致。

**关键操作事实（务必记住）**：

- `tools.subagent` 派发的子代理：**能向调度者发消息，但调度者无法回它**（`send_message` → `active teammate "<id>" not found`）。`list_agents` 也看不到它们。`job_list` 里也没有。
- **因此「子代理提问」= 单向。** 一旦子代理阻塞提问，唯一出路是**等它结算后重新派发**，不能就地答复。
- `not found` **不能**用来判断子代理已结束。

**教训**：在**无法确认前一个子代理已终止**之前，不得对同一批文件派发第二个子代理。判断依据只能是「文件 mtime 静止 + 无 java 进程」，而不是 `send_message`。

### 24.7 已下的裁决：走 (A) 事件契约

裁决内容（因无法回复 `ad5e9626`，改为等两个写入者都结算后重新派发）：

- **保留**：`QQBotProactiveRequests.kt`、`QQBotMessageRepository.sendProactiveText`、`HOST_USER_OPENID_KEY` 与 openid 落库、`QQBotChannelSendTool` 的全部安全设计（`appLocalOnly=true` + `requiresConfirmation=true`、`QQBotChannelToolHost` 接缝、默认即真 `ContentFilter.checkOutputSafety`、前置状态如实失败、`delivery_receipt` 恒 false）。
- **摘除**：`ChannelAdapter.kt` 里的 `ChannelOutbound.Proactive` + `TargetKind`，恢复 P3-2a 形态。**注意该文件是未跟踪新文件**（`git status` 显示 `??`，`git show HEAD:` 取不到），**没有 HEAD 版本可回退**，必须手工摘除。它目前**零生产读取者**，摘除零风险。
- **恢复** `QQBotChannelAdapter.proactiveSend = false` → `QQBotChannelPluginTest.kt:225` 的 `assertFalse` 保持有效，**不需要改测试**。
- **改造**：工具 `execute()` 不再直连 `host.sendProactive()`，改为经 `ctx.bail(...)` 把出站请求**派发为插件事件**；`QQBotChannelPlugin.setup()` 用 `ctx.onBail(...)` 订阅并完成发送，`ctx.effect` 注销；**不匹配的请求必须 `next(...)` 放行**；**无订阅者时如实失败**。
- **新增** `core:domain` 通道无关的出站请求/结果类型（纯数据类、零第三方依赖、**新文件**，不改既有契约文件）。

### 24.8 待办（本批不做，记录在案）

- `ChannelCapabilities.proactiveSend` 在 QQ 上恢复为 `false` 后**事实上有过时之嫌**（QQ 确实支持主动消息）。该位描述的是「`ChannelOutbound.Proactive` 这个出站变体能否跑通」，变体摘掉后它就是 `false`。**待 §17.6 的契约重写批次一并处置。**
- 群聊出站 @ 在 API 中不存在（§23.8）。
- 「列出机器人所在群聊」的入口未找到（§24.5）。

### 23.12 教训

**GitHub 镜像 ≠ 权威源。** 官方文档站点即使看起来是 SPA，正文也可能内联在 HTML 里——**先剥标签看一眼，再决定放弃**。这一轮我因为「看到 SPA 空壳」就转向了一个冻结的镜像，得出了与事实相反的结论，还据此向用户断言其需求前提不成立。
### 24.9 P4-1 中间态审查（19:39，调度者只读复核，**不构成验收**）

裁决 (A) 已被执行，且实现质量高于任务书要求：

| 项 | 状态 |
|---|---|
| `ChannelAdapter.kt` 摘除 `Proactive`/`TargetKind` | ✅ 只剩 `ChannelOutbound`(:142) 与 `Text`(:154) |
| `QQBotChannelAdapter.proactiveSend` | ✅ 恢复 `false`(:91)，且 :45 KDoc **保留**「QQ 主动发送不走这一位」的事实说明（比指令更准确） |
| `ChannelOutboundEvents.kt`（新，core:domain） | ✅ 通道无关契约；选 `bail` 并给出论证：`waterfall` 是「把监听器包在 inner 外面」的过滤器链语义，本请求是一次性路由，且 `isBailed==false` 天然表达「无人认领」 |
| `ChannelSendTool.kt`（新，core:agent） | ✅ `appLocalOnly=true` + `requiresConfirmation=true`；出站文本先过 `ContentFilter.checkOutputSafety`；无人认领 → `NoChannel` 如实失败；`delivery_receipt` 恒 false |
| `MessageSendPlugin.kt`（新，core:agent） | ✅ **发起方是独立插件**，且 KDoc 论证优于任务书：通道卸载后工具仍在、请求「没人认领」→ 明确失败，用户能分清「没启用通道」与「工具坏了」；若把发起方塞进通道插件，工具会随通道消失，反而分不清 |
| `QQBotChannelPlugin` 订阅 | ✅ `ctx.onBail(REQUEST)`：不匹配 → 返回 `null` 放行；匹配 → `handleOutboundRequest`。KDoc :53 明确「**不启动连接、不发心跳、不碰重连、不改变任何消息时序**」 |
| 失败信息 | ✅ 如实：「QQ 机器人未配置账号：请先完成扫码绑定」「QQ 通道当前未连接（状态 X）」 |

**同步/异步边界的处理值得记录**：`PluginContext.bail` 是同步函数而发送是挂起的，实现用 `runBlocking` 收敛；`CancellationException` 照常上抛；其余异常转成如实失败，注释点明「**绝不让异常穿出成『无人认领』，那会把『发送失败』误报成『通道未启用』**」——这是一个正确且不显然的判断。

#### ⚠️ 已发现的待验缺陷（未修，等构建证实）

**`QQBotChannelPlugin.kt` 疑似编译错误**：`:139` 的 `internal fun handleOutboundRequest(` **不是** `suspend`，但 `:155` 调用了 `:201` 的 `private suspend fun resolveTarget(`。Kotlin 不允许非挂起函数调用挂起函数。

该文件写于 19:39:21，而在跑的构建启动于 19:37:19（**早于**该写入），因此那次构建抓不到。子代理已在 19:39:43 启动第二次构建，**验收时必须强制复跑确认**。

**纪律说明**：本节为只读复核，**不构成验收**。验收只能由调度者强制复跑构建 + 读原始 `TEST-*.xml` 得出。
### 24.10 P4-2（微信侧）的前置设计缺口 —— 需要用户裁定

#### 已排除的风险：`runBlocking` 不会 ANR

`QQBotChannelPlugin.handleOutboundRequest` 在同步 bail 链里用 `runBlocking` 收敛挂起发送。已核实其**不在主线程**：`AgentToolHost.kt:147` 用 `withContext(Dispatchers.IO)` 执行工具，`:78` 的工具池也是 `Dispatchers.IO`；宿主层 `:110` 本身就有 `runBlocking`（Rust 同步回调），嵌套同线程安全。→ **阻塞的是 IO 线程，不会 ANR。**

#### 缺口：事件请求里没有 `companionId`

`ChannelOutboundRequest(channelKey, target, text)`（`ChannelOutboundEvents.kt:71-75`）——**三个字段刻意通道无关**，对 QQ 足够（机器人有绑定宿主 `user_openid`）。

但**微信不够**：
- `WeChatTokenStore.getWechatUserIdsForCompanionId(companionId)` 是靠 `companionId` **反查** `wechatUserId` 的（`WeChatTokenStore.kt:195-198`）；
- 它是**一对多**（`Map<wechatUserId, companionId>`）——同一个伴侣可绑多个微信用户；
- `WeChatOutboundPortImpl.resolveUserIds` 在未指定 `wechatUserId` 时会把消息**广播**给该伴侣绑定的**所有**微信用户。

→ 所以微信侧的「发给**本人**」**本身就有歧义**：`target == null` 时应该发给谁？

且工具**拿不到当前 companionId**：`AiTool.execute(argumentsJson)` 只收参数，`AgentToolHost` **刻意不传 `contextJson`**（`:339`）。

#### 三种可选处置（需用户选）

| 方案 | 说明 | 代价 |
|---|---|---|
| **W1** | `target` 必须显式给 `wechatUserId`；`target == null` 时微信插件**如实失败**并提示「微信通道需要显式指定收件人」 | 最安全、无广播；但用户要说出/粘贴一个 `wechatUserId`，体验差 |
| **W2** | 给 `ChannelOutboundRequest` **加一个 `companionId: Long?`** 字段（通道无关，QQ 忽略） | 微信可解析「本人」；但该类型不再是「三字段刻意通道无关」，且**仍要面对一对多歧义** |
| **W3** | 微信侧「本人」= 该伴侣绑定的微信用户中**最近有入站消息的那个**（用既有 context_token 时间戳判定） | 语义最接近「本人」；但引入「按最近活跃推断」的隐含规则，需用户认可 |

**调度者倾向 W1**：本批目标是「Agent 能发」，而**静默广播给多个真人**是本仓既有行为里最危险的一点（§22.3）。W1 把「发给谁」变成显式输入，不会误发。但这是产品语义，**由用户定**。

#### 另外两点（P4-2 必须处理）

- **微信的失败语义是坏的**：`WeChatOutboundPortImpl` 未登录 / 无映射时**静默返回 `SKIPPED`（空串）**，调用方分不清「成功入队」与「静默跳过」。P4-2 的订阅方**不得**沿用这条通路，必须如实失败。
- **24 小时 `context_token` 硬门**：缺失/过期是 **non-retryable 判死**（`WeChatOutboxCoordinator.kt:350-356`）。窗口外必须如实失败。

#### 工具已经宣告了 wechat 通道

`ChannelSendTool` 的 `parametersJsonSchema` 已写明「channelKey 指定通道（qqbot / wechat）」，`KNOWN_CHANNEL_KEYS`（`:84`）也含 wechat。当前无微信插件订阅 → 返回 `NoChannel`（「该通道未启用」）——**这是正确的如实失败**，P4-2 落地后自动变为可用。
### 24.11 W1 裁定 + P4-2 派发（2026-09-29）

用户未答 W1/W2/W3。调度者按 **W1** 派发，理由：**W1 是严格保守的选择**——它只在无法确定收件人时**如实失败**，绝不误发、绝不广播，因此**不可能造成损害**；将来若要换成 W2/W3，是局部可逆的加性改动。

W1 规则：`request.target` 非空 → 按 `wechatUserId` 发给该用户；`target` 为空 → **如实失败**并提示需要显式指定收件人；**禁止**回退到「发给该伴侣绑定的所有微信用户」。

派发 id：`268791c5-3fca-441f-92e3-aba4b5225d56`（与 P4-1 的 `ad5e9626` 并行，目录不相交）。

### 24.12 P4-1 安全关键路径审查（调度者只读复核，**不构成验收**）

`ChannelSendTool.execute()` 的链路完整，且有三处**超出任务书要求**：

| 环节 | 实现 |
|---|---|
| 参数校验 | 在**派发之前**完成（:149-174），「参数错误不产生任何出站副作用」 |
| 内容安全门 | 在派发之前（:176-188）；且**安全门自身抛异常时 fail-closed**（:179-182）：「安全门自身故障时拒绝发送，绝不放行未经检查的文本」 |
| 派发 | `ctx.bail(ChannelOutboundEvents.REQUEST, ChannelOutboundRequest(...))`（:191-194）——**不是** `ChannelSession.send()` |
| 无人认领 | `!dispatched.isBailed` → `ChannelOutboundResult.NoChannel`（:196-198） |
| 应答类型校验 | 非 `ChannelOutboundResult` → `bad_channel_response`（:200-205） |
| 结果映射 | `Sent` → `ok:true` + `delivery_receipt:false`；`Failed` → `send_failed`；`NoChannel` → `no_channel` 并提示去启用通道（:208-217） |
| `isAvailable()` | 恒 `true`（:127）——**没有**用它表达连接状态（符合「30s TTL 不可表达连接状态」的约束） |
| `systemPrompt()` | 显式禁止模型撒谎：「若返回 `no_channel` 说明该通道未启用，应如实告诉用户去启用，**不要**改口说已经发出去了」（:132-133） |

### 24.13 事件 API 的真实签名（已核实）

`core/domain/.../plugin/Plugin.kt`：
- `fun onBail(event: String, handler: PluginEventListener)` :204 —— handler **不是挂起**；
- `fun onWaterfall(event: String, handler: PluginWaterfallListener)` :211；
- `fun emit(event: String, payload: Any)` :221；
- `fun bail(event: String, payload: Any): PluginEventResult` :250 —— **同步**；
- `fun waterfall(event: String, payload: Any, inner: () -> PluginEventResult): PluginEventResult` :261；
- `class PluginEventResult` :79，`val isBailed: Boolean` :81，`val bailValue: Any?` :83，`val none` :90，`fun bail(value)` :97。

→ 因此订阅回调里调用挂起函数**必须**经 `runBlocking` 收敛。`QQBotChannelPlugin.kt:170-190` 是正确写法。
### 24.16 撞车事故收尾（两个子代理均已报告）

`fab60f8c`（**第一份错误任务书**的派发）已自行停手并结束。它没有收到我的裁决（`send_message` 对它同样返回 `not found`），但**凭证据自行判断并停手**：它检测到并发写入者、确认对方走的是与它互斥的路线、于是停止一切写入且不再跑 gradle。**它的判断是对的。**

**事故裁决（事实已自行收敛，无需再派发）**：
- **唯一写入者 = `ad5e9626`**（事件路线）。`fab60f8c` 已结束，不再触碰这批文件。
- **契约形状以事件路线为准**（`ChannelOutboundEvents` + `bail`），`ChannelOutbound.Proactive` 已被删除。这是 §17.6 裁定的正确落地。
- `core/agent/src/main/**` 的「禁动」由我放开（发起方插件本就该在 `core:agent`，见 §24.9）。

**`fab60f8c` 的贡献仍然保留**（未被覆盖，已核实）：`QQBotTokenStore` 的 `HOST_USER_OPENID_KEY`/`getHostUserOpenId`/`setHostUserOpenId`、`QQBotMessageRepository` 的 `QQProactiveTargetStore`/`sendProactiveText`/`hasAccount`/`hostUserOpenId`/`rememberHostUserOpenId`、`QQBotProactiveRequests.kt`、`QQBotChatBridge` 入站补齐、`QQBotViewModel` 绑定落盘。

**它报告的第 4 条错误（它自己留下的）已修**：`QQBotTokenStore.kt:22` 现为 `class QQBotTokenStore(context: Context) : QQProactiveTargetStore {`，与 `QQBotMessageRepository.kt:74` 的 `private val proactiveTargetStore: QQProactiveTargetStore = tokenStore,` 类型匹配。前 3 条（`replyTo` ×2、`resolveTarget` 挂起）也已修。

**它独立核实并确认了一条我此前的结论**：QQ 群列表端点不存在。「QQ群」分组只有群信息 / 群内状态 / 群成员列表 / 群成员信息 / 批量移除 / 入群申请系列 / 黑名单 / 禁言 / 加入退出事件；唯一的 `@me` 形式是 `GET /users/@me/guilds`（**频道**，不是群）。→ 与 §24.5 一致，且它**没有凭空实现** `GET /v2/users/@me/groups`。

### 24.17 ⚠️ 超出授权的偏差：`ChannelOutbound` 形状被改

我的指令是「摘除 `Proactive` + `TargetKind`，**恢复 P3-2a 形态**（`ChannelOutbound` 只保留 `Text(replyTo, text)`，`replyTo` 非空）」。

实际落地（`ChannelAdapter.kt:142-158`）：

```kotlin
sealed class ChannelOutbound {
    abstract val text: String                    // ← P3-2a 是 abstract val replyTo
    data class Text(
        override val text: String,               // ← 参数顺序也变了
        val replyTo: String,
    ) : ChannelOutbound()
}
```

→ **抽象属性由 `replyTo` 换成 `text`，`Text` 的参数顺序由 `(replyTo, text)` 换成 `(text, replyTo)`。**

**影响评估**：`ChannelAdapter.kt` 目前**零生产读取者**（`ChannelRegistry` 无生产读者、`start()`/`send()` 零生产调用），唯一引用点是 `QQBotChannelAdapter.kt:195` 且用的是**具名参数** `text.replyTo`，因此可编译、无行为影响。KDoc 已同步改写。

**处置**：记录在案，**本批不要求回退**——该契约按 §17.6「需按投影模型重写」，届时一并处置。但这是一次**未经授权的契约形状改动**，若未来出现位置参数调用点会变成隐患。

### 24.18 教训（累积）

1. **`not found` ≠ 已结束**（§24.6）。判断子代理是否还在写，唯一可靠依据是「文件 mtime 是否静止 + 有无 java 进程」。
2. **无法确认前一个子代理终止前，不得对同一批文件派第二个**（§24.6）。
3. **GitHub 镜像 ≠ 权威源**；SPA 页面正文可能内联在 HTML 里（§23.12）。
4. **grep 方法名要考虑大小写变体**（§24.14 的假警报：`hostUserOpenId` 匹配不到 `setHostUserOpenId`）。**先核实再上报。**
5. **子代理会在无裁决的情况下按证据自行判断**——两次都判对了（`ad5e9626` 只做不冲突的部分、`fab60f8c` 停手）。任务书里写清「发现并发写入者时怎么做」是有价值的。
### 24.19 P4-1 测试审查（调度者只读复核，**不构成验收**）

`QQBotChannelPluginTest.kt` 在 19:47:01 被重写，新增 13 项覆盖了任务书的全部验收点：

| 验收点 | 测试 | 断言 |
|---|---|---|
| 不匹配 `channelKey` 必须放行 | `:570` | `assertFalse(result.isBailed)` + `assertEquals(1, ctx.nextCalls)`——「不得吞掉别人的请求」 |
| 匹配时认领并 bail | `:582` | `assertTrue(result.isBailed)` + `bailValue == Sent(messageRef="msg-9")` |
| `target=null` → 绑定宿主 | `:594` | `sentTargets == ["USER:host-42"]` |
| `group:` 前缀 → 群 | `:603` | `sentTargets == ["GROUP:g-7"]` |
| 无可用目标 → **不得猜** | `:612` | `Failed` + `sentTargets == []` |
| 未配置账号 → 明确失败 | `:628` | `Failed` + `sentTargets == []` |
| 未连接 → 明确失败且不发送 | `:643` | `Failed` + `sentTargets == []` |
| 发送失败 → 如实上报 | `:658` | `Failed`，不假装成功 |
| 未接线 → 明确失败 | `:674` | `Failed`，不假装成功 |
| **卸载后不再被认领**（可自由停用） | `:687` | 卸载后 `sentTargets.size == 1`（不再增长） |
| 订阅互不干扰 | `:710` | 卸载本插件后非本通道请求仍 `isBailed == false` 且 `nextCalls == 1` |
| 订阅即 effect | `:559` | `bailListenerCount(REQUEST) == 1` |

**既有 13 项零删除**，含 `:272`「capabilities 逐字段等于诚实声明值」与 `:285`「整体等于逐位构造的诚实值（防顺手改成理想值）」——「防顺手改成理想值」这类断言写法值得保留。

#### ⚠️ 一处已过时的注释（不阻塞，记录备查）

`QQBotChannelPluginTest.kt:279`：

```kotlin
assertFalse("出站 100% 被动，必须带 msg_id", caps.proactiveSend)
```

**断言本身正确**（该位描述 `ChannelOutbound.Proactive` 变体能否跑通，而该变体已按 §17.6 删除），但**注释文字已与事实不符**：QQ 平台**支持**主动消息（省略 `msg_id` 即主动消息，官方文档标为「无任何条件」，见 §24）。`QQBotChannelAdapter.kt:45` 的 KDoc 已改正这一点，因此**测试注释与适配器 KDoc 现在互相矛盾**。

→ 待处置：把注释改成「该位描述 `ChannelOutbound.Proactive` 变体；主动发送走 `ChannelOutboundEvents`，故恒 false」，与 `QQBotChannelAdapter.kt:45` 对齐。**不阻塞验收**。
### 24.20 P4-2（微信）实现审查（调度者只读复核，**不构成验收**）

新增文件：`WeChatChannelPlugin.kt`(183) / `WeChatChannelAdapter.kt` / `WeChatChannelSender.kt`(159) / `WeChatChannelWiring.kt`。

#### W1 的落地方式值得记录：**无条件且先于接线检查**

`WeChatChannelPlugin.kt:152-156`：

```kotlin
// ── W1（**无条件**，先于任何接线检查）：没有显式收件人就如实失败 ──
val target = request.target?.trim().orEmpty()
if (target.isEmpty()) {
    return ChannelOutboundResult.Failed(NEED_EXPLICIT_TARGET_REASON)
}
```

→ 该判定放在 `sender == null` 检查**之前**，因此**接线失败无法绕过 W1 保证**。这是一个正确的顺序选择。

`NEED_EXPLICIT_TARGET_REASON`（:92-98）同时满足三点：说明「需要显式收件人」、说明「**不会**替你猜、也**不会**广播给全部绑定用户」、并给出**可操作**的下一步（「可在予念『设置 → 微信』的用户绑定列表里查看，或改用 App 内对话 / QQ 通道」）——比任务书要求更完整。

#### 发送器把静默跳过变成如实失败（`TransportWeChatChannelSender`）

逐条前置判定（顺序即优先级）：
1. 收件人为空 → 失败（「**绝不放行成『发给所有人』**」）；
2. 文本空白 → 失败；
3. 未登录（`getSessionAccount() == null`）→ 失败 + 可操作提示（**不是** `SKIPPED`）；
4. `context_token` 缺失 → 失败（`SEND_BLOCKED_NO_TOKEN`）；
5. `context_token` 年龄 > 24h → 失败（`SEND_BLOCKED_EXPIRED`），**复用同一个常量** `WeChatOutboxCoordinator.CONTEXT_TOKEN_MAX_AGE_MS`，且同样**不重试**（判死是协议事实，不是网络抖动）；
6. 才落到 transport，异常经 `Result` 转成失败原因。

#### 三处超出任务书要求（记录为正面发现）

1. **与既有 outbox 的边界逐字一致**（`:136`）：`savedAt != null` 才做年龄判定——「`getContextTokenSavedAt` 返回 null（旧格式记录，`savedAtMs = 0`）时**不做**年龄判定——**无法证明过期就不判死**」。它没有发明更严的规则，而是精确对齐既有语义。
2. **失败原因脱敏**（`:152-157`）：失败原因会回到模型 / 用户可见处，故 `mask()` 成 `abc***xy`，不回显完整 id。任务书未要求。
3. **「为什么不走 outbox」的论证**（`:26-33`）：`enqueue` 只证明「**已入队**」不证明「已发出」；排完队由 drain 异步发送时，插件无法在不读 Room 行状态的前提下如实回答 `Sent` / `Failed`。故主动发送走**一次同步 transport 调用**。并明确**被动回复 / AI 自动回复仍走既有 outbox 通路，本批一行未改**——**消息时序红线保住**。

另外：`WeChatChannelSender` 作为**窄接口**的理由是测试源集只有 junit 无 Robolectric，「被测的插件代码本身是真代码，不是复制品」；接口 KDoc 明确「本接口**没有**、也不得有任何『未指定就群发』的语义」。
### 24.21 「可自由启用 / 停用」的机制已核实成立

这是需求的**核心语义**（用户原话：「要求微信和 QQ 成为 cordis 独立插件」+ 访谈中的「组合自由 = 插件的自由启用」），此前只被间接验证过。本轮读源码确认：

两条路径分工明确：
1. `pluginHost.register(instance)` —— 把插件**实例**登记进宿主目录（`YuNianApplication.kt:656/661/662/673/697/708` 等）；
2. `loadDefaultBlueprint`（`:791-809`）读 `assets/blueprints/default.json` → `host.loadBlueprint(blueprint)` —— **决定哪些插件真正执行 `setup()`**。

→ 因此 **`default.json` 的 `plugins` 数组就是启用开关**：

| 操作 | 结果 |
|---|---|
| 列表含 `channel.wechat` | `setup()` 执行 → 注册适配器 + 订阅 `channel.outbound.request` → 微信可发 |
| **从列表移除** `channel.wechat` | 实例仍登记但**不装载** → 无适配器、无订阅 → 发送工具派发后 `isBailed == false` → 返回 `no_channel`（「该通道未启用」） |

`:714` 的注释也确认了这一设计：「插件装载由默认蓝图统一驱动（assets/blueprints/default.json，initBusiness 内执行）」；`:787-788`：「plugins 为基准列表（缺省启用），patches 覆盖配置，inserts 追加新插件；装载顺序即列表顺序（sticker 引擎依赖此顺序）」。

**当前 `default.json` 顺序**：`coffee.luckin` → `skill.builtin_chat_protocol` → `sticker.preference` → `automation.core` → `channel.qqbot` → `channel.wechat` → `message.send`。`message.send` 在末尾不影响正确性（它只注册工具，派发发生在调用时）。

**App 装配**（两通道均已接线）：`YuNianApplication.kt:674` QQBotChannelPlugin、`:698` WeChatChannelPlugin（`:700` `weChatChannelSender(app)`）、`:708` MessageSendPlugin。

#### 用户可见的「自由启用」入口尚未做（记录为缺口）

机制成立 ≠ 用户能操作：**目前没有 UI 让用户勾选启用 / 停用某个通道插件**，只能改 `default.json` 重启。这与访谈中「组合自由 = 插件的自由启用」的最终目标还有距离。**本批不要求**（本批目标是「Agent 能发」），但应作为后续批次记录。
### 24.22 P4-2 测试审查（调度者只读复核，**不构成验收**）

`WeChatChannelPluginTest.kt` **36 项**，覆盖密度高于任务书要求。值得单列的是以下七项——它们验证的是**边界与顺序保证**，而不是 happy path：

| 测试 | 验证的不变量 |
|---|---|
| `:696` target 为空时**即使通道未接线也如实失败** | W1 判定**先于**接线检查——顺序保证若被改回，测试立刻红 |
| `:703` target 为空时**经事件派发**同样得到 Failed（端到端） | 走**真实事件总线**派发，不是只测内部函数 |
| `:688` target 为空时**绝不调用发送方**（连通道都不问） | 「不猜、不广播」的**副作用层面**证据 |
| `:611` 不匹配 channelKey 时**不产生任何出站副作用** | 放行 ≠ 只是返回值对，还必须无副作用 |
| `:791` context_token **恰好落在 24 小时边界内**时仍然发送 | 边界值（`>` 而非 `>=`） |
| `:815` **旧格式记录（无落库时间）不做年龄判定** | §24.20 那个「无法证明过期就不判死」的微妙分支 |
| `:864` 发送抛异常时**不得表现为无人认领** | 防止「发送失败」被误报成「通道未启用」 |

其余分组：路由放行（`:579`/`:628`）、认领应答（`:642`）、W1（`:662`/`:679`/`:696`/`:703`）、未接线 / 未登录 / token 缺失 / token 过期 / 传输失败 / 抛异常（`:723`-`:853`）、卸载后订阅消失（`:883`）、会话契约（`:904`-`:968`）、插件宿主契约（`:410`-`:563`）。

#### 两个子代理无法被中断（记录约束）

`list_agents` 只返回 `lead`——**`subagent` 派发的子代理不可寻址**，因此 `interrupt_agent` 也**无法**对它们生效（该工具要求 target 来自 `spawn_teammate` / `list_agents`）。

→ 调度者**无法主动中止**它们，只能等其自行结束。这解释了验收为何被推迟多轮，也是 §24.6 教训的延伸：**派发前必须预留验收窗口**，否则只能等。
### 24.23 P4-1 工具层测试审查（调度者只读复核，**不构成验收**）

`core/agent/src/test/java/com/yunian/ai/agent/tools/ChannelSendToolTest.kt` **27 项**。安全关键项全部在册：

| 测试 | 验证的不变量 |
|---|---|
| `:164` 工具是本机敏感且默认走确认门 | `appLocalOnly` + `requiresConfirmation` 双真 |
| `:213` **外部桥接会话看不到本机发送工具** | **数据出境边界**（`allowAppLocalTools=false`） |
| `:200` 插件装载后工具在注册表可见且本机会话能枚举到 | 正向对照 |
| `:481` 文本未过安全过滤时不发送 | ContentFilter 门生效 |
| `:497` **安全门自身故障时 fail-closed 拒绝发送** | 安全门异常不放行 |
| `:510` **安全过滤先于通道派发（顺序契约）** | 任务书要求的顺序保证 |
| `:273` 没有任何通道插件订阅时工具返回失败而不是成功 | 如实失败 |
| `:286` 订阅者只处理匹配的通道其余请求仍然无人认领 | 路由正确 |
| `:302` **订阅者认领却不应答时不得当成成功** | bail 出 `null` 不得算成功 |
| `:350` 成功结果必须声明没有投递回执 | `delivery_receipt: false` |
| `:387` 通道应答类型不对时明确失败 | 类型校验 |
| `:417`/`:430` 未知 / 未声明通道明确失败且**不派发** | 参数校验先于副作用 |
| `:443`/`:456` 空白 / 超长文本明确失败且不派发 | 同上（超长是失败而非截断） |
| `:470` 参数不是合法 JSON 时明确失败 | 同上 |
| `:179` **isAvailable 恒为真（连接状态不得走 30s TTL 缓存）** | 避开 TTL 陷阱 |
| `:228`/`:531`/`:550` 卸载后工具消失 / 该通道请求变为失败 / 工具消失 | effect 生效 + **可自由停用** |

#### 三个测试文件合计

| 文件 | 项数 |
|---|---|
| `ChannelSendToolTest.kt`（新，core:agent） | 27 |
| `QQBotChannelPluginTest.kt`（feature:qqbot） | 13 既有 + 13 新增 |
| `WeChatChannelPluginTest.kt`（新，feature:wechat） | 36 |

→ 新增测试合计约 **76 项**，既有测试零删除。**但全部尚未由调度者强制复跑，因此不作为验收证据。**
### 24.24 红线合规审计（调度者只读核实，无需构建）

用户红线原话：「**红线：作废，可以引入第三方依赖，但是不允许引入第三方API。一切都已cordis架构为优先。**」+「**不得让API渗透进架构**」。

对全部新增 / 改动源文件做 import 审计：

| 文件 | import 数 | 结论 |
|---|---|---|
| `ChannelOutboundEvents.kt`（新，core:domain） | **0** | ✅ `core:domain` 的零依赖规则保持 |
| `ChannelAdapter.kt`（core:domain） | 1 | ✅ |
| `ChannelSendTool.kt`（新，core:agent） | 7 | ✅ |
| `MessageSendPlugin.kt`（新，core:agent） | 7 | ✅ |
| `WeChatChannelPlugin.kt`（新，feature:wechat） | 11 | ✅ |
| `WeChatChannelSender.kt`（新，feature:wechat） | 4 | ✅ |
| `WeChatChannelWiring.kt`（新，feature:wechat） | 4 | ✅ |

全部 import 均落在 `kotlin` / `kotlinx` / `com.yunian` / `java` / `android` / `androidx` 之内——**没有任何第三方 API 渗进架构**。

新增的 `:core:common` 依赖（`core/agent/build.gradle.kts`，3 行）是 **core→core**，AGENTS.md 只禁止 core→feature 与 feature→feature，故合规。
### 24.25 子代理自跑的测试结果（**不是验收证据**，但是强信号）

从原始 `TEST-*.xml` 读取（19:56 采样）：

| 模块 | 基线（P3-3c） | 现在 | 变化 | failures | errors | skipped |
|---|---|---|---|---|---|---|
| `core:agent` | 220 | **247** | **+27** | 0 | 0 | 0 |
| `feature:qqbot` | 58 | **70** | **+12** | 0 | 0 | 0 |
| `core:wechat` | 88 | **88** | **0** | 0 | 0 | 0 |
| `feature:wechat` | 49 | 未跑（19:12 旧读数） | — | — | — | — |

`core:agent` 的 +27 **正好等于 `ChannelSendToolTest` 的 27 项**；`core:wechat` 零变化说明**没有回归**。

**但这不是验收**：按 R6，子代理的自我陈述与自跑结果都不作为验收依据。验收必须由调度者**强制复跑**（`--rerun-tasks`）并读原始 XML 得出。此表仅用于判断「代码当前是否可能通过」，不用于宣布完成。

### 24.26 并发构建风险（观察到的实际情况）

19:53:38 与 19:53:42、19:54:35 与 19:54:49 分别出现**成对的 `kotlin-compiler-*.salive` 会话文件**，且采样时同时存在两个满载 java 进程（约 3.4 + 3.6 核）。→ **两个子代理在并发编译。**

这正是任务书里写明的红线（本仓曾因并发 gradle 损坏 Kotlin 增量状态）。后果分两种：
- **显性**：虚假的 `Unresolved reference`——可识别、可重跑；
- **隐性**：构建通过但用过期 class——会产出**假的绿色**。

**调度者的处置**：**拒绝**在并发构建进行中插入验收。理由：假绿灯会让一个未验证的功能被当成已验证交付，比多等几轮严重得多。

**后续验收必须带新鲜度证明**：`--rerun-tasks` + 校验 `TEST-*.xml` 的 mtime **晚于**全部源文件 mtime + 核对用例计数与基线差值。三者同时满足才计为有效证据。
### 24.27 四模块测试结果全部产出（19:59 采样，**仍不是验收证据**）

`feature:wechat` 的结果在 19:58:01 首次产出后，四个模块的数字已完整：

| 模块 | 基线（P3-3c） | 现在 | 变化 | 期望变化 | 吻合 | failures | errors | skipped |
|---|---|---|---|---|---|---|---|---|
| `core:agent` | 220 | **247** | +27 | +27（`ChannelSendToolTest`） | ✅ | 0 | 0 | 0 |
| `feature:qqbot` | 58 | **70** | +12 | — | — | 0 | 0 | 0 |
| `feature:wechat` | 49 | **85** | +36 | +36（`WeChatChannelPluginTest`） | ✅ | 0 | 0 | 0 |
| `core:wechat` | 88 | **88** | 0 | 0（不得回归） | ✅ | 0 | 0 | 0 |
| **合计** | 415 | **490** | **+75** | — | — | **0** | **0** | **0** |

两个「期望变化」都精确吻合，`core:wechat` 零回归。**零失败、零错误、零跳过。**

#### 为什么这仍然不是验收

按 R6：**子代理的自跑结果与自我陈述都不作为验收依据。** 三个具体理由：
1. 这些 XML 由**子代理自己**运行产出，我没有参与，无法排除其运行参数（是否 `--rerun-tasks`、是否被中断）与实际不符；
2. 采样时**两个子代理在并发编译**（§24.26），无法排除「用了过期 class」的隐性假绿；
3. 我没有核验这些 XML 的 mtime 是否**晚于**全部源文件 mtime。

→ 仍需调度者按 §24.26 的三条标准强制复跑。**本表的作用是「若验收失败则极可能是环境问题而非代码问题」**，而不是替代验收。
### 24.28 ✅ 强制复跑验收：**通过**（调度者亲自执行，2026-09-29 20:08）

两个子代理均已结束，工作区静止后执行：

```
.\gradlew.bat :core:agent:testDebugUnitTest :feature:qqbot:testDebugUnitTest \
  :feature:wechat:testDebugUnitTest :core:wechat:testDebugUnitTest \
  --offline --console=plain --rerun-tasks
```

**结果**：`BUILD SUCCESSFUL in 6m 5s`，`127 actionable tasks: 127 executed`，`EXITCODE=0`。

**三条判定标准逐条满足**：

| # | 标准 | 证据 |
|---|---|---|
| 1 | `--rerun-tasks` 强制重跑且退出码 0 | `127 actionable tasks: **127 executed**`（非 UP-TO-DATE）+ `EXITCODE=0` |
| 2 | XML mtime **晚于**全部源文件 mtime | XML 20:07:04–20:08:23；最新源文件 `ChannelSendToolTest.kt` 19:53:31 |
| 3 | 用例计数与基线吻合 | 见下表 |

| 模块 | 基线（P3-3c） | 验收读数 | 变化 | failures | errors | skipped |
|---|---|---|---|---|---|---|
| `core:agent` | 220 | **247** | +27 | 0 | 0 | 0 |
| `feature:qqbot` | 58 | **70** | +12 | 0 | 0 | 0 |
| `feature:wechat` | 49 | **85** | +36 | 0 | 0 | 0 |
| `core:wechat` | 88 | **88** | 0 | 0 | 0 | 0 |
| **合计** | 415 | **490** | **+75** | **0** | **0** | **0** |

**既有测试零删除、零弱化。** `core:wechat` 零变化证明无回归。

#### 对并发风险的结论

§24.26 担心的「隐性假绿」**没有发生**：`--rerun-tasks` 让 127 个任务全部真实执行（而非复用产物），且 XML 全部晚于源文件。→ 之前的并发构建**没有损坏增量状态**。

#### 两个子代理的交付回报（均已完成并结束）

**`ad5e9626`（P4-1）**：契约 + 发起方插件 + QQ 订阅方 + 接线；`:app:compileDebugKotlin` BUILD SUCCESSFUL。它**独立核实了 `msg_id` 可省略**——用 `sitemap.xml` 拿真实页址 → 取 CDN chunk → 从 VuePress 的 `t._v("...")` token 还原文档表格，读到 `msg_id | string | **否**`，与 §24 的结论**互证**。

它**主动披露**了唯一一处改动既有测试的地方，且那是**加强**：原断言 setup「恰好 1 条 effect」，P4-1 后 setup 有两条合法 effect（通道注册 + 事件订阅），改为逐条按标签断言并要求**同时**存在 `unregister-channel:` 与 `unsubscribe:channel.outbound.request`——比原来更严。

它**主动绕开两个坑并说明理由**：(1) `org.json` 在纯 JVM 单测里是 `Stub!` 空壳、`kotlinx.serialization` 不在 `:core:agent` 依赖里 → 自写极小扁平 JSON 读取器，**没有为一个三字段解析给核心模块加依赖**；(2) `runBlocking` 已核实跑在 `AgentToolHost` 的 `Dispatchers.IO` 工具池（`:77-79`）上，**不在主线程**——与 §24.10 的独立核实一致。

**`268791c5`（P4-2）**：微信插件 + 发送器 + 接线 + 36 项测试；验收命令 EXITCODE=0。它**主动上报了三点**：并发构建确实发生（另一子代理的构建窗口 19:56:53–20:00:35 与它的运行重叠，且它第一次构建曾瞬时失败，同源码随后两次全绿）；**一处偏离**（见 §24.29）；**两处禁动区的过期文档**（见 §24.30）。

它还用**二进制扫描**证明 App 注册被编译进产物：`YuNianApplication$Companion.class`（20:00:32）同时含 `WeChatChannelPlugin` 与 `weChatChannelSender` 引用——在 `:app:compileDebugKotlin` 为 UP-TO-DATE 时这是一个有效的替代证明。

### 24.29 对 P4-2 一处偏离的裁定：**接受**

任务书写的是「跑 gradle 前先 `Get-Process java`，有进程就等待」。P4-2 指出该判据**不可证伪**：本机存在常驻 Gradle 守护进程（PID 39900），它**空闲时也一直存在**，等它消失不现实。

它改用：解析 `~/.gradle/daemon/9.4.1/*.out.log` 中 `Executing build with daemon context` 与 `Finishing executing command: Build` 的行序，判定是否有构建在跑。

**裁定：接受，且这比原判据更好。** 原判据是我写的，它是错的（把「守护进程存在」误当成「正在构建」）。这条要记进教训：**给子代理的并发判据必须是可证伪的**，否则它只能在「永远等」与「违规开跑」之间二选一。
### 24.30 ⚠️ `:app:compileDebugKotlin` 验收第一次尝试失败 —— **环境问题，非代码问题**

命令：`.\gradlew.bat :app:compileDebugKotlin --offline --console=plain --rerun-tasks`

结果：`BUILD FAILED in 41s`，`152 actionable tasks: 152 executed`，`EXITCODE=1`。

失败原因（原始日志）：

```
* What went wrong:
Execution failed for task ':core:domain:bundleLibCompileToJarDebug'.
> java.nio.file.FileSystemException:
  ...\core\domain\build\intermediates\compile_library_classes_jar\debug\
     bundleLibCompileToJarDebug\classes.jar: 另一个程序正在使用此文件，进程无法访问。
```

**判定为环境问题（Windows 文件锁），不是代码问题。** 证据：
1. **同一次会话稍早的验收（§24.28）已经成功执行过 `:core:domain` 的全部任务**（含 `compileDebugKotlin`），退出码 0；
2. 失败任务是 `bundleLibCompileToJarDebug`——**打包 jar 的 I/O 步骤**，不是编译步骤；
3. 报错是 `FileSystemException ... 另一个程序正在使用此文件`，即文件被另一个 JVM 持有；
4. 采样显示当时有两个 java 进程：`39900`（19:39:44 启动的常驻守护进程，CPU 5351s）与 `14740`（20:04:12，本次运行的启动器）。

**处置**：`.\gradlew.bat --stop` 停掉守护进程（`1 Daemon stopped`），确认 `Get-Process java` 清空后重跑。

#### 教训：常驻 Gradle 守护进程会持有 jar 锁

这与 §24.29 是同一件事的两面：**「守护进程存在」不等于「正在构建」**（P4-2 的偏离因此正确），但**它确实会持有 `build/intermediates/**` 下的文件锁**，从而让 `--rerun-tasks` 的 I/O 步骤失败。

→ 正确做法：**`--rerun-tasks` 之前先 `--stop`**，而不是等守护进程消失（它不会消失）。
### 24.31 ✅ `:app:compileDebugKotlin` 验收重试：**通过**（§24.30 的环境性失败已排除）

`gradlew --stop` 停掉守护进程后，用**完全相同的命令**重跑：

```
.\gradlew.bat :app:compileDebugKotlin --offline --console=plain --rerun-tasks
→ BUILD SUCCESSFUL in 7m 29s
→ 203 actionable tasks: 203 executed
→ EXITCODE=0
```

**这坐实了 §24.30 的判定**：同一命令、同一源码，第一次因 jar 文件锁失败、停掉守护进程后通过 → **纯环境问题，与代码无关**。

### 24.32 本批验收结论（调度者亲自执行，两条独立命令）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `:core:agent:testDebugUnitTest :feature:qqbot:testDebugUnitTest :feature:wechat:testDebugUnitTest :core:wechat:testDebugUnitTest --rerun-tasks` | `BUILD SUCCESSFUL in 6m 5s`，`127 tasks: 127 executed`，`EXITCODE=0`，**490 tests / 0 failures / 0 errors / 0 skipped** |
| 2 | `:app:compileDebugKotlin --rerun-tasks` | `BUILD SUCCESSFUL in 7m 29s`，`203 tasks: 203 executed`，`EXITCODE=0` |

两条命令都是 `--rerun-tasks`（全部任务真实执行，非 UP-TO-DATE），XML mtime 均晚于全部源文件。**P4-1 + P4-2 通过验收。**

### 24.33 ⚠️ 用户提供权威约束，**推翻 §24 的 W1 决策前提**

用户原话：

> 「微信是通过ilink协议，但是ilink协议只允许给绑定的微信发消息，所以给别人和群发消息是不存在的，官方协议不允许。」

**后果一：W1 的危险前提不存在。** 我此前反复强调「绝不放行成发给所有人」并据此让微信 `target` 必填。但 ilink **协议层**不允许发给非绑定用户，因此「群发」这个攻击面**根本不存在**。我基于一个想象出来的危险做了一个决策。

**后果二：P4-2 的实现成为真实功能缺陷。** 它按 W1 实现 `target` 为空 → `Failed(NEED_EXPLICIT_TARGET_REASON)`。于是用户说「你给我微信发条消息」→ 模型省略 target → **必然失败**。**这正是本目标的微信侧核心场景。**

**后果三：`ChannelSendTool` 的描述反而是对的。** 它写「target 省略或留空 = 发给用户本人」——按用户的新约束，「本人」就是绑定的那个微信，所以该描述**成立**。是**我的 W1 实现与它不一致**，不是描述错了。

**处置**：已派发修正任务（`2efbe209`），语义改为：

| 情况 | 行为 |
|---|---|
| `target` 为空 / 空白 | **解析出绑定的微信用户并发送**（主路径，必须工作） |
| `target` = 绑定用户 | 发送 |
| `target` = 非绑定用户 | **如实失败**，说明 ilink 只允许发给绑定账号；**绝不静默改写后发送** |
| 无法唯一确定绑定用户 | **如实失败**，绝不静默挑一个 |

后两条刻意保留：**安全属性是「不猜」，不是「怕群发」**。危险变小了，但「不得在多个候选里静默挑一个」仍然是正确原则。
### 24.35 ✅ 授权门控核实：**新工具确实受门控管辖**，且与用户 Q3 裁定一致

用户目标里的「必须保持既有授权门控与安全过滤不被绕过」——逐环核实：

| 环节 | 证据 |
|---|---|
| 工具声明 | `core/agent/.../tools/ChannelSendTool.kt:117` `override val requiresConfirmation: Boolean = true` |
| 折成类别 | `core/agent/.../AgentFacade.kt:682` `toolRequiresConfirmation = tool.requiresConfirmation` |
| 门控生效 | `core/agent/.../AgentConfirmGuard.kt:43` —— `requiresConfirmation = true` ⇒ 映射为 `ToolCategory.COMMERCE` |
| 出现在授权 UI | `core/agent/.../capability/CapabilityGrantBoard.kt:142` 「把注册池里的**全部** Agent tools 转成清单行（**不再过滤** `requiresConfirmation`）」 |
| 默认值 | `CapabilityGrantBoard.kt:230` → `CapabilityDefaults.requiresConfirm(explicit, tool.requiresConfirmation)`，`explicit == null` 时回到工具自身默认 |
### 24.37 ✅ 「不破坏通道保活与消息时序」核实：**有硬证据**

用户目标里的这条要求，用文件 mtime 直接证明——**保活/时序相关文件在本批里一个字节都没动**：

| 文件 | mtime |
|---|---|
| `feature/qqbot/.../service/QQBotForegroundService.kt` | **09-29 05:03:55** |
| `feature/qqbot/.../data/network/QQBotWebSocketClient.kt` | 09-29 05:02:54 |
| `feature/wechat/.../service/WeChatServiceLocator.kt` | 09-29 05:52:19 |
| `core/wechat/.../outbox/WeChatOutboxCoordinator.kt` | 09-29 05:52:19 |
| `app/src/main/AndroidManifest.xml` | 09-28 23:41:17 |

全部是 **09-28/09-29 凌晨**的时间戳，而本次工作从 **19:00 之后**才开始 → 本批**未触碰**这些文件。

补充结构证据：`QQBotChannelPlugin.setup()` 与 `WeChatChannelPlugin.setup()` 都**不启动连接、不发心跳、不碰重连、不改任何消息时序**（两份 KDoc 均如此声明，且实现里只有 `registry.register` + `ctx.effect` + `ctx.onBail`）。发送是**按需触发**，没有后台循环。

**未做**：熄屏 / Doze 实机验证（需要真机 + 真实账号，本环境做不到）。此条为**结构性证明**，不是运行时证明——如实标注。

### 24.38 Gradle 守护进程日志佐证（可证伪的构建判据）

按 §24.29 采纳的判据解析 `~/.gradle/daemon/9.4.1/*.out.log`：

```
daemon-39900: 2026-09-29T20:08:52 Executing build with daemon context
              BUILD FAILED in 41s                       ← 第一次 :app:compileDebugKotlin（文件锁）
              2026-09-29T20:09:33 Finishing executing command: Build
daemon-41272: 2026-09-29T20:10:14 Executing build with daemon context
              BUILD SUCCESSFUL in 7m 29s                ← 停守护进程后重试（§24.31）
              2026-09-29T20:17:41 Finishing executing command: Build
```

日志与 §24.30 / §24.31 的观测**逐条吻合**，且证明 20:17:41 之后无构建在跑。
### 24.39 ⚠️ 用户目标中的「你在QQ群里@我一下」——**平台层面无法完整满足**（已读源码确认）

用户的原始需求包含：「用户在 yunian 里聊天时，如涉及……**「你在QQ群里@我一下」**这种消息，则自主调用对应的消息通道」。

**结论：QQ 群聊的出站 @ 提及在官方 API 中不存在，只能降级为含「@你」字样的纯文本，不会触发真实 @。**

证据（直接读序列化模型，非推测）：`feature/qqbot/.../data/model/QQBotModels.kt`

```kotlin
// :88-95  出站请求体的**全部**字段
data class SendTextRequest(
    val content: String? = null,
    val markdown: QQMarkdown? = null,
    @SerialName("msg_type") val msgType: Int = 0,
    @SerialName("msg_id") val msgId: String? = null,
    @SerialName("msg_seq") val msgSeq: Int = 0,
    @SerialName("message_reference") val messageReference: QQMessageReference? = null,
)
```

- **没有 `mentions` 字段。**
- `mentions`（`:82-83`）在**入站**的 `QQMessageEvent` 上，用途是「判断是否 @ 了机器人本身」——**方向相反**。
- `message_reference`（`:102-105`）是**引用回复**（`message_id`），不是 @；且主动发送没有 `message_id` 可用。

**为什么不能靠「发一条含 @ 的文本」蒙过去**：QQ 客户端的 @ 提及是**结构化字段**，纯文本里的 `@昵称` 不会被渲染成提及，对方收不到 @ 通知。

**处置**：如实标注为**未满足项**，且**不可由本项目修复**（属平台能力边界，不是实现缺陷）。已在上报用户的进度表中标记为 ⚠️ 降级。

**与 §24 的 `msg_id` 结论的对照**：那一条我一开始信了过期镜像、下了错误结论，后来靠**官方在线文档**纠正；这一条我**直接读序列化模型**确认，且与官方文档的群聊消息页（无 @ 字段）一致。**两条独立证据**。

**结论**：`send_channel_message` 默认需确认；用户在「工具授权」里放开后转为自主。**这正是用户 Q3 的原话「默认需要确认，授权后允许自主」。** ✅

### 24.36 ⚠️ 发现两处测试夹具的陈述已过期（**不是失败，是假陈述**）
### 24.40 「可自由启用/停用」的启用开关本身：**已核实可用，但发现一处既有债**

#### 已核实（正面证据）

`app/src/main/assets/blueprints/default.json` 全文 30 行，JSON **合法**，7 个插件按序：

```
[0] coffee.luckin          [1] skill.builtin_chat_protocol   [2] sticker.preference
[3] automation.core        [4] channel.qqbot                 [5] channel.wechat
[6] message.send
```

解析器 `PluginBlueprintParser` 对该文件形状的处理逐条正确：

| 行为 | 证据 |
|---|---|
| `enabled` 缺省即启用 | `:74` `o.optBoolean("enabled", true)`；`default.json` 未写该字段 → 7 个全部启用 |
| 顺序保持 | `:62` 返回 `LinkedHashMap.values.toList()`（`LinkedHashMap` 保持插入序） |
| 缺 `patches`/`inserts` 不报错 | `:66` `if (arr == null) return emptyList()`；`root.optJSONArray(...)` 对空数组返回空数组 |

→ **开关的两种用法都成立**：删掉某个 `{"id": "channel.wechat"}` 条目，或给它加 `"enabled": false`。

#### ⚠️ 既有债（**不是本批引入**）

1. **`PluginBlueprintParser` 没有任何测试**：`glob **/*Blueprint*Test*.kt` 返回空。
   原因很可能是它 `:5-6` 依赖 `org.json`，而 `org.json` 在纯 JVM 单测里是 Android java-stub 空壳（`Stub!`）——P4-1 报告独立指出了这个坑（`ChannelSendTool` 因此自写了扁平 JSON 读取器）。
2. **`YuNianApplication.kt:222` 是 `runCatching { loadDefaultBlueprint(app) }`——异常被吞掉。** 若该 JSON 哪天写错，插件会**静默不加载**，应用照常启动，用户只会看到「通道未启用」。

**判定**：`PluginBlueprintParser.kt` 是 **tracked 文件**（不在 `git status` 的 `??` 列表中）→ **既有代码**，本批未改。因此这是**既有债**，不是本批缺陷。
### 24.41 ✅ 授权门控**完整链条闭合**（含 Rust 侧消费点）

§24.35 只核实到「折成类别」，本轮补齐了「类别如何被消费」这一环。关键事实来自 `AgentFacade.kt:651-656` 的文档：

> Rust 侧唯一的门控判定（`agent-native/src/agent.rs`：`definition.category == ToolCategory::Commerce`）目前以 COMMERCE 作为「需要确认」的载体；而 `ToolDefinition` 的 JSON 投影只取 name / description / parameters_json，**既不带 category 也不带 requiresConfirmation**，所以「这个工具要不要确认」只能在**装配期**折进 category——否则该声明在 Agent 链路上没有任何消费者（等于摆设）。

| # | 环节 | 落点 |
|---|---|---|
| 1 | 工具声明 | `core/agent/.../tools/ChannelSendTool.kt:117` `requiresConfirmation = true` |
| 2 | 折成类别 | `core/agent/.../AgentFacade.kt:680-683` `CapabilityDefaults.requiresConfirm(explicit, toolRequiresConfirmation)` → `ToolCategory.COMMERCE` |
| 3 | 按伴侣折叠 | `AgentFacade.kt:729-735` `toolDefinitionsFor(companionId, tools)` —— **唯一折叠点**（`:709` 注明「所有工具装配点都必须走它」） |
| 4 | **Rust 侧消费** | `agent-native/src/agent.rs` 读 `definition.category == ToolCategory::Commerce` 决定是否走确认门 |
| 5 | 授权后转自主 | `decisions[toolName] = true` ⇒ 不再强制 COMMERCE ⇒ 类别回落到工具集映射（生产确认类工具 `toolsets` 为空 ⇒ `GENERAL`） |

**fail-closed 保证**（`:723-724`）：`resolveDecisions` 在「存储未注册 / 读取抛异常」时返回**空表** ⇒ 所有工具走 `deriveToolCategory` ⇒ 行为与没有授权表时**逐字一致**。

**保守边界**（`:719-721`）：授权只解除 / 施加「确认」这一道；工具**自身工具集**本来就是 `commerce` 的仍得到 COMMERCE。原文：「授权表不是『关掉确认门』，而是『承认用户已经逐条同意过的那条组合』」。

**结论**：`send_channel_message` 默认走确认门，用户逐条授权后转自主——与用户 Q3 的裁定「默认需要确认，授权后允许自主」**逐字一致**。

**未来项**（`:670-671` 自述）：若给 `ToolDefinition` 加显式 `requires_confirm` 字段（Rust 门控直接读该字段），`deriveToolCategory` 的确认分支应当一并删除。→ 对应本计划中的 **P2-b**（待办）。

**但值得记下**：本批的「组合自由」**首次真正依赖**这条路径（此前的插件都是长期存在的），所以这条债的暴露面变大了。建议后续单独立项：给解析器加 JVM 可跑的测试（或改用不依赖 `org.json` 的解析），并把 `:222` 的 `runCatching` 改为至少记录失败原因。

**非阻塞**：不影响本批任何验收项。

新增的 `send_channel_message` 是**第 7 个** `requiresConfirmation = true` 的生产工具，但两处夹具仍硬编码 6 个：

| 文件 | 行 | 内容 |
|---|---|---|
| `core/agent/src/test/kotlin/.../CapabilityGrantTestFixtures.kt` | `:85` | `/** 生产侧 6 个静态 \`requiresConfirmation = true\` 的工具名（逐条对齐源码）。 */` |
| 同上 | `:86-93` | `PRODUCTION_CONFIRM_TOOL_NAMES` 列表，6 项 |
| 同上 | `:95` | `/** 6 个生产确认类假工具。 */` |
### 24.42 W1 修正任务（`2efbe209`）的实现审查——调度者只读核实

#### 交付物

| 文件 | 性质 |
|---|---|
| `feature/wechat/.../channel/WeChatRecipientResolution.kt` | **新建**（37 行）：收件人解析结果，两态密封类 |
| `feature/wechat/.../channel/WeChatChannelSender.kt` | 修改（159→283 行）：新增 `resolveRecipient`，新增 `BOUND_USER_UNRESOLVED_REASON` |
| `feature/wechat/.../channel/WeChatChannelPlugin.kt` | 修改（183→200 行）：删 `NEED_EXPLICIT_TARGET_REASON`，接线检查先于解析 |
| `core/domain/.../channel/ChannelAdapter.kt` | 修改（**仅 KDoc**，已核实） |
| `feature/wechat/src/test/.../WeChatChannelPluginTest.kt` | 重写（40→51 KB，36→44 用例） |

#### 设计优于要求之处：把「要么唯一确定，要么如实失败」**结构化**了

```kotlin
sealed class WeChatRecipientResolution {
    data class Resolved(val wechatUserId: String) : WeChatRecipientResolution()
    data class Failed(val reason: String) : WeChatRecipientResolution()
}
```

任务书只要求「不得静默挑一个」。可空字符串允许调用方随手兜底，**密封类不允许**——调用方必须处理 `Failed` 分支。这是把约束从「约定」升级成「类型」。
### 24.45 ✅ 调度者强制复跑验收（W1 修正）——**通过**

**命令**（先 `gradlew --stop` 释放守护进程文件锁）：

```
gradlew.bat :core:agent:testDebugUnitTest :feature:qqbot:testDebugUnitTest \
            :feature:wechat:testDebugUnitTest :core:wechat:testDebugUnitTest \
            --offline --console=plain --rerun-tasks
```

**结果**：`BUILD SUCCESSFUL in 6m 48s` / **`127 actionable tasks: 127 executed`** / `EXITCODE=0`。

`127/127 executed`（无一个 UP-TO-DATE）是**强制重跑**的机器证据，不是「看起来跑了」。

#### 计数对账（原始 `TEST-*.xml`）

| 模块 | tests | 基线 | failures | errors | skipped | 判定 |
|---|---|---|---|---|---|---|
| `core:agent` | **247** | 247 | 0 | 0 | 0 | ✅ |
| `feature:qqbot` | **70** | 70 | 0 | 0 | 0 | ✅ |
| `feature:wechat` | **93** | 93 | 0 | 0 | 0 | ✅ |
| `core:wechat` | **88** | 88 | 0 | 0 | 0 | ✅ |

**合计 498 个用例，0 失败 / 0 错误 / 0 跳过。**

四个模块**逐一等于基线**——包括修正未触碰的 `core:agent` 与 `feature:qqbot`（`core/domain` 的 KDoc 改动没有产生连带回归）。

#### 新鲜度证明

- 最新 `TEST-*.xml` mtime = **20:35:23**
- 最新源文件 mtime = **20:24:25**（`WeChatChannelPlugin.kt`）
- → 测试结果**晚于**全部源文件 ✅

#### 与子代理自报的一致性

`2efbe209` 自报 `feature:wechat` 93 / `core:wechat` 88——**与我的独立复跑逐字相同**。它的自报可信。

#### 它在回报里主动披露的两件事（均已核实）

1. **删除了 5 个 W1 旧用例**，逐条具名，且每个都是「前提被用户裁定推翻」而非弱化；新增 13 个逐条具名。→ 「改绿了」与「改弱了」在数字上无法区分，它用具名方式让两者可区分。
2. **修了一个替身保真度 bug**：`FakeSessionStore.getContextTokens` 原先返回带 `"accountId:"` 前缀的键，与真实现 `WeChatTokenStore.getContextTokens` 不一致，会让候选集判定失真。

#### 它发现并上报的并发写者 = 本调度者

它报告 `docs/reports/agent-freedom-framework-plan.md` 的 mtime 在它会话期间变动、并声明「该文件不是我改的……请父代理确认」。**那是我在写本文件。** 它的处置正确：发现自己没碰过的文件在变 → 不擅自处理 → 如实上报。

### 24.46 `2efbe209` 提出的两个遗留项（**需处置**）

#### (1) `YuNianApplication.kt:696` 旧注释与现状矛盾——**这是调度者的任务书疏漏**

原文仍写：「W1：target 为空时如实失败——一个伴侣可绑多个微信用户，**绝不广播**」。

该注释描述的是**已被用户裁定推翻**的前提。`app/**` 不在 `2efbe209` 的可动清单里，它没碰——**这个边界是我划的**，所以我欠这一处。

#### (2) 验收标准 6 的字面范围：真 `ChannelSendTool` 实例 e2e 在本任务边界内不可行

**架构约束**（AGENTS.md）：`feature:*` 不得依赖 `feature:*`，`core:*` 不得依赖 `feature:*`。而 `:feature:wechat` 既不依赖 `:core:agent`，`:core:agent` 也不得依赖 `:feature:wechat`——所以字面意义上「在真 `ChannelSendTool` 实例上跑 e2e」**不可能**在 `feature:wechat` 的测试源集里做到。

**它的等价做法**：复现 `ChannelSendTool` 省略 target 时构造的**精确请求形状**（`ChannelSendTool.kt:174` 把空白 target 归一成 `null`，`:193` 派发 → `ChannelOutboundRequest(channelKey="wechat", target=null, text=…)`），让它穿过真 `setup()` 注册的真订阅回调经 bail 派发链得到 `Sent`。

**它的判断是对的**：我的验收标准 6 措辞越界了。真类 e2e 需要 `:app` 同时依赖两者——**待授权**。

#### 附带发现（它主动指出，已核实）

`ChannelSendTool.kt:173` 原有注释「target 为空白 = 未指定 = 本通道绑定的宿主（用户本人）」**本来就与新语义一致**——「旧插件实现才是那个 outlier」。即：工具作者一开始就写对了，是**本调度者的 W1 前提**（§24.34）把两边弄成了矛盾。

#### 它**否决了我列的四个候选中的两个**，理由准确

| 候选 | 裁定 |
|---|---|
| `IlinkAccount.ilinkUserId` | ✅ **权威来源**（扫码 `confirmed` 返回的 `ilink_user_id`） |
| `getAllWechatUserMappings()` / `getWechatUserIdsForCompanionId()` | ❌ **本地可编辑**的用户→伴侣映射，「回答的是『这个用户对应哪个伴侣』，**不是**『协议允许我发给谁』」 |
| `getContextTokens(accountId)` | ⚠️ 只配当**兜底候选集**（可能为空或非唯一） |
| `getSessionAccount()` | ✅ 用于登录判定 |
### 24.47 ✅ App 装配编译验收——通过

```
gradlew.bat :app:compileDebugKotlin --offline --console=plain --rerun-tasks
→ BUILD SUCCESSFUL in 8m 29s / 203 actionable tasks: 203 executed / EXITCODE=0
```

### 24.48 ✅ 调度者补齐的两处收尾（L1，直接执行）

#### (1) `YuNianApplication.kt:696` 过时注释——**调度者任务书的疏漏，已修**

原文「W1：target 为空时如实失败——一个伴侣可绑多个微信用户，**绝不广播**」描述的是**已被用户裁定推翻**的前提。改为 5 行，说明修正后的语义（ilink 只允许发给绑定账号 → target 为空没有歧义 → 主路径；非绑定用户 → 如实失败，绝不改写）。

验证：`gradlew :app:compileDebugKotlin`（增量）→ `BUILD SUCCESSFUL in 51s` / **`1 executed, 202 up-to-date`** / `EXITCODE=0`。恰好只有那一个编译任务重跑，既证明改动只影响该任务，也证明它可编译。

#### (2) 两处过期 fixture：6 → 7，并**合并两个手工镜像**

事实核实（grep 全仓）：生产侧静态 `requiresConfirmation = true` 的工具是 **7 个**：

| # | 工具 | 落点 |
|---|---|---|
| 1-3 | `screen_tap` / `screen_swipe` / `screen_click_text` | `feature/skills/.../AccessibilityTools.kt:120/141/166` |
| 4 | `luckin_create_order` | `feature/coffee/.../LuckinCoffeeTools.kt:101`（**`get() = true`**） |
| 5-6 | `automation_create` / `automation_create_workflow` | `feature/automation/.../AutomationTools.kt:71/146`（**`get() = true`**） |
| **7** | **`send_channel_message`** | `core/agent/.../tools/ChannelSendTool.kt:117` |

（第 8 类是**动态**来源：`feature/mcp/.../McpToolAdapter.kt:21` `requiresConfirmation = mcpTool.needsApproval`，已由既有用例覆盖。）

**注意**：其中 3 个用 `get() = true`（计算属性）而非 `= true`——第一次用 `: Boolean = true` 的模式 grep 会**漏掉它们**。这是本轮的一个实测教训。

**修法**：把 `AgentToolCategoryConfirmationTest` 里那份本地列表改为**从唯一镜像派生**：

```kotlin
private val confirmationRequiredTools: List<FakeTool> =
    PRODUCTION_CONFIRM_TOOL_NAMES.map { FakeTool(name = it, requiresConfirmation = true) }
```

→ 原来**两处各自维护「6 个」**，这正是同一个新工具被两处同时漏掉的原因。现在只有一处需要维护。

**顺带修掉一个假测试**：原断言 `assertEquals(6, confirmationRequiredTools.size)` 是**断言自己的列表长度**，永远为真、永远不会失败。现在：

```kotlin
assertEquals(PRODUCTION_CONFIRM_TOOL_NAMES.size, confirmationRequiredTools.size)  // 两来源一致
assertEquals(7, PRODUCTION_CONFIRM_TOOL_NAMES.size)                              // 钉住数量
```

**验证**：`gradlew :core:agent:testDebugUnitTest` → `BUILD SUCCESSFUL in 43s` / `2 executed, 56 up-to-date` / `EXITCODE=0`；`core:agent` **247/0/0/0**（等于基线，无回归）；重命名后的用例 `七个 requiresConfirmation = true 的生产工具全部映射为 COMMERCE` **PASS**。

**结构性局限（已在 KDoc 写明，不是可修的缺陷）**：该镜像是**手工维护**的。架构上不可能自动校验——`core:*` 不得依赖 `feature:*`（AGENTS.md），而 7 个里 6 个住在 `feature:*` 中，`core:agent` 的测试源集看不到它们。**新增此类工具时必须同步该列表。**

### 24.49 临时产物清理

删除调度者自己的 6 个验收日志（`_acceptance_r1.log` / `_acceptance_app.log` / `_acceptance_app2.log` / `_acceptance_app3.log` / `_acceptance_app4.log` / `_acceptance_r2.log`，合计约 290 KB）。仓库根已无 `_*` 临时文件残留。

那个否决理由是我没想到的区分：**映射表是应用层的，「能发给谁」是协议层的。**

它还引用了仓内已有的协议事实作依据：`WeChatMessageRepository.isOutboundEcho` 的注记 + `WeChatOutboundEchoTest` —— `ilink_user_id` 是**对话对端**（与入站 `from_user_id` 同值），`ilink_bot_id` 才是 bot 自己。**没有凭猜测。**

#### 它发现并处理了一个我不知道的可达边界

`IlinkClientManager.pollLoginStatus` 用 `status.ilinkUserId.orEmpty()` 兜底 → **服务器没返回 `ilink_user_id` 时绑定用户是空串**。处置：退到「本账号持有 context_token 的微信用户」候选集，**恰好一个才认**，0 个或多个一律 `Failed`。

#### 一处顺序调整，理由正确

旧实现里 W1 检查（target 为空 → 失败）**先于**接线检查。现在**接线检查在前**。理由（其 KDoc 原文）：「收件人解析需要真实的会话存储，因此『未接线』判定**先于**解析：没有通道就没有『绑定的微信用户』可言，此时唯一的诚实答复是『未接线』」。

#### 「不扩契约」已遵守

其 KDoc 自述：「本类拿不到 `companionId`（`ChannelOutboundRequest` **刻意不含**该字段）……**本任务不扩契约：`ChannelOutboundRequest` 一个字段都没加。**」→ W2 仍待用户裁定，未被擅自引入。

### 24.43 ✅ 「测试是加强还是削弱」的核实结论：**加强**

§24.42 的修正反转了 4 个既有用例的断言方向（`target` 为空从「必须失败」变成「必须成功发给绑定用户」）。语义反转时改测试是对的，但「改绿了」和「改弱了」在数字上一样，必须逐条核实。**结论：加强。** 三条证据：

**1. 控制流级证明，不是返回值断言。** `ExplodingSender.sendText` 与 `ExplodingTransport.sendText` 都是**一旦被调用就 `throw AssertionError`**（后者还记录被调用过的收件人）。其注释自述理由：「**比断言返回值更硬**：真发出去会立刻炸成测试失败，而不是悄悄通过」。旧版只有「断言发送方未被调用」。

**2. 替身明确声明自己「不证明解析规则」。** `RecordingSender` 的 KDoc：「`resolveRecipient` 在这里刻意是**最笨的直通实现**……解析规则本身由 `fromTransport` 的生产实现承担，相关用例一律走 `realSender(...)` 驱动**真代码**，**不靠这个替身证明**」。

→ 这句话很关键：否则「替身说发给绑定用户 + 测试断言发给绑定用户」是**自证**，看起来绿、实际什么都没验证。

**3. 解析语义的用例跑的是生产代码。** `realSender(...)` 直接调 `WeChatChannelSender.fromTransport(...)`，只替换 `IlinkSessionStore` 与 `WeChatTransportPort` 两个契约层替身。

**没有任何安全用例被删除或放松。** 新增的「非绑定用户绝不落到传输层」「绝不静默改写」「多候选绝不静默挑一个」用的都是最硬的那一档证明手段。

### 24.44 修正后的测试读数（**子代理自跑，非验收**）

`2efbe209` 的构建：`BUILD SUCCESSFUL in 2m 2s`（20:24:45–20:26:45）。

| 模块 | 修正前 | 修正后 | 变化 | failures | errors | skipped |
|---|---|---|---|---|---|---|
| `feature:wechat` | 85 | **93** | **+8** | 0 | 0 | 0 |
| `core:wechat` | 88 | **88** | 0 | 0 | 0 | 0 |

`+8` 与「用例函数 36 → 44」**精确吻合**。`core:wechat` 零变化 = **无回归**。

**仍需调度者强制复跑验收**（R6）。
| `core/agent/src/test/kotlin/.../AgentToolCategoryConfirmationTest.kt` | `:43` | `/** 生产侧 6 个静态 \`requiresConfirmation = true\` 的工具（名字 / 工具集逐条对齐源码）。 */` |
| 同上 | `:44-51` | `confirmationRequiredTools` 列表，6 项 |
| 同上 | `:54` | 测试名 `六个 requiresConfirmation = true 的生产工具全部映射为 COMMERCE` |
| 同上 | `:55` | `assertEquals(6, confirmationRequiredTools.size)` —— **断言的是自己列表的长度，所以不会失败** |

**为什么这值得修**：夹具声称「逐条对齐源码」，而该声称现在**不成立**。后来的人读它会相信门控恰好覆盖 6 个工具。

这与本仓已纠正过的「假安全注释」（`QQBotChannelPlugin.kt:22-23` / `YuNianApplication.kt:664-665` 曾声称工厂是惰性的，实为立即调用）**是同一类问题**：**一句不成立的声称比没有声称更危险**。

**处置**：待办——把 `send_channel_message` 加入两处夹具列表、把 6 改为 7、并让测试真正断言「生产工具都在列表里」。**非阻塞**，因为它不影响门控的实际行为（门控读的是 `AiTool.requiresConfirmation`，不读夹具）。

### 24.34 教训：凭想象设定安全前提，会造出比它想防的问题更糟的问题

W1 的完整因果链：
1. 我看到 `WeChatOutboundPortImpl.resolveUserIds` 在无显式收件人时返回**全部**绑定用户，判断「广播给多个真人」是危险；
2. 我据此选了 W1（`target` 必填），并把理由写成「绝不放行成发给所有人」；
3. 但**我从未核实 ilink 协议是否允许发给非绑定用户**——而这正是决定该危险是否存在的唯一事实；
4. 事实是：协议不允许。危险不存在。而 W1 让主路径**直接失败**。

→ **教训：安全前提必须建立在已验证的协议事实上，不能建立在「看起来可能危险」上。** 一个基于错误前提的保守默认，会造成比它想防的问题更严重的功能缺陷。

（对照：§24.29 的 P4-2 偏离也是同一类问题——我写的并发判据不可证伪。两次都是**我**的前提有问题，子代理的判断更好。）
---

## §25 新需求：统一「插件设置」入口（用户 2026-09-29 提出）

### 25.1 用户原话（权威）

> QQ平台不支持自然做不了，接受。
> 启用通道原先在，我-设置-通用-微信设置，QQ机器人侧绑定。
> 现在应该统一成插件启用和插件配置。

> 移除旧UI位置（我-设置-通用-微信设置，QQ机器人侧绑定）（我-API设置-能力授权），统一新入口，我界面，API设置卡片下方，主题模式上方。名称为插件设置。列出所有插件，并且有个搜索框，在页面里做一个上方导航栏，（消息通道，通用插件）

补充裁定（逐条）：

| 问题 | 用户裁定 |
|---|---|
| 「插件配置」形态 | **搬进新页面，删原页面** |
| 「停用」生效时机 | **运行时卸载插件** |
| 「通用插件」清单范围 | **只列出 cordis 插件注册的** |
| 新页面 UI 贡献机制 | **方案 B：插件自贡献 UI**（用户原话「最符合 cordis 语义」） |

### 25.2 分诊：**L2**

跨 6 个模块（`core:ui-common` / `feature:settings` / `feature:profile` / `feature:wechat` / `feature:qqbot` / `app`），多交付物（新建页面 + 新建 UI 贡献契约 + 搬运两个设置页 + 删除三处旧入口 + 启停持久化），且存在关键歧义 → 走完整流程：**访谈 → 规划 → 多子代理并行 → 分层验收**。

### 25.3 只读勘察结论（子代理 `de86cc22`，已独立复核）

#### ✅ 方案 B 的成本很低：`core:ui-common` 已是四个模块的依赖

```
feature:wechat   → implementation(project(":core:ui-common")) ✅
feature:qqbot    → implementation(project(":core:ui-common")) ✅
feature:settings → implementation(project(":core:ui-common")) ✅ + core:domain ✅ + core:agent ✅
feature:profile  → implementation(project(":core:ui-common")) ✅ + core:domain ✅
```

→ 贡献契约放 `core:ui-common` **不需要新增任何模块依赖**。

#### ✅ 方案 B 恰好化解了一个严重风险

勘察发现：`WeChatSettingsScreen` / `QQBotSettingsScreen` 的**唯一挂载点**是 `MainNavGraph.kt:356-361` / `:365-367`。物理删除 → 两个设置页彻底不可达，**连 `WeChatBindScreen`（扫码绑定，唯一入口 `:359` 的 `onBindClick`）一起失联**。

方案 B 让 `feature:wechat` **自己注册**自己的设置区，`feature:settings` **从不 import** 它——既不违反 `feature ↛ feature`，也不需要 `app` 当中间人。

#### ✅ `PluginHost` 的四个核心动作**零新增能力**

| 动作 | API | 行 |
|---|---|---|
| 列出全部 | `plugins(): List<LianYuPlugin>`（按 id 排序） | `Plugin.kt:380` |
| 分类过滤 | `pluginsOf(kind): List<LianYuPlugin>` | `:383` |
| 装载（带配置） | `load(id, configJson): PluginLoadResult` | `:386` |
| **运行时卸载** | `unload(id): Boolean` | `:389` |
| 装载态查询 | `isLoaded(id)` / `loadedIds()` | `:392` / `:395` |

`PluginHostImpl` 全部实现（`:81/:83/:86/:134/:143/:145`）。已由 `ServiceRegistry.registerSingleton(PluginHost::class.java)` 注册（`YuNianApplication.kt:713`），feature 模块可直接取用。

#### 当前实注册的 7 个插件（页面初始内容）

| id | name | kind | 分类归属 |
|---|---|---|---|
| `channel.qqbot` | QQ 机器人通道 | ADAPTER | 消息通道 |
| `channel.wechat` | 微信通道 | ADAPTER | 消息通道 |
| `coffee.luckin` | 瑞幸咖啡 | TOOL | 通用插件 |
| `automation.core` | 自动化工具 | TOOL | 通用插件 |
| `message.send` | 消息发送 | TOOL | 通用插件 |
| `skill.builtin_chat_protocol` | 内置聊天工具协议技能 | SKILL | 通用插件 |
| `sticker.preference` | 表情包偏好引擎 | STICKER | 通用插件 |

> **（2026-10-03 更正）** 上表第 3 行 `coffee.luckin` 已随「瑞幸咖啡」功能**整体下线**移除：`feature/coffee` 模块删除、蓝图 `app/src/main/assets/blueprints/default.json` 的该条目删除、`core:domain` 的 `CoffeeOrderProvider` 契约删除、`YuNianApplication` 里 `pluginHost.register(CoffeePlugin(...))` 及其 `ServiceRegistry` 绑定删除。
> **当前在册插件为 6 个 + `ui.assists`**（即上表去掉 `coffee.luckin` 后的 6 条，加上上表成文后才新增的 `ui.assists`）：`channel.qqbot` / `channel.wechat` / `automation.core` / `message.send` / `skill.builtin_chat_protocol` / `sticker.preference` / `ui.assists`。
> 本节其余内容与下方「缺口 1 / 缺口 2」是 2026-08 的历史记录，按原样保留，不作当前态断言。

（`PluginKind` 枚举：TOOL / SKILL / STICKER / ADAPTER / PIPELINE。用户的两分类与枚举**不是一一对应**：消息通道 = ADAPTER，通用插件 = 其余全部。目前仓里**没有 PIPELINE 插件**。）

#### ⚠️ 两处必须上报的缺口（已由调度者独立复核）

**缺口 1：「停用」没有持久化。** 全仓 `blueprint` 相关代码只有只读加载与 `loadBlueprint` 实现，**没有任何写回**。蓝图 `default.json` 是只读资产。→ 「运行时卸载」一旦重启即**回滚**，除非新增持久化存储。**这是欺骗性行为**（用户以为关了，重启又开了）。

**缺口 2：「能力授权」没有路由。** 它不是导航目的地，而是 `SettingsScreen.kt:631-640` 的同页全屏覆盖层（状态 `:110`，卡片 `:414-475`，文案 `:455`/`:461`）。删掉卡片与覆盖层后 `CapabilityGrantScreen` **完全无法访问**（全仓无第二个入口）。→ 必须一并搬到新页面，否则功能失联。

#### ✅ 好消息：删旧路由不破坏任何测试

全仓 186 个测试文件，grep `wechat_settings` / `qqbot_settings` / `settings_general_category` / `MainRoute` 在 `**/src/test/**` 下**零命中**（调度者独立复核确认）。唯一影响是**编译期**：须同步删 `MainNavGraph` 的 import 与 `composable` 块。
### 25.6 ✅ 访谈收敛（用户逐条裁定，前沿已空）

| # | 问题 | 裁定 |
|---|---|---|
| Q1 | 点一个插件之后 | **(a) 列表项展开，内联显示配置** |
| Q2 | 「能力授权」搬去哪 | **(b) 并入「通用插件」**（理由：在 cordis 架构下，工具本身也是插件） |
| Q3 | 停用是否跨重启保留 | **(a) 要** → 需新增持久化 KV |
| Q4 | 上方导航栏形态 | **(a) `TabRow` + Pager 联动** |
| Q5 | `message.send` 归哪类 | **「cordis 万物皆是插件」** → 按 kind 归「通用插件」，不设特例 |
| Q6 | 工具授权的形态 | **(a) 由 `feature:settings` 贡献一个保留 id 的设置区** |

**调度者的一处判断被用户纠正（记档）**：我最初主张「`MainNavGraph.kt:356-361`/`:365-367` 两个 composable 必须保留，否则扫码绑定失联」。用户纠正：**「微信扫码绑定，QQ绑定，工具授权未必一定要依赖 feature:*。这些功能也可以是插件」**。

→ **我错了。** `WeChatBindScreen` 本来就住在 `feature:wechat` 里，它不可达只是因为**唯一入口是 app 的一条路由**。只要插件贡献的设置区**自带内部导航**（Compose 状态，不走 app 路由），`app` 就完全不需要知道这些概念。

### 25.7 最终设计

#### 契约层（`core:ui-common`）

```kotlin
enum class PluginSettingsCategory { CHANNEL, GENERAL }

interface PluginSettingsSection {
    val pluginId: String
    val category: PluginSettingsCategory
    @Composable fun Content()
}

object PluginSettingsSections { register / unregister / all / forPlugin }
```

注意：`core:ui-common` **只依赖 `core:common`**，不依赖 `core:domain`——所以 `PluginSettingsCategory` 必须**自足**，不得引用 `PluginKind`。

#### 贡献注册点：插件自己的 `setup()`（**调度者主动改进**）

不放在 `app` 启动时，而是放在插件的 `setup()` 内，用 `ctx.effect` 撤销：

```kotlin
override fun setup(ctx: PluginContext) {
    registry.register(...)                       // 既有
    ctx.effect({ PluginSettingsSections.unregister(ID) }, "settings-section")
    PluginSettingsSections.register(WeChatSettingsSection())
}
```

→ 「插件被停用 → 它的设置区自动消失」成为**结构保证**，不靠两边同步。`app` **完全不需要**知道任何插件设置区的存在。

→ 且行本身**不消失**（列表来自 `PluginHost.plugins()`，含未装载插件），所以用户仍能重新启用它——只是展开后没有配置项。

#### 页面（`PluginSettingsScreen`，住 `feature:settings`）

```
┌─ GlassTopBar「插件设置」
├─ 搜索框（core:ui-common 新增共享组件）
├─ TabRow：消息通道 │ 通用插件   （+ HorizontalPager 联动）
└─ 列表：每行 = 名称 · kind 徽标 · [启用开关] · 展开箭头
        展开 = PluginSettingsSections.forPlugin(id)?.Content() ?: 「此插件无可配置项」
```

- **消息通道** = `PluginKind.ADAPTER` → `channel.wechat` / `channel.qqbot`
- **通用插件** = 其余 kind + **一个保留 id 的「工具授权」项**（`tool.grant`，展开 = 现有 `CapabilityGrantScreen` 内容原样搬入）

#### 启停与持久化

`PluginHost.isLoaded(id)` 读状态 → 开关调 `unload(id)` / `load(id, configJson)` → 写持久化 KV（`core:domain` 定义契约，`app` 实现，`app` 启动时在 `loadDefaultBlueprint` **之后**套用覆盖）。

#### 旧入口处置（**修正后**）

| 旧位置 | 处置 |
|---|---|
| 我-设置-通用-微信设置 | 删条目 `GeneralSettingsScreen.kt:196-201` |
| 我-设置-通用-QQ机器人 | 删条目 `:202-207` |
| 我-API设置-能力授权 | 删卡片 `SettingsScreen.kt:414-475` + 同页覆盖层 `:631-640` |
| 我界面 | **新增**「插件设置」，插在 API 设置 `ProfileScreen.kt:204` 与主题模式 `:205` 之间 |
| `MainRoute.WeChatSettings` / `WeChatBind` / `QQBotSettings` | **全部删除**（修正：绑定流程移入插件设置区内部导航） |

→ `app` 从「知道 4 个通道相关路由」收敛到「知道 1 个插件设置路由」。

### 25.8 任务拆分（两波）

| 波 | 任务 | 边界 | 依赖 |
|---|---|---|---|
| 1 | **T1 契约层** | `core/ui-common/**` + `core/domain/**` | 无 |
| 2 | T2 页面 | `feature/settings/**` | T1 |
| | T3 两个插件设置区 | `feature/wechat/**` + `feature/qqbot/**` | T1 |
| | T4 入口与路由 | `app/**` + `feature/profile/**` | T1 |

### 25.9 统一验收标准（每任务必过）

1. 本模块 `testDebugUnitTest` 全绿，计数与基线对账
2. `:app:compileDebugKotlin` 通过
3. **架构红线**：新代码零跨 feature import；`core:*` 不依赖 `feature:*`
4. **功能不丢**：微信扫码绑定、QQ 绑定、工具授权三处**在新页面内仍可达**
5. 保活与时序：`setup` 之外零新增后台循环
6. `gradlew --stop` 后再 `--rerun-tasks`；守护进程日志行序判定无并发构建

#### 可复用的现有范式（本仓**没有**共享搜索框与 Tab 组件）

| 需要什么 | 可模仿的现成实现 |
|---|---|
| 搜索框 | `feature/companion/.../ContactsScreen.kt:140-150`（裸 `OutlinedTextField`）；图标 `AppIcons.Search` @ `core/ui-common/.../icon/AppIcons.kt:75` |
| 顶部 Tab | `feature/memory/.../MemoryScreen.kt:132-193`（`TabRow` + `SecondaryIndicator` + `tabIndicatorOffset`，与 `HorizontalPager` 双向同步） |
| Chip 分类行 | `MemoryScreen.kt:576-600` |
| 「列表+搜索+分类」整页 | `ContactsScreen.kt`（首选模仿对象） |
| 页面骨架 | `GlassPageScaffold` + `GlassTopBar`（`core:ui-common/.../glass/`） |
| 分组卡片 | `GeneralSettingsScreen.kt:393-417` `SettingsCategoryList`（在 `feature:profile`，跨模块不可用 → 需上移或复制） |

### 25.4 待用户裁定的前沿（**访谈未收敛，不得开始实现**）

| # | 问题 | 推荐 |
|---|---|---|
| Q1 | 点一个插件之后发生什么？ | (a) 列表项展开、内联显示配置（符合「搬进新页面」） |
| Q2 | 「能力授权」搬去哪？（它不是插件，塞不进两分类） | (a) 新页面加第三个分类「工具授权」 |
| Q3 | 停用是否跨重启保留？ | (a) 要 → 需新增持久化 KV |
| Q4 | 上方导航栏形态？ | (a) `TabRow` + Pager 联动（仿 `MemoryScreen`） |
| Q5 | `message.send`（TOOL，但语义上是通道基础设施）归哪类？ | 按 kind 归「通用插件」，不引入特例 |

### 25.5 新建页面位置

**`feature:settings`**（不新建模块——`settings.gradle.kts` 属禁改区）。依据：它已依赖 `core:ui-common` + `core:domain` + `core:agent`，且已有 `capability/`（工具授权）这一**同形态先例**（列出一批条目 + 逐条开关）。

入口在「我」页（`feature:profile`），导航回调由 `:app` 注入（`MainNavGraph.kt:473-485`）——`:app` 同时依赖所有 feature，是放置「插件 id → 路由」知识的合法位置。
### 25.10 T1 契约层 —— 已验收（调度者强制重跑）

**交付**：`core:ui-common` 的 `PluginSettingsCategory` / `PluginSettingsSection` / `PluginSettingsSections` + `core:domain` 的 `PluginEnablementStore`（接口），共 5 个新文件（含 2 个测试）。

**调度者独立验证**：`gradlew --stop` 后 `--rerun-tasks` → `BUILD SUCCESSFUL in 2m 21s`，`EXITCODE=0`；原始 XML：`PluginSettingsSectionsTest` 10/0/0/0、`PluginEnablementStoreTest` 5/0/0/0 → **15 tests / 0 失败**；XML mtime `22:21:46` > 最新源文件 `22:19:16`（新鲜度对账通过）。

**调度者做的两处修正**：
1. **修正子代理的 id 文档错误**：原 KDoc 把插件 id 举例为 `qqbot` / `wechat`，**真实 id 是 `channel.qqbot` / `channel.wechat`**。这不是笔误——插件 id 与「通道键」（`ChannelKeys`）是**两套 id 空间**，照抄会导致 `forPlugin()` 静默查不到、设置区永不显示。已改写 KDoc 并加入显式警告。
2. **授权 `testImplementation(libs.junit)`**：调度者原本一刀切禁止改 `build.gradle.kts`，但该禁令本意是防**模块依赖**漂移，却连带禁掉了**测试依赖**——而 AGP 9.2.1 不再自动注入 junit（全仓 16 个有测试的模块无一例外手写）。**导致调度者自己的验收标准 2 不可达。** 子代理实测「加 2 行即 15/15 全绿」后**主动撤回该行并上报冲突**——这是正确做法。

### 25.11 T2 统一页面 —— 已验收（调度者强制重跑）

**交付**：`feature/settings` 的 `PluginSettingsBoard.kt`（纯逻辑，244 行）/ `PluginSettingsScreen.kt`（461 行）/ `PluginSettingsRows.kt`（198 行）/ `PluginSettingsBoardTest.kt`（33 条）+ 5 语言 strings。

**调度者独立验证**：`:feature:settings:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL in 5m 49s`，`EXITCODE=0`；原始 XML：`CapabilityGrantBoardTest` 28、`PluginSettingsBoardTest` **33**、`MimoCloneSampleCleanupTest` 3、`WorldbookTransferTest` 7 → **71 tests / 0 失败**（基线 38 → 净增 33，与子代理自述逐字吻合）；`:app:compileDebugKotlin` `EXITCODE=0`；XML mtime `22:57:19` > 最新源文件 `22:41:26`。

**子代理的一处设计纠正（调度者认可）**：任务书要求 `tool.grant` 展开后**内联渲染** `CapabilityGrantScreen`。子代理实测该做法会**嵌套 `LazyColumn`**（运行期崩溃），且破坏液态玻璃所需的同窗口背景采样；改为**页内全屏浮层**，结构上与既有「视觉模型 / AI 生图」子页面同构，同时仍按要求注册进 `PluginSettingsSections`。**任务书本身有缺陷，子代理判断正确。**

**另**：该行**不给 Switch**（「能力授权」不是 `PluginHost` 里的插件，没有装载态，给开关就是假开关），改为箭头。

### 25.12 ⚠️ 调度者的方法论错误：陈旧行号污染了两份任务书（留档）

**现象**：勘察子代理与 T2 子代理**都**报告 `SettingsScreen.kt` 含「能力授权」卡片（`:414-475`）、状态 `:110`、覆盖层 `:631-640`。T2 据此执行了三次删除 edit，却发现磁盘上本就没有这些代码，且 `git diff` 只有 1 行。

**定性（调度者实测）**：

| 事实 | 值 |
|---|---|
| `SettingsScreen.kt` 磁盘 / HEAD 行数 | 688 / 688（一致） |
| `git diff --stat` | 1 insertion, 1 deletion（一句注释） |
| HEAD 是否含能力授权 | **不含** |
| 该文件最近一次提交 | `dc2a258d merge: 合并 origin/linzihan —— 保留 Cordis Agent 架构 + 采纳远端精简/性能/安全删除` |
| 本会话 reflog 条目 | **零**（无任何提交） |
| `CapabilityGrantScreen` 的调用点（T2 之前） | **零个** |

**根因**：那张卡片在本会话开始**之前**已被上述合并删除。但调度者的**压缩摘要**里仍保留着合并前的行号（`SettingsScreen.kt:461`），而调度者把**这些行号写进了两份任务书**——勘察任务书写「能力授权 entry（`SettingsScreen.kt:461` area）」，T2 任务书写「删卡片 `:414-475` + 覆盖层 `:631-640` + 状态 `:110`」。两个子代理照着调度者给的行号去找，然后**把调度者的行号当成自己的发现回报给调度者**。

**教训（重要）**：
1. **任务书里引用行号 = 给子代理注入前提。** 子代理会顺着它找，并把结果当成独立发现回报——形成**确认偏误回路**，看起来像「两个独立来源互相印证」，实际是同一个陈旧源头被回声两次。
2. 任务书应引用**符号名与语义**（「能力授权入口」「`CapabilityGrantScreen` 的调用点」），行号只作为**待核实的提示**，并明确写「行号可能过期，以符号搜索为准」。
3. 调度者自己的压缩摘要**不是可信来源**——它是二手信息，必须回到工作区核实后才能写进任务书。

**附带发现（对用户有价值）**：用户要求「移除旧UI位置（我-API设置-能力授权）」，但**该入口在本会话开始前就已不存在**——`CapabilityGrantScreen` 在 T2 之前**零调用点**，即整个能力授权功能当时是**不可达**的。T2 的工作实际上**恢复**了这个孤儿功能的可达性（经由新页面的 `tool.grant` 行）。

### 25.13 T3 —— 两个通道插件自贡献设置区（进行中）

**边界**：`feature/wechat/**` + `feature:qqbot/**`。

**调度者的解耦决定**：T3 **不修改** `WeChatSettingsScreen` / `QQBotSettingsScreen` 的既有函数签名，只**新增**设置区来组合它们。理由：`MainNavGraph.kt:356-361` / `:365-367` 当前仍在调用它们（含 `:359` 的 `onBindClick`），若改签名则 `app` 立刻编译失败，而 T3 无权改 `app`（T4 的边界）。保持签名不变即可让 T3 与 T4 互不阻塞。

绑定流程改用设置区**内部 Compose 状态**承载（用户裁定：「微信扫码绑定、QQ 绑定这些功能也可以是插件」）。

### 25.14 T4 —— 入口与路由（待 T3 后派发）
### 25.16 T3 —— 已验收（调度者强制重跑）

**交付**：`feature/wechat` 的 `WeChatSettingsSection.kt`（103 行）、`feature:qqbot` 的 `QQBotSettingsSection.kt`（73 行）+ 两个 `*ChannelPlugin.kt` 的 `setup()` 追加注册与 `ctx.effect` + 两个测试文件（+4 用例）。

**调度者独立验证**：`--rerun-tasks` → `BUILD SUCCESSFUL in 5m 7s`，**`107 actionable tasks: 107 executed`**（完全强制），`EXITCODE=0`；原始 XML：`feature:wechat` **95**/0、`feature:qqbot` **72**/0 → **167 tests / 0 失败**（基线 163 → 净增 4）；`:app:compileDebugKotlin` `EXITCODE=0`；XML mtime `23:15:41` > 最新源文件 `23:06:33`。

**调度者从原始 XML 逐字读出新用例确实执行且 PASS**：
```
wechat: 装载后注册 channel_wechat 设置区，卸载后设置区消失
wechat: 设置区 id 逐字等于插件 id 而不是通道键
qqbot:  装载后注册 channel_qqbot 设置区，卸载后设置区消失
qqbot:  设置区 id 逐字等于插件 id 而不是通道键
```
→ **核心契约被实测钉住**：`host.unload()` 之后 `PluginSettingsSections.forPlugin(ID)` 返回 null。「插件停用 → 设置区自动消失」不是纸面设计。

#### ⚠️ 调度者的验收标准自相矛盾（留档）

调度者要求 T3 **同时**满足：§1「必须在 `setup()` 加 `ctx.effect` 注册注销」与 §4「既有测试零回归」。但既有断言恰好锁死了 effect 数量：
```
QQBotChannelPluginTest.kt:325   assertEquals("必须登记恰好两条撤销副作用", 2, ctx.effects.size)
WeChatChannelPluginTest.kt:619  assertEquals("必须登记恰好一条撤销副作用（订阅另有一条）", 2, ctx.effects.size)
```
加第三条 effect **必然**让它们变红。**两个要求不可能同时成立。**

**子代理的处理（调度者裁定接受）**：数字 2→3，并**加强**断言——从「只数数量」变成「按位置 + 标签逐条断言」（`assertEquals("settings-section", ctx.effects[2].second)`）。**断言意图被保留，还多了一层保护。**

**教训**：调度者写「零回归」这类绝对化验收标准前，必须先确认既有断言是否与该批改动**在字面上相容**。绝对化措辞会把子代理逼进「要么违约束、要么削弱测试」的二选一。

### 25.17 用户裁定：契约加「呈现方式」标志（方案 A）

**问题**：`PluginSettingsRows.kt` 把 `section.Content()` 渲染在 `LazyColumn` 的 item 里，而 `WeChatSettingsScreen` / `QQBotSettingsScreen` 都是整页组件（`GlassPageScaffold` = 背景层 + Scaffold + `Column(verticalScroll)`）。**内层滚动容器在 `LazyColumn` item 里会拿到无界最大高度约束 → 运行期崩溃。**

**决定性旁证**：T2 自己在 `tool.grant` 上刻意避开了同一个坑（`PluginSettingsScreen.kt:204-213` 逐字记录了理由）。T3 指出「同样的理由逐字适用于我这两个设置区」——**正确**。根因是**契约缺口**：无参的 `Content()` 表达不了「请以整页渲染我」。

**用户裁定**：方案 A —— 契约加「呈现方式」标志。

**设计要点（调度者定）**：
- `enum class PluginSettingsPresentation { INLINE, FULL_PAGE }`
- **默认 `FULL_PAGE`**，因为它是**安全默认**：错选 `INLINE` 会崩溃，错选 `FULL_PAGE` 只是呈现方式变整页。**fail-safe 方向是「宁可整页，不可崩溃」。**
- 判定标准：设置区若自带 `Scaffold` / `LazyColumn` / `Column(verticalScroll)` / 依赖 `LocalPageBackdrop`，就必须是 `FULL_PAGE`。
- `tool.grant` **也必须声明 `FULL_PAGE` 并走同一套分支**，消除「两个代码路径做同一件事」的重复。

### 25.18 T4 —— 呈现方式契约扩展（进行中）

**边界**：`core/ui-common/**` + `feature/settings/**` + `feature/wechat/**` + `feature:qqbot/**`。

**任务书里加了一条防重演条款**：若某个既有断言与本次改动在字面上冲突，**优先保留断言的「意图」并加强它**，然后在报告里明确指出哪条断言、为什么必须改、改成了什么；**不得为凑「零回归」而削弱断言，也不得默默改掉**。

### 25.19 T5 —— 入口与路由 + 启停持久化（待 T4 后派发）

**边界**：`app/**` + `feature/profile/**`。与 T4 **源码上互不重叠**，但**必须串行**（都要跑 gradle，并发会撞 jar 文件锁）。

内容：`MainRoute.PluginSettings` + `fromRoute` 映射；`MainNavGraph` 的 import / `composable` / `ProfileScreen(onPluginSettingsClick=…)`；`ProfileScreen` 新增形参与条目（插在 API 设置 `:204` 与主题模式 `:205` 之间）；删除 `GeneralSettingsScreen.kt:196-207` 的两条旧条目与其形参；删除 `MainRoute.WeChatSettings` / `WeChatBind` / `QQBotSettings` 及对应 `composable`；`:app` 实现 `PluginEnablementStore`（底层 `AppMetaStore` KV）并在 `loadDefaultBlueprint` **之后**套用停用覆盖。

⚠️ **行号同样来自调度者的二手摘要，可能过期。** 派发时须以**符号名与语义**为准，并注明「行号可能过期，以符号搜索为准」——这是 §25.12 的教训。

**边界**：`app/**` + `feature/profile/**`。

内容：`MainRoute.PluginSettings` + `fromRoute` 映射；`MainNavGraph` 的 import / `composable` / `ProfileScreen(onPluginSettingsClick=...)`；`ProfileScreen` 新增形参与条目（插在 API 设置 `:204` 与主题模式 `:205` 之间）；删除 `GeneralSettingsScreen.kt` 的两条旧条目与其形参；删除 `MainRoute.WeChatSettings` / `WeChatBind` / `QQBotSettings` 及对应 `composable`；`:app` 实现 `PluginEnablementStore`（底层 `AppMetaStore` KV）并在 `loadDefaultBlueprint` **之后**套用停用覆盖。

### 25.15 并发纪律（贯穿本批）

**T2 / T3 / T4 不得并行**——它们都要跑 gradle，而并发 gradle 会撞 jar 文件锁（本会话已撞过一次：`bundleLibCompileToJarDebug\classes.jar: 另一个程序正在使用此文件`）。改为**串行派发**。判定并发用 `~/.gradle/daemon/9.4.1/*.out.log` 的构建事件行序，不用 `Get-Process java`（常驻守护进程即使空闲也存在）。
### 25.20 T4 —— 已验收（调度者强制重跑）

**交付**：契约新增 `PluginSettingsPresentation { INLINE, FULL_PAGE }`（默认 `FULL_PAGE`）；页面按呈现方式分流（判断全部落在纯逻辑 `PluginSettingsBoard`，新增 `PluginRowAction { TOGGLE_INLINE, OPEN_FULL_PAGE }` 与派生的 `hasSection` / `action` / `rendersInline`）；`tool.grant` 改为声明 `FULL_PAGE` 走同一分支；两个通道设置区声明 `FULL_PAGE`。共 15 个文件。

**调度者独立验证**：四模块测试 `--rerun-tasks` → `BUILD SUCCESSFUL in 5m 58s`，`137 actionable tasks: 137 executed`；原始 XML：core:ui-common **15**、feature:settings **80**、feature:wechat **96**、feature:qqbot **73** → **264 tests / 0 失败**（基线 248 → 净增 16）；`:app:compileDebugKotlin --rerun-tasks` → `BUILD SUCCESSFUL in 6m 29s`，`203 executed`，`EXITCODE=0`；XML `23:45:57`–`23:49:43` > 最新源文件 `23:32:34`；新测试类 `PluginSettingsPresentationTest tests=5 f=0` 确实执行。

**调度者的架构断言检查（全部成立）**：

| 断言 | 证据 |
|---|---|
| 页面层不硬编码通道插件 id | 仅 `PluginSettingsBoard.kt:81` 的 `TOOL_GRANT_ID` 常量；无 `"channel.wechat"` / `"channel.qqbot"` |
| `PluginSettingsRows.kt` 不含呈现方式判断 | grep `presentation` **零命中**——判断已上移到 Board |
| 三个生产实现方声明 `FULL_PAGE` | `WeChatSettingsSection.kt:84` / `QQBotSettingsSection.kt:79` / `PluginSettingsScreen.kt:241` |
| 契约默认值 = `FULL_PAGE` | `PluginSettingsSection.kt:141 get() = PluginSettingsPresentation.FULL_PAGE` |

契约 KDoc（`:46-57`）把**代价不对称**写清了：该 `FULL_PAGE` 却写 `INLINE` → 整页设置区被塞进列表项 → **运行期崩溃**；该 `INLINE` 却写 `FULL_PAGE` → 只多一层浮层 → **不崩**。所以默认值必须落在「不会崩」的一侧。

**子代理主动声明的一处诚实取舍**：`INLINE` 现在是**死代码**（生产 3 个实现方全 `FULL_PAGE`，默认值也是 `FULL_PAGE`）。它**没有**为了「让 INLINE 活起来」而伪造一个内联设置区——这是正确做法。将来出现真正无滚动容器的设置区时它才会生效。

### 25.21 ⚠️ 调度者的编排错误（第二次踩同一个坑，留档）

**现象**：调度者把「四模块测试 `--rerun-tasks`」与「`:app:compileDebugKotlin --rerun-tasks`」放在**同一个 pwsh 进程里连着跑**，中间没有 `gradlew --stop` → 第二次 `BUILD FAILED in 27s`，`EXITCODE=1`。

**定性**：**环境性失败，非代码缺陷。** 证据：
1. 单独跑增量 `:app:compileDebugKotlin` → `BUILD SUCCESSFUL`，且任务状态是 **`UP-TO-DATE`**——Gradle 的 up-to-date 检查通过，说明输入自上次成功编译以来未变，**代码本身能编译**；
2. 补一次 `--stop` 后单独跑 `--rerun-tasks` → `BUILD SUCCESSFUL in 6m 29s`，`203 executed`，**零 FAILED 任务**；
3. `27s` 就失败，对全量 app 编译（正常 6–8 分钟）而言是**早期失败**，符合资源锁而非编译错误的特征。

**根因**：守护进程仍持有上一轮 `bundleLibCompileToJarDebug` 的 jar 文件锁（`另一个程序正在使用此文件`）——**这正是本会话早先已经记录过的失败模式**。

**已固化的纪律（已写进 T5 任务书）**：
> 每次 `--rerun-tasks` 之前单独 `gradlew.bat --stop`，**且两次强制构建之间必须隔一次 `--stop`**。

**方法论教训**：验收失败时的**第一动作应是「判断它是不是环境性的」**，而不是直接认定子代理交付有缺陷。本次差点因此误判 T4——若非先跑增量看到 `UP-TO-DATE`，就会把一次锁冲突当成代码回归。

### 25.22 T5 —— 入口与路由 + 启停持久化（进行中，最后一个实现任务）

**边界**：`app/**` + `feature/profile/**`。

**内容**：`MainRoute.PluginSettings` + `fromRoute` 映射；`MainNavGraph` 的 import / `composable` / `ProfileScreen(onPluginSettingsClick=…)`；`ProfileScreen` 新增形参与条目（插在 API 设置与主题模式之间）；删除两条旧条目与形参；删除 `MainRoute.WeChatSettings` / `WeChatBind` / `QQBotSettings` 及三个 `composable`；`:app` 实现 `PluginEnablementStore`（底层 `AppMetaStore`，照 `CapabilityGrantStoreImpl` 先例）并在 `loadDefaultBlueprint` **之后**异步套用停用覆盖。

**任务书里的三处防御**：
1. **行号一律标注「可能过期，以符号搜索为准」**——§25.12 教训的直接应用；
2. **并发/锁纪律写进硬性约束 6**，并附上本会话两次踩坑的具体现象；
3. **预先说明 `:app:testDebugUnitTest` 的 6 条既存失败与本次无关**，要求先测基线再对比，只要**不新增**失败即可，不要去修它们——避免子代理把无关失败当成自己的回归。

⚠️ **`WeChatSettingsScreen` / `QQBotSettingsScreen` / `WeChatBindScreen` 的 composable 本体不删、签名不改**——它们仍被设置区调用；删的只是 app 层的路由与挂载点。
### 25.23 T5 —— 已验收（调度者强制重跑）
---

## 26. 技术债批次（debug APK 之前的清理）

### 26.1 为什么先勘察而不是直接派活

调度者手上的债务清单**全部来自压缩摘要**，而本会话已因此犯错**两次**（把陈旧行号写进任务书，子代理照着找、再把调度者的错误当成自己的发现回报）。

**故先派只读勘察逐条判定「成立 / 已失效 / 部分成立 / 无法判定」，并要求贴出亲眼读到的原文。** 勘察结果：12 条中**成立 4 条、部分成立 6 条、不成立 2 条**，另**纠正调度者 5 处前提**。

### 26.2 勘察纠正的调度者前提（留档）

| 调度者的说法 | 真相 |
|---|---|
| `ChannelRegistryImpl` 有回滚缺口 | **不成立**。它**没有 `setup()`**（那是插件的职责）；条目**最后才写**；`ctx.effect` 逆序回滚 + `isLoaded` 查询过滤双重覆盖 |
| `msg_seq` 应在每个 `msg_id` 重置 | **方向反了**。QQ 要求同一 `msg_id` 内唯一递增，全局自增天然满足；**重置反而会违反唯一性**。现状是对的 |
| `splitIntoSentences` 把句子切碎 | 只按**句末**标点切，不切逗号。但**发现一个真 bug**：不跳过连续分隔符 ⇒「你好！！」会**真的发出一条只含「！」的消息** |
| Room 版本 19（AGENTS）vs 17（CLAUDE） | **两个都错，真实值 45** |
| 根目录约 180 个垃圾文件 | **192 个**，且 **192/192 全部**被 `.gitignore` 覆盖（逐个 `git check-ignore` 验证） |
| 「两份文档都写微信保活 = FGS + Worker 兜底」 | 只有 `AGENTS.md` 有，且在**历史回归记录表**里作为根因出现；`CLAUDE.md` 无此节 |
| `AgentToolHost` 有通道维度陈旧注释 | **不成立**。那里讲的是 `allowAppLocalTools`（执行渠道开关），与通道维度无关 |

### 26.3 真正必修的三项（都违反「不得假装成功」）

**① 插件开关的两种「假装成功」**（最高优先）
- `PluginSettingsScreen.kt:312` **丢弃 `host.load(...)` 的返回值** ⇒ 装载失败时 `setEnabled(id, true)` 仍把 id 移出停用集合，开关显示 **ON**，而插件根本没装载
- `PluginSettingsBoard.kt:228` 的 `pluginId in hostLoadedIds || pluginId !in disabled` ⇒ **从未装载**的插件显示为 ON
- 设置页**零 Snackbar**，而同模块 `CapabilityGrantScreen` / `ImageGenSettingsScreen` / `SettingsScreen` / `CheckUpdateScreen` **全都用 Snackbar 反馈保存结果**——范式现成

**② 蓝图解析静默失败**
`PluginBlueprintParser` **零测试**；`loadDefaultBlueprint` 被 `runCatching` 包着；`BlueprintLoadResult.Failed` 也只打日志。**一个 JSON 语法错误 = 7 个插件全部不装载，界面仍显示「已启用」。**

**③ 微信通道失效在 UI 上完全静默**
`SESSION_EXPIRED_USER_MESSAGE`（errcode=-14，1 小时冷却）与两条 context_token 文案**都写好了**，却停在从未被渲染的 `lastError` 字段里；`WeChatSettingsScreen.kt:606-673` 的健康卡不读它；`SendFailed` 的唯一发射点挂在一个**没有 UI 调用方**的方法上。后果：用户只看到「AI 不回消息」，真因可能是会话过期。

### 26.4 调度者明确判定**不做**的项

| 项 | 理由 |
|---|---|
| `ChannelRegistryImpl` 回滚 | **不是缺陷**（见 26.2） |
| `msg_seq` 重置 | **方向反了**，现状正确（见 26.2） |
| 让 `setEnabled` 抛异常以暴露写失败 | **违反契约**（`PluginEnablementStore` KDoc 明文要求实现方吞掉）。且写失败后开关会**回弹**到持久化旧值——**显示与持久化仍一致**，不是「假装成功」，只是缺明确提示。改进它需先改契约，留作后续 |
| 改 `_server_update/bootstrap.sh` 的 `updateLog`/`publishDate` | **无效修改**：该模板只在**首次**生成 manifest 时写入；若服务器上 manifest.json 已存在则零影响。真正要改的是产出 manifest 的发布流程（`_srv_publish_manifest.py` 是 gitignored 的本地脚本）。**先要确认线上 manifest 的真实来源** |
| QQ 出站重试 / 微信 G3 主动 token 恢复 | **这些不是债，是新功能**，各有自己的设计（重试语义、退避、平台频控；token 恢复受 iLink 协议限制、需扫码） |

### 26.5 构建锁（本批新增的工程机制）

**问题**：5 个任务写作用域完全互不重叠，但**都要跑 gradle**，而并发 gradle 会撞 jar 文件锁。

**解法**：仓库根新增 `build-lock.ps1`（已加入 `.gitignore`）。机制：
1. 抢锁（原子创建 `.build-lock`），抢不到每 10 秒重试；
2. **过期接管**：锁文件超过 25 分钟未更新 ⇒ 判定持锁者已死，强制接管；
3. 等超过 90 分钟 ⇒ **报错退出**（不静默继续）；
4. `finally` 释放；持锁期间每 60 秒 touch 一次锁文件，所以「锁很旧」确实意味着持锁者已死。

**冒烟测试通过**：语法 OK、抢锁/释放干净、无残留。

### 26.6 仓库卫生（调度者亲自做）

**192 个根目录临时文件**（`.log` 67 / `.py` 62 / `.txt` 34 / `.ps1` 22 / `.sh` 3 / 其它 4）已**移动**（非硬删）到 `%TEMP%\yunian-junk-<时间戳>`，可恢复。

**保护未动**：`release.keystore` / `fake.keystore`（签名密钥）、两个历史 APK、`local.properties`、`_server_update/`（跟踪的正式资产）、全部跟踪文件。

**一个虚惊**：勘察提示 `_tmp_secret_sweep.txt` 里「命中了一条凭据形态的模式」。调度者用**结构指纹**（长度 + 字符类，不打印值）核查后判定：

| 行 | 指纹 | 实际是 |
|---|---|---|
| L76-79 | `"sk-` … `o(),` | **引号包着的正则模式** |
| L80 | `lega…RET,` | 常量名 `LEGAL_…_SECRET,` |
| L81 | `hand…mpty` | 名字 `handle…Empty` |
| L83 | `gene…rd()` | **函数调用** `generatePassword()` |
| L84 | `AESG…ret,` | 常量名 `AESGCM…Secret,` |

且文件自身的结构是扫描报告：`#### ... SWEEP (tracked tree @ HEAD) ####` / `--- FULL ... literal ---` / `none` / `--- ... PREFIX (first 5 chars) ---`，尾部是 `Signer #1 ... SHA-256 digest:`（APK 签名摘要）。**它报告的是长度与前缀，不是密码本身。确认无凭据泄露。**

### 26.7 文档纠错（已完成并核对）

`AGENTS.md` 与 `CLAUDE.md` 五处纠正**均已生效**：

| 处 | 改后 |
|---|---|
| Room 版本 | **45**（原 19 / 17），并改成「以 `AppDatabase.kt` 的 `@Database` 块为准（今天 32 个）」——**不再逐条列举**，避免再次过期 |
| `foregroundServiceType` | **`specialUse`**，并写明「`dataSync` 只出现在模块 manifest，**不要把模块的类型抄到 app 侧服务上**——targetSdk 35 会强制 FGS 类型要求」 |
| 路由位置 | **`MainNavGraph.kt`**，「与 `MainRoute.kt` 共同构成事实来源」，代表路由含 `plugin_settings` |
| 厂商列表 | 「由 `ApiProvider` 枚举定义（**事实来源**，14 项）」，明写「**没有 `Codex`**、没有本地模型」 |
| 微信保活 | 「现状：FGS 立即自重启 + Worker **仅作兜底，不可依赖**」 |

另外「Adding a New Module」第 4 步从 `MainActivity.kt` 改成了 `MainRoute.kt` + `MainNavGraph.kt`。

**交付**：`MainRoute.PluginSettings` + `fromRoute` 映射；`MainNavGraph` 挂载与接线；`ProfileScreen` 新增 `onPluginSettingsClick` 形参与条目（插在 API 设置与主题模式之间，图标 `AppIcons.Zap`）；删除两条旧条目与 4 个形参；删除 3 个旧路由 object / 映射 / `composable` / import；`:app` 的 `PluginEnablementStoreImpl`（181 行，`AppMetaStore` KV 单键 `plugin.enablement`）+ 注册 + 启动覆盖。共 12 个文件（10 改 + 2 新）。

**调度者独立验证**：

| 项 | 结果 |
### 26.8 ⚠️ 子代理是**单向**的（重要的操作约束，留档）

**实测**：`send_message` 到 `subagent` 工具派生的子代理 → 报 `active teammate "<id>" not found`。`list_agents` 也只返回 `lead`——**这些子代理对调度者不可见、不可寻址**。

它们**能**给调度者发消息（本会话已收到 10 次），但**调度者无法回信**。

**直接后果**：

| 做法 | 安全性 |
|---|---|
| 任务书写「如果你认为我的前提有错，**直接说出来并给方案**」 | ✅ 安全——邀请的是**回报**（单向可行） |
| 任务书写「如果你还要我做 X，**我可以做，请答复**」 | ❌ **危险**——邀请的是**提问**，而子代理永远收不到答复，会卡住或自行决断 |
| 任务书包含「请先确认再动手」 | ❌ 危险——同上 |

**规则：任务书必须自足。** 需要中途决策的地方，要么在派发前就给定判据（「若 A 则做 X，若 B 则做 Y」），要么把「先说明再动手」改成「**动手并说明**，我会在验收时裁定」（可回退的小改动），要么改用子代理**能读到的工作区文件**作为通信信道。

**本次实例**：D4 在中期回报里问「如果你还要我做 stash 后重跑的真基线，我可以做，但需要额外 ~20 分钟 + 两次 build-lock 排队」——**它收不到我的答复**。调度者已批准它的三项设计决定（`PluginBlueprintStatus` 放 `core/agent`、删 `runTurn` 的 `channelKey`、不做额外基线跑），但只能记录在此，无法送达。

### 26.9 D4 的中期设计决定（调度者批准）

**① 它推翻了调度者的一个前提。** 任务书要求「记进某个**已存在的**可观测位置」。它逐个查完，**没有可用的**：
- `ServiceRegistry`（core:domain）只有 factories/singletons + `initialized: StateFlow<Boolean>`——**没有状态容器**
- `PerformanceTrace`（core:common）全是 `AtomicLong` 时间戳，只有 `markStartupStage(name)`——**表达不了「失败/原因」**
- core:agent 里没有 `pluginHealth` / `lastError` / `Status` 类对象

而 `core:domain` 与 `core:common` **都在它的边界之外**。**调度者的指令不可执行。**

**它的决定（已批准）**：新建 `core/agent/src/main/kotlin/com/yunian/ai/agent/plugin/PluginBlueprintStatus.kt`——纯状态持有者（`object` + `AtomicReference` 持不可变快照，无 UI、无持久化、无 IO、纯 JVM 可测）。
```
Outcome = NotAttempted | Applied(loaded, skipped) | Failed(blueprintId, reason, loaded)
API     = current() / outcome() / hasFailed() / attempts() / recordApplied(...) / recordFailure(...)
```
**放 core:agent 而非 core:domain 的理由**：解析器与宿主都在 core:agent，「蓝图装载」的归属就是它；`:app` 已依赖 core:agent，**不新增任何模块依赖**（一行 `build.gradle.kts` 未改）；放进零依赖契约层反而要为一个「无契约消费者、单一实现者」的状态类型开先例。

**接线（两处，均在边界内）**：`loadDefaultBlueprint` 的三种真实失败（资产读不到 / JSON 非法 / `PluginHost` 未注册）就地 `recordFailure`；`Applied` 分支 `recordApplied`。调用点的 `runCatching` 保留为最后兜底，`onFailure` 里也 `recordFailure`。
⚠️ **有意没有**让宿主 `PluginHostImpl.loadBlueprint` 也记录——那会造出两个记录点、语义重叠。**记录点保持单一。**

**② `runTurn` 的 `channelKey`：删（已批准）。** grep 实测全文只有 3 处引用，**全部是「声明 + 原样转发」，函数体从未读取**；`DialogueRequest.channelKey` 的 3 个生产调用点（`WeChatDialoguePortImpl` / `QQBotChatBridge` / `ChatViewModel`）都在**领域契约层**，不受影响，全部保留。理由：P3 通道插件化时插件自己就知道自己是谁（插件契约里已有 `channelKey`），coordinator 不需要被告知；**留一个恒被忽略的形参只会制造「本中间层是通道感知的」假信号**。连带删了 `generateTextReply` / `generateVisionReply` 的同名形参，并把 `:89-91` 的陈旧半句改成「按 (伴侣 × 工具) 命中，不含通道维度」。

**③ 它纠正了调度者的另一处前提**：「org.json 在纯 JVM 单测里是已知障碍」——**不是障碍**，`core/agent/build.gradle.kts:40-42` 早有 `testImplementation(libs.org.json)`（org.json:json:20231013），纯 JVM 单测可直接用真实实现，无需 Robolectric / `returnDefaultValues` / 改任何构建文件。

**④ 它纠正了自己的一个假设。** org.json 20231013 的方言**比标准 JSON 宽松**：单引号键 `{'id':'x'}` 与尾随逗号 `{"id":"x",}` **都解析成功**。它最初写了两条断言它们抛异常的测试，**按实测改正**，并把这条宽松边界单独钉住。真正抛异常的只有：空串/空白、非 `{` 开头、缺冒号、缺值。**测出来自己的假设是错的，然后改测试而不是改现实。**

**⑤ 它报告了一次失败的高风险操作（已恢复）。** 为拿「改动前基线」，它把两个 tracked 文件 `git checkout` 回 HEAD 再跑 → `:core:agent:compileDebugKotlin` **直接失败**（`AgentDialogueCoordinator.kt:255` 缺 `category` 参数）——**HEAD 版本比工作区版本旧，工作区那批未提交改动才是真正的基线**。已从备份完整恢复 5 个文件。

**教训（已写进本批纪律）**：**这个仓库的「基线」= 工作区当前态，不是 HEAD。** 不得用 `git checkout` 回退来构造基线；本批的基线一律取「改动前工作区跑一次测试」的结果。调度者已在 D4 的验收里要求**逐文件给出恢复证据**（证明磁盘内容 = 编辑后版本而非 HEAD 版本）——回退 tracked 文件是本批风险最高的一次操作，必须独立验证。
|---|---|
| 六模块测试 `--rerun-tasks` | `BUILD SUCCESSFUL in 5m 5s`，`159 actionable tasks: 159 executed`，`EXITCODE=0` |
| 六模块原始 XML | ui-common 15、domain 5、settings 80、wechat 96、qqbot 73、profile 4 → **273 tests / 0 失败** |
| `:app:compileDebugKotlin --rerun-tasks` | 任务**真的执行**（非 UP-TO-DATE），**无 `e:` 错误** |
| `:app:testDebugUnitTest` | **27 tests / 6 失败**（基线 15/6 → +12）；`PluginEnablementStoreImplTest` **12 条全绿**；6 条失败**逐条同名**，与基线完全一致 |
| 旧路由残留 | 代码里 **零命中** |
| 新鲜度 | XML `00:35:10` / `00:42:12` > 最新源文件 `00:12:27` |

**调度者验证的集成接缝**（T2 的页面 ↔ T5 的路由，此前无任何单一子代理端到端负责过）：
```
MainRoute.kt:23   object PluginSettings : MainRoute("plugin_settings")
MainRoute.kt:89   route == "plugin_settings" -> PluginSettings
MainNavGraph.kt:352-353  composable(MainRoute.PluginSettings.route) {
                             PluginSettingsScreen(onNavigateBack = { navController.popBackStack() })
MainNavGraph.kt:463      onPluginSettingsClick = { navController.navigate(...) }
PluginSettingsScreen.kt:133-135  fun PluginSettingsScreen(onNavigateBack: () -> Unit)
    ← 签名与调用点逐字匹配；KDoc:「路由由 :app 挂载，本页不感知导航」
YuNianApplication.kt:643-644  registerSingleton(PluginEnablementStore::class.java) { …Impl(…) }
YuNianApplication.kt:229      applyDisabledPluginOverlay()   ← 紧跟 loadDefaultBlueprint
```
**接缝全部对齐。**

### 25.24 T5 的独立判断（调度者裁定）

**① 它把「另起协程异步套用覆盖」改成「同协程顺序执行」——调度者接受，它的设计更好。**
### 26.10 D1 验收通过（调度者独立核对）

**它纠正了调度者三处前提**，其中一处是任务书**根本没预期到的一层**：

> **缺陷 1 的「补测试」不是加个文件就完事——它当时物理上不可测。** `splitIntoSentences` 是 `QQBotChatBridge` 的 **private 方法**，而该类构造函数需要 Android `Context` 并立即 `AppDatabase.getDatabase(context)` ⇒ **纯 JVM 单测无法构造它**。所以「没有测试覆盖」不只是疏忽，**是结构决定的**。必须先抽成 `internal object` 才可能测。

另两处：**(a)** bug 比调度者描述的更严重——对 N 个连续分隔符会切出 **N−1** 条纯标点消息（「什么？？？」→ `["什么？","？","？"]`，单独「！」→ `["！"]`）；**(c)** 同一个 bug 在 `core/wechat` 也存在。

**它的修法（两条规则，缺一不可）**：
1. **连续分隔符并进前一句**（选「并入」而非「只留一个」，为了不丢语气强度）；
2. **不含实义字符的片段一律丢弃**（覆盖规则 1 管不到的**行首标点游程**：「。。你好」→ `["你好"]`）。

**它还加了一条任务书没要求但代码需要的约束：标点游程不跨 `'\n'` 合并。** 理由：`drainPending` 把合并窗口内的多条入站消息用 `joinToString("\n")` 拼成一段文本，所以**换行在真实输入里非常常见**；允许跨行合并会把「嗯？\n\n？」粘成一条带空行的消息。这条约束同时让改动对既有正常输入**逐字保守**（「你好。\n\n世界。」改动前后都是 `["你好。","世界。"]`）。

**抽成 `internal object QQBotSentenceSplitter` 不是发明新模式**：与既有 `QQBotOutboundProjection` 同构（同包、internal object、纯文本判定、独立测试文件）。

**`sendMutex` 判定为「不需要」并删除**，五条理由，第 5 条最锋利：**加锁反而有害**——它会把互不相关的会话串起来（A 用户因 500ms 节流占住锁，B 用户只能排队）。QQ 侧对被动回复的约束是**次数**约束、不是并发约束：加锁既不能提高上限，也不能让超限回复被接受。**这个分析比调度者的更准确。**

**调度者独立核对的结果**（全部通过）：

| 核对项 | 结果 |
|---|---|
| `:feature:qqbot` 测试 | **91 / 0 / 0 / 0**（调度者自读 XML，单次运行 03:30:04） |
| 基线 73 → 91 | **+18 正好等于新测试类** ⇒ 新测试真的被执行 |
| `QQBotSentenceSplitter` | 确为 `internal object`，同包 |
| `\n` 边界 | 合并循环确有 `text[end] != '\n' && text[end] in DELIMITERS` |
| `private fun splitIntoSentences` | 已删（0 处残留），2 处调用新 object |
| **500ms 节流** | **未动**（`delay(minGapMs - elapsed)` 仍在）⇒ 发送策略一行未改 |
| 测试独立性 | 测试 L36 **自己抄了一份分隔符集合** ⇒ 不是同义反复 |
| `sendMutex` / `Mutex` import | 已删净 |
| `msgSeqCounter` | **未动**，仍是 `private val`（**字面上不可能被重新赋值**），3 处全是 `getAndIncrement()` |
| `QQBotChannelPluginTest` | 仍 **30** 条（未被削弱） |
| 断言 | 仅失败消息改窄，断言与期望值未动 |
| `cleanReplyText` | 全仓仅 1 处 `private fun` 定义 ⇒ 零调用者，死代码确认 |

⚠️ **一次由调度者的 grep 模式造成的误报**：`msgSeqCounter\s*=` 匹配到了 **`private val msgSeqCounter = AtomicInteger(1)` 的初始化**，被误读成「有重置逻辑」。**错的是模式，不是代码。** 教训：`val` 声明天然不可重赋值，模式应写成赋值而非初始化（或直接看 `val`/`var`）。

### 26.11 D1 的变异测试（本批最强的一份证据）

因为它第一次跑 **90 分钟没拿到构建锁**（锁被 5 个进程轮转持有），它**没有绕过锁**，而是另建了一条不依赖 gradle 的验证路径：用仓库缓存里的 **kotlin-compiler-embeddable 2.2.10**（与项目同版本）+ JUnit 4.13.2 **直接编译运行**，只写 `%TEMP%`：
1. 新实现 + 新测试 → `COMPILE EXIT = 0`，`JUnit: OK (18 tests)`，`TEST EXIT = 0`；
2. ⭐ **变异测试**：把**旧算法逐字复刻**成同名 object，配它的新测试 → **18 条里 12 条失败**，例如 `expected:<[hello!!]> but was:<[hello!, !]>`、`expected:<[什么？？？]> but was:<[什么？, ？, ？]>`。

**⇒ 证明新测试不是空转，它们真的抓得住这个 bug。** 这回答了「这些测试有没有意义」这个单测本身无法自证的问题。

**它另外如实标注的边界**：① 它**没能拿到 QQ 官方文档**关于 `msg_seq` / 被动回复次数约束的原文（Bing zh-CN 返回噪声；文档站是 VuePress SPA，HTML 只有壳；JS chunk 抓取被截断在 100000 字符且 `msg_seq` 0 命中）⇒ **因此它刻意在 KDoc 里不写具体数字**，只写「同一 `msg_id` 能被回复的条数有限」；② 它**没跑 `:app` / `assembleDebug`**，所以**不能声称 APK 能出包**；③ 无任何真机/真连接验证。

**它报告但未动**：`QQBotChatBridge.cleanReplyText`（全仓零调用）与 `QQBotApiClient.refreshToken`（全仓零调用，`QQBotWebSocketClient` 用的是自己的 `refreshTokenAndRetryHandshake`）——两个死符号。

调度者原设计是「用合适的协程作用域异步套用」。它改为在 `loadDefaultBlueprint` **之后同一协程顺序执行**，理由：另起协程会引入「覆盖 vs 装载」的**真实竞态窗口**，顺序执行把窗口降到零，且天然满足「在装载之后」。代价是放行屏障多一次 `app_meta` 主键查询（亚毫秒级）。**它选了确定性，这是对的。**

**② 它指出调度者的验收标准 #8「AndroidManifest.xml diff 为空」字面上不可能满足——调度者的前提错了。**

工作区在 T5 开工前就是脏的（T1–T4 全部产物未提交），`git diff --stat` 里的 `AndroidManifest.xml | 1 +` 是**更早的任务**留下的。它改用 **mtime** 作证（禁区文件 mtime 全部早于它的编辑窗口 00:03–00:14 达 24h+）——**这是比 diff 更强的证据**。该标准应改写为「相对任务起点无新增 diff」。

**③ 它主动认领了自己的一次测量错误。**

它用 `cmd /c "... %ERRORLEVEL% ..."` 抓退出码，而 **cmd 在解析期就展开了 `%ERRORLEVEL%`**，抓到的 `0` 是假的。它作废该值，改用 `&& / ||` 分支重跑拿字面退出码，并在报告里逐条标明「哪个是实测、哪个是推断」。**这种自我纠错值得留档。**

**④ 孤儿字符串资源**：删除两条菜单项后，`wechat_settings` / `qqbot_settings` 等 4 个键变成无引用资源。它**故意保留**并请示（调度者只要求清「引用」）。
**调度者裁定：保留。** 理由：删资源是跨 5 个 locale 的资源合并改动、零功能收益；且这些键语义仍然有效（将来插件可用它们标注自己）。用户的要求是移除 **UI 位置**，不是字符串定义。

### 25.25 本批（T1–T5）最终验收汇总

| 模块 | 测试数 | 失败 | 归属 |
|---|---|---|---|
| core:ui-common | 15 | 0 | T1 + T4 |
| core:domain | 5 | 0 | T1 |
| feature:settings | 80 | 0 | T2 + T4 |
| feature:wechat | 96 | 0 | T3 + T4 |
| feature:qqbot | 73 | 0 | T3 + T4 |
| feature:profile | 4 | 0 | T5 |
| **合计** | **273** | **0** | |
| :app | 27 | 6（**全部既存**，逐条同名） | T5 新增 12 条全绿 |

`:app:compileDebugKotlin --rerun-tasks` 无 `e:` 错误。全部验收由调度者用 `--rerun-tasks` 强制重跑取得，**未采信任何子代理的自述**。

### 25.26 ⚠️ 未闭合的验证缺口（必须如实记录）

**本批全部证据止于「编译通过 + 纯 JVM 单测通过」，没有任何运行时/真机证据。** 具体：

| 未验证的行为 | 为什么无法在此环境验证 |
|---|---|
| 「我」页真的显示「插件设置」这一行 | 需真机/模拟器 |
| 点进去真的打开插件设置页 | 需真机 |
| 拨动开关后**重启**真的还是关的 | 需真机 + 重启 |
| FULL_PAGE 浮层里渲染 `WeChatSettingsScreen` 的**视觉**（两层 `GlassPageScaffold` 背景是否重影） | 纯 JVM 单测覆盖不到 Compose 渲染 |
| 微信扫码绑定 / QQ 绑定在新页面内仍可达 | 需真机 |
| 浮层内子页面的系统返回键层级 | 需真机 |

**已知的次要技术债**：
1. **`INLINE` 是死代码**——生产 3 个实现方全部 `FULL_PAGE`，默认值也是 `FULL_PAGE`，无生产路径走它（仅 6 条测试覆盖）。这是 fail-safe 的必然代价；将来出现真正无滚动容器的设置区时才会生效。
2. **孤儿字符串资源** 4 个键（见 §25.24 ④），调度者裁定保留。
3. **运行期再次 `loadBlueprint` 不会重套停用覆盖**（T5 §8.4 指出）。当前仓库只有启动路径一个调用点，安全；将来若加热重载需在该处补一次覆盖。
4. **`tool.grant` 的箭头位置与真实插件不同**（前者行尾、后者行底，因真实插件的行尾槽被 Switch 占着）。同一图标与语义，位置随可用槽位。

**建议的收口动作**：构建 debug APK 装到用户设备上做一次端到端走查——这是唯一能闭合上表的手段。
### 26.12 集中验收（调度者亲自执行）

**第一次复跑抓到一个真实编译错误**——这是子代理自检漏掉、只有独立复跑才能发现的：

```
e: core/agent/src/test/kotlin/.../PluginBlueprintStatusTest.kt:168:57
   Class '<anonymous>' is not abstract and does not implement abstract members
> Task :core:agent:compileDebugUnitTestKotlin FAILED
```

**根因**：`LianYuPlugin` 里只有 `version` 与 `manifest` 有默认实现，`requires: Set<String>` 与 `configSchema: String?` **没有**；匿名实现漏了这两个。同模块既有正确写法见 `tools/ChannelSendToolTest.kt:403-418` 的 rogue 桩。

**处置**：调度者援引 R8「任务不可委派 → **极小单点改动**」亲自补两行 `override`。理由：错误位置、错误文本、仓库既有正确写法三者俱全，再派一轮子代理往返成本高于收益。**这是本批唯一一次调度者亲自改代码，已如实标注。** 同时核对了该文件其余全部蓝图 API 用法（`BlueprintPluginRef(id,enabled,configJson)`、`PluginBlueprint(id,name,plugins)`、`BlueprintLoadResult.Applied(loaded,skipped)`、`internal fun disposeAll()` 同模块可见）——均无误。

**最终测试结果**（`--offline --console=plain`，单次串行运行）：

| 模块 | 测试类 | 用例 | 失败 |
|---|---|---|---|
| `:core:domain` | 1 | 5 | 0 |
| `:core:ui-common` | 2 | 15 | 0 |
| `:core:agent` | 22 | **290** | 0 |
| `:core:wechat` | 11 | **95** | 0 |
| `:feature:qqbot` | 6 | **94** | 0 |
| `:feature:wechat` | 12 | **119** | 0 |
| `:feature:settings` | 5 | **95** | 0 |
| `:feature:profile` | 1 | 4 | 0 |
| `:app` | 9 | 29 | **6（既有）** |

**`:core:domain` 与 `:core:ui-common` 是 `UP-TO-DATE`，不是本轮执行**。已核实其有效性：ui-common 最新源文件 mtime `09-29 23:29`、domain `09-29 22:19`，**都早于**各自 XML 的 `09-30 00:30` ⇒ 输入未变，缓存结果成立。

### 26.13 那 6 个 `:app` 失败与本批无关（已排除，非推测）

| 失败类 | 断言消息要点 |
|---|---|
| `OnePieceShellNativeLoaderTest` | AssertionError |
| `OnePieceShellPayloadPackagingTest` | AssertionError |
| `OnePieceShellPlanTest` ×2 | 其中一条是 **`FileNotFoundException: docs/security/one-piece-shell-hardening-plan.md`** |
| `ReleaseApkBlackboxAuditTest` | `release APK verifier must block BLACKBOX_DEX_PATTERNS` |
| `ReleaseConfigurationTest` | `Original component must not be declared directly in manifests: …CompanionKeepAliveService` |

**三重排除证据**：
1. **符号级**：这 5 个测试类逐个检索本批全部符号（`PluginSettings`/`PluginEnablement`/`PluginBlueprint`/`PluginToggle`/`WeChatChannelHealthAlerts`/`QQBotSentenceSplitter`/`WeChatOutboundSegmenter`/`ToolGrant`）——**全部「无」命中**；
2. **文件级**：`OnePieceShellPlanTest:13` 读 `docs/security/one-piece-shell-hardening-plan.md`，而 **`docs/security/` 目录本身就不存在** ⇒ 纯仓库状态问题；
3. **改动级**：`:app` 用例数 27 → **29**（+2 正是新增的取消传播测试，且**全过**），失败集合**逐类不变**。`app/src/main/AndroidManifest.xml` 本批唯一改动是 `android:screenOrientation="portrait"`，与 `CompanionKeepAliveService` 无关。

⇒ 这 6 个失败属于 **one-piece-shell / release 加固**这条独立战线，是既有状态。

### 26.14 Debug APK（用户请求的第二步）

`gradlew :app:assembleDebug --offline --console=plain` → **`BUILD SUCCESSFUL in 1m 52s`**（475 tasks: 24 executed / 451 up-to-date）。

| 项 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 107.02 MB (112,218,368 bytes) |
| mtime | 2026-10-01 02:44:25 |
| SHA-256 | `3633303329D5B428241B13B93C5ABC4E3956AAABA1056D0A36FF3AE2F8831F27` |
| package | `com.yunian.ai` |
| versionCode / versionName | **26 / 2.0.2** |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| `application-debuggable` | **存在** ⇒ 确为 debug 包 |
| label | 予念（en: YuNian） |
| native-code | `arm64-v8a` |
| 签名 | **`Verifies`**；v2 `true`、v3 `true`；证书 SHA-256 `8d535c73…bb62c` |

**原生库**（11 个，含 `liblianyu_agent.so` **5.23 MB**、`liblianyu_security.so` 0.51 MB、`liblianyu_shell.so` 0.05 MB）。

**本批新类已确认进入 DEX**（28 个 dex 文件内按 JVM 描述符检索）：`PluginToggleController` ✅、`PluginSettingsBoard` ✅、`WeChatChannelHealthAlerts` ✅、`QQBotSentenceSplitter` ✅、`PluginBlueprintStatus` ✅、`PluginEnablementStoreImpl` ✅、`PluginSettingsSections` ✅。

⚠️ **签名事实说明**：`app/build.gradle.kts:72-76` 的 `debug` 块显式 `signingConfig = signingConfigs.getByName("release")`，所以 **debug 包用的是 release keystore**（不是 debug keystore）。密码来自 `~/.gradle/gradle.properties` 的 `YUNIAN_STORE_PASSWORD`/`YUNIAN_KEY_PASSWORD`（已确认存在；值未被读取或输出）。这对安装是必要的——与已安装的同签名应用才能覆盖安装。

### 26.15 本批**未**关闭的验证缺口（必须如实说明）

全部证据止步于「**编译通过 + 纯 JVM 单测通过 + APK 出包且结构正确**」。**没有任何运行时 / 真机证据**。用户正在使用该设备，故**未用 adb 驱动真机**（`10AE1S0TKL002FT`）。因此以下**均未验证**：

1. 「我」页面里「插件设置」入口是否渲染、点击是否打开；
2. 开关切换后**重启是否保持**；
3. `FULL_PAGE` 浮层渲染 `WeChatSettingsScreen` 的视觉效果（**两层 `GlassPageScaffold` 背景可能叠影**）；
4. 微信扫码绑定 / QQ 绑定在新页面内是否仍可达；
5. 浮层内的系统返回键分层行为；
6. 微信通道健康卡片新增的失败提示是否真的出现在屏幕上；
7. QQ 分句修复在**真实 QQ 连接**下的消息条数表现；
8. 蓝图装载失败状态是否在真机上被任何 UI 消费（当前 `:app` 只记录，**没有 UI 消费方**）。

**安装并实际打开这个 debug APK 是关闭上述缺口的唯一途径。**
