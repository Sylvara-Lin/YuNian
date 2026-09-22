# AI Agent 工具完善：自动化任务 + 瑞幸确认卡片 设计

日期：2026-08-07
状态：已与用户逐节确认

## 1. 背景与目标

现有 AI 工具调用框架已就绪（`AiTool` 接口 + `ToolRegistry` + `AiToolLoopRunner` 循环），瑞幸咖啡 6 工具与记忆召回工具已注册。本次完善两件事：

1. **新增「自动化」能力**：AI 在对话中通过工具创建/取消定时自动化（提醒），到点时以系统通知 + 伴侣聊天自然消息双重送达。
2. **瑞幸下单确认**：`luckin_create_order` 目前直接执行（真实支付），需改为「AI 提议 → 弹确认卡片 → 用户确认 → 才下单」。

设计约束（用户明确要求）：
- **不动 Room 数据库**（AppDatabase v19 保持不变，无 Entity/DAO/Migration 变更）。
- 功能命名为「自动化」（不叫提醒）。
- 对话中创建自动化要走**卡片弹窗确认**，与瑞幸下单确认共用同一机制。

## 2. 需求定稿

| 项 | 结论 |
|---|---|
| 自动化类型 | 一次性 / 每天 / 每周（指定星期） |
| 送达 | 系统通知 + 聊天内伴侣自然消息（进 AI 上下文） |
| 管理 | 设置页列表页 + AI 双入口 |
| 创建确认 | 对话中 AI 弹确认卡片，用户确认后才创建 |
| 瑞幸下单 | 仅 createOrder 需确认（只读工具不拦截） |
| 工具启用 | 扩充关键词门控（保持省钱、减少误调用） |
| 存储 | DataStore（零 DB 变更） |
| 模块 | 新增 `feature:automation` |

## 3. 架构方案（A′ 修订）

- 新增 Gradle 模块 `feature:automation`（依赖 `core:domain`、`core:database`、`core:common`、`core:ui-common`；**不依赖任何其他 feature 模块**，通知自建渠道）。
- 存储用 DataStore JSON 列表，不引入 Room。
- 调度用 WorkManager `OneTimeWorkRequest`（与 `CompanionMessageWorker` 同款机制，WorkManager 不依赖 Room）。
- 到点写聊天消息复用现有 `MessageWriteCoordinator`（消息表无 schema 变化）。
- **通知渠道**：`feature:automation` 自建自动化通知渠道（`NotificationHelper` 在 feature:notification，跨 feature 依赖被架构禁止，故自包含）。

### 模块注册（AGENTS.md 新模块流程）

1. `settings.gradle.kts`：`include(":feature:automation")`
2. `app/build.gradle.kts`：添加依赖
3. `MainRoute.kt` / `MainNavGraph.kt`：添加 `automation` 路由
4. `YuNianApplication.registerServiceProviders`：注册 `AutomationStore` 单例 + `AutomationTools.registerAll(...)`
5. 设置页添加「自动化」入口项

## 4. 数据模型

```kotlin
enum class AutomationType { ONCE, DAILY, WEEKLY }

@Serializable
data class Automation(
    val id: String,            // UUID，工具与 UI 引用
    val title: String,         // 任务名（用户原话，如"喝水"）
    val companionId: Long,     // 从哪个对话创建 → 到点消息写进该对话
    val type: AutomationType,  // 一次 / 每天 / 每周
    val triggerAtMillis: Long, // ONCE：绝对触发时间
    val hourOfDay: Int,        // DAILY/WEEKLY：触发时刻
    val minuteOfHour: Int,
    val dayOfWeek: Int?,       // WEEKLY：1(周一)..7(周日)
    val message: String,       // 到点时伴侣发的自然文案（AI 生成）
    val enabled: Boolean = true,
    val createdAt: Long
)
```

## 5. 存储：AutomationStore

- 独立 DataStore 文件（仿 `ChatDetailSettingsDataStoreProvider` 模式），单 key `automations_json` 存 JSON 数组。
- 方法：`list()` / `upsert()` / `delete()` / `setEnabled()`，全部 suspend + 并发安全。
- 不引入 Room，AppDatabase v19 不动。

## 6. 调度与触发

### AutomationScheduler

- 每个启用中的自动化注册一个 WorkManager `OneTimeWorkRequest`，唯一名 `automation_<id>`，`initialDelay = 下次触发时刻 - now`。
- 创建/修改/删除/开关时调用 `reschedule(id)` / `cancel(id)` 维护调度。
- App 启动时在 `YuNianApplication.initBusiness` 的 bgScope 内对全部启用中的自动化统一调用 `reschedule(id)` 重建调度（WorkManager 本身会对未执行任务开机自动恢复，此重建用于对账兜底）。

### AutomationFireWorker.doWork

1. 从 Store 读取该自动化；不存在或 `enabled=false` → 直接结束。
2. 发系统通知（自建自动化通知渠道，内容 = title）。
3. 经 `MessageWriteCoordinator` 写一条伴侣聊天消息（内容 = `message`，`isFromUser=false`，写进 `companionId` 对应对话）；写前过 `ContentFilter.checkOutputSafety`（对齐 `CompanionMessageWorker` 兜底，AI 输出违规不累计用户封禁）。
4. DAILY/WEEKLY：计算下次触发时刻并重新调度；ONCE：结束。

### 重启恢复

WorkManager 对未执行的 OneTimeWorkRequest 开机后自动恢复（与 `CompanionMessageWorker` 同款），无需额外处理。

## 7. AI 工具

### AutomationTools（registerAll → ToolRegistry）

| 工具名 | 参数 | 说明 |
|---|---|---|
| `automation_create` | title, type, triggerAt(ms)/hour/minute/dayOfWeek, message | 创建自动化（**需确认卡片**） |
| `automation_cancel` | id 或 title | 取消自动化（低风险直接执行） |
| `automation_list` | — | 列出当前自动化（供 AI 查询） |

`automation_cancel` 支持按 title 取消：先 `list()` 模糊匹配，唯一命中则取消；多/零命中返回 JSON 供 AI 追问澄清。
`automation_create` 的 `message` 参数缺省时由工具侧生成「到点啦～该{title}啦」。

### 瑞幸

`luckin_create_order` 覆写 `requiresConfirmation = true`，其余 5 个工具不变。

## 8. 通用「确认卡片」机制（核心）

### AiTool 接口变更

```kotlin
interface AiTool {
    ...
    /** 涉及支付/创建等副作用，执行前需用户确认 */
    val requiresConfirmation: Boolean get() = false
}
```

带默认值，不破坏现有 7 个工具实现。

### AiToolLoopRunner 改造

- 构造注入 `ConfirmationGate`（fun interface：`suspend fun requestConfirmation(toolName, argumentsJson): Boolean`）。
- 循环中执行工具前检查 `tool.requiresConfirmation && gate != null`：
  - 是 → `gate.requestConfirmation(...)`，返回 false 时结果写「用户已取消」并继续循环；
  - 否 → 原逻辑直接执行。
- 超时（`TimeoutBudgets.AUTOMATION_CONFIRM_TIMEOUT_MS`）按拒绝处理。

### ChatGenerationManager 实现 Gate

- `MutableStateFlow<ToolConfirmationRequest?>`（`confirmationRequest`），`ToolConfirmationRequest(id, toolName, summary, argumentsJson)`。
- Gate：emit 请求 → 挂起等待用户响应（Channel）→ `respondToConfirmation(id, confirmed)` 恢复。
- ChatScreen 收集 `confirmationRequest` 弹 Dialog（标题「AI 请求创建自动化 / 确认下单」+ 摘要 + [确认] [取消]）。
- 卡片弹出期间暂停 typing，确认后恢复。

### 卡片摘要

- `automation_create`：解析参数生成「每天 08:00 · 喝水」人类可读摘要。
- `luckin_create_order`：取参数中门店/商品数/实付（AI 已先 preview，卡片只做最终确认）。

## 9. UI

- `AutomationListScreen`（路由 `automation`）：LazyColumn 列出全部自动化，行内：任务名 + 循环徽标 + 触发时间 + 开关 + 删除；空态文案引导「在对话里让 AI 帮你设置」。
- 设置页新增「自动化」入口项。
- ChatScreen 新增确认卡片 Dialog（通用组件）。

## 10. 工具门控扩充

`ChatToolIntent` 关键词在现有「咖啡/瑞幸/记忆」基础上增加：
`提醒` `定时` `几点` `每天` `每周` `明天` `明早` `闹钟` `待办` `记得` `别忘了` `自动化`
（保持现有咖啡词不动。）

## 11. 冲突预审（AGENTS.md 必做）

| 维度 | 结论 |
|---|---|
| 保活链路 | ✅ 只新增 WorkManager 一次性任务，不碰 FGS/Worker 存活时序；`CompanionMessageWorker` 零改动 |
| 心跳机制 | ✅ 无涉及 |
| 消息管线 | ⚠️ 触发时经 `MessageWriteCoordinator` 写消息（同主动消息路径）；确认卡片使 AI 轮次挂起等待，typing 卡片期间暂停、确认后恢复，可控 |
| 网络层 | ✅ OkHttp/ilink/QQ 网关不动 |
| 安全模块 | ✅ 伴侣消息写入前过 `ContentFilter.checkOutputSafety`；`AiTool` 仅加带默认值属性 |
| 数据库 | ✅ Room v19 不变，无 Entity/DAO/Migration |

## 12. 超时预算

新增至 `core/common/.../TimeoutBudgets.kt`：
- `AUTOMATION_CONFIRM_TIMEOUT_MS`（确认卡片等待上限，超时按拒绝）

## 13. 测试计划

- `:shell` JVM 测试：
  - `AutomationSchedulerTest`：ONCE/DAILY/WEEKLY 下次触发时刻计算（含跨天、跨周、周几边界）。
  - `AutomationToolsTest`：create 参数解析缺省值、cancel 按 title 模糊匹配单/多/零命中。
  - `ConfirmationGateTest`：AiToolLoopRunner 拦截路径（确认执行 / 拒绝 / 超时拒绝 / 非确认工具直通）。
- 手工验证：
  - 对话说「每天早 8 点提醒我喝水」→ 卡片弹确认 → 确认后列表可见 → 到点收通知 + 伴侣消息。
  - 瑞幸对话下单 → 预览后 createOrder 弹卡片 → 取消则不下单。
