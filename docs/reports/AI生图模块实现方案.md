# AI 生图接入模块 — 详细实现方案

> 目标：在「API 设置」页新增独立的 AI 生图模块，兼容 OpenAI `/images/generations` 标准协议；
> 支持①自动拉取生图模型列表 ②聊天自动触发概率 0–100% ③自定义关键词触发。
>
> 约束：存在并行会话共同开发，本方案刻意避开高冲突文件（`MainActivity.kt` / `AppDatabase.kt` /
> `SettingsViewModel.kt`），全部键盘输入点收敛为「新增文件 + 单点插入」。

---

## 0. 现状勘察结论（已核对源码）

| 事项 | 现状 | 对本方案的意义 |
|---|---|---|
| 数据库 | `AppDatabase` version = **43** | 方案**零 schema 变更**，不动 43→44，避免迁移脚本与并行会话冲突 |
| 配置存储范式 | `AppSettingsStore`（DataStore，`app_settings`）+ `VisionModels` 对象 | 生图配置完全同构照搬，纯追加 key |
| 独立模型设置页范式 | `VisionModelSettingsScreen.kt` / `DiaryModelSettingsScreen.kt`，由 `SettingsScreen` **内部 state 切换**，不注册导航路由 | 生图页照此办理，**不改 `MainActivity.kt` / NavGraph** |
| 模型拉取能力 | `AiService.fetchModels(baseUrl, apiKey, provider): Result<List<String>>`，GET `{base}/models` | 可直接复用同一套 OkHttp 组装方式 |
| 网络层 | `core:network`，OkHttp + `RequestSecurityInterceptor.enforceTls` + `RetryInterceptor` | 新建独立 client，不触碰 `AiService` 超时/互斥 |
| 聊天产出落点 | `ChatGenerationManager.startAiResponse()` → `responseFinalizer.deliverResponse()` | 生图挂在 `deliverResponse` 之后，独立协程 |
| 消息写入 API | `messageWriter.enqueueChat(ChatMessage): Long`（同款用法见 `AiResponseFinalizer.flushPendingSticker`） | 生图结果以 IMAGE 消息落库 |
| 图片消息约定 | `MessageType.IMAGE` + `content="[图片]"` + `linkString=绝对路径`；渲染见 `ImageMessageItem.kt`（读 `linkString`） | 严格遵守，`content` **绝不能**写自定义 `[xxx]`（会被当表情包标签查找） |
| 每会话配置范式 | `CompanionChatDetailSettings`（JSON 序列化，`ignoreUnknownKeys = true`） | 追加字段天然向后兼容，无需迁移；可做单聊覆盖 |
| 概率 UI 先例 | `stickerProbability: Int = 30`（每会话，Slider 交互） | 概率交互直接对齐 |

---

## 1. 冲突预审（对照 `AGENTS.md` 新功能开发规范）

| 审查维度 | 本方案影响 | 结论 |
|---|---|---|
| 保活链路 | 不新增 Service / Worker / FGS，纯 `viewModelScope`/`scope` 协程 | ✅ 无影响 |
| 心跳机制 | 不涉及会话与 ack | ✅ 无影响 |
| 消息管线 | 仅在 `deliverResponse()` **之后**追加一次异步生图；不改变 发送→typing→AI 生成 时序；**绝不阻塞** typing 前链路 | ✅ 需强制 `try/catch` 隔离异常 |
| 网络层 | 新建独立 `OkHttpClient`；不改 `AiService` 的超时、连接互斥、`RequestSecurityInterceptor` 注册 | ✅ 无影响 |
| 安全模块 | 不触碰 `ContentFilter` / pipeline / Bayesian | ✅ 无影响 |
| 数据库 | **无** Entity / Migration / DAO 签名变更 | ✅ 无影响 |

---

## 2. 分层设计

```
                    ┌─ 配置层 ────────────────────────────────┐
                    │ AppSettingsStore（全局，DataStore）      │
                    │  imageGenEnabled / Provider / BaseUrl / │
                    │  ApiKey / Model / ModelList / Size / N  │
                    │  TriggerProbability / Keywords / Cooldown│
                    └──────────────────┬──────────────────────┘
                                       │
   ┌─ 服务层（core:network）───────────▼──────────────────────┐
   │ ImageGenerationService                                   │
   │  · fetchImageModels()  GET  {base}/models                │
   │  · generateImage()     POST {base}/images/generations    │
   │  · testConnection()    GET  {base}/models                │
   │  · b64/url → 落盘 getExternalFilesDir("generated_images") │
   └──────────────────┬───────────────────────────────────────┘
                      │ 经 ServiceRegistry 暴露
                      │ 接口 ImageGenerationProvider（core:domain）
   ┌─ 触发层（feature:chat）──────────▼──────────────────────┐
   │ ImageGenTrigger（纯逻辑，可单测）                          │
   │  关键词命中 → 直接触发 ／ 否则 random(100) < P → 触发      │
   │  → 冷却闸门 → 异步生图 → enqueueChat(IMAGE)              │
   └──────────────────┬───────────────────────────────────────┘
                      │
   ┌─ 展示层 ─────────▼───────────────────────────────────────┐
   │ SettingsScreen 内嵌 ImageGenSettingsScreen（新页面）      │
   │ ImageMessageItem 复用现有 IMAGE 渲染（无需改动）           │
   └──────────────────────────────────────────────────────────┘
```

---

## 3. 配置结构设计

### 3.1 全局配置（`core/common/AppSettingsStore.kt`，纯追加）

| 字段 | key | 类型 | 默认值 | 说明 |
|---|---|---|---|---|
| 总开关 | `image_gen_enabled` | Boolean | `false` | 关闭时触发层直接短路 |
| 连接模式 | `image_gen_provider` | String | `"auto"` | `auto`=跟随主 API / `CUSTOM`=独立配置 |
| API 地址 | `image_gen_base_url` | String | `""` | 独立配置时使用，需含 `/v1` |
| API 密钥 | `image_gen_api_key` | String | `""` | 独立配置时使用 |
| 模型名 | `image_gen_model` | String | `""` | 如 `dall-e-3` / `flux-schnell` / `wanx-v1` |
| 模型列表缓存 | `image_gen_model_list` | String | `""` | `\n` 分隔，避免每次进页都请求 |
| 出图尺寸 | `image_gen_size` | String | `"1024x1024"` | 预设枚举 |
| 出图张数 | `image_gen_count` | Int | `1` | 1–4 |
| 触发概率 | `image_gen_trigger_probability` | Int | `0` | 0–100，默认关闭 |
| 关键词 | `image_gen_keywords` | String | 见下 | `\n` 分隔 |
| 冷却时间 | `image_gen_cooldown_minutes` | Int | `3` | 同一会话两次生图最小间隔 |
| prompt 模板 | `image_gen_prompt_template` | String | `"{content}"` | `{content}` 为占位符 |
| 结果提示 | `image_gen_toast_enabled` | Boolean | `true` | 生图失败是否提示 |

默认关键词：`画一张` / `画个` / `画一下` / `给我画` / `生成图片` / `来张图` / `生图` / `画出来`

### 3.2 单聊覆盖（✅ 已确认纳入一期，`CompanionChatDetailSettings`）

`CompanionChatDetailSettings` 为 JSON 序列化 + `ignoreUnknownKeys = true`，追加以下 3 字段**天然向后兼容**（旧 JSON 缺字段 → 取默认值），**无需数据库迁移**：

```kotlin
val imageGenOverrideEnabled: Boolean = false,   // 是否覆盖全局
val imageGenTriggerProbability: Int = 0,
val imageGenKeywords: String = ""
```

生效规则：

```
if (单聊.imageGenOverrideEnabled) 用 { 单聊.imageGenTriggerProbability, 单聊.imageGenKeywords }
else                            用 { 全局.imageGenTriggerProbability, 全局.imageGenKeywords }
```

- 解析逻辑收敛在新文件 `ImageGenTrigger.kt` 的 `resolveEffectiveConfig(global, companion)` 纯函数中，便于单测。
- UI 入口：`ChatDetailScreen.kt` 追加「AI 生图」设置项（与该页面既有的「表情概率 stickerProbability」同区块、同交互），详情页 ViewModel `ChatDetailSettingsViewModel.kt` 追加 3 个 setter。

---

## 4. 接口调用逻辑（`core/network/ImageGenerationService.kt`）

### 4.1 拉取模型列表

```
GET  {baseUrl}/models
Header: Authorization: Bearer {apiKey}
        Accept: application/json
→ data[].id  按关键字白名单过滤：
  flux | dall-e | sd- | stable-diffusion | sdxl | wanx | cogview |
  qwen-image | imagen | ideogram | seedream | kolors | hidream | midjourney
```
- 复用 `AiService.fetchModels` 的成功/失败判定策略（HTML 响应、`error.message` 提取、25s 超时）。
- 过滤结果为空时**不报错**，提示「该接口未返回可识别的生图模型，请手动填写」。

### 4.2 生图

```
POST {baseUrl}/images/generations
Header: Authorization: Bearer {apiKey}
        Content-Type: application/json
Body:  {
         "model": "dall-e-3",
         "prompt": "<已拼装的 prompt>",
         "n": 1,
         "size": "1024x1024",
         "response_format": "b64_json"
       }
```
响应处理：
1. `data[0].b64_json` → Base64 解码直接落盘；
2. 否则 `data[0].url` → 二次 GET 下载（同样带 Bearer，兼容需要鉴权的图床）；
3. 落盘目录：`context.getExternalFilesDir("generated_images")`（**持久目录，严禁用 cacheDir**），
   文件名 `gen_{timestamp}_{index}.png`；
4. 返回 `Result<GeneratedImage(filePath, revisedPrompt, latencyMs)>`。

超时：生图远慢于聊天，单次请求 **120s**（`okHttpClient.newBuilder().readTimeout(120, SECONDS)`），不经聊天链路预算（`TimeoutBudgets`）。

### 4.3 厂商兼容策略

| 厂商 | 兼容性 | 处理 |
|---|---|---|
| OpenAI / Azure | 原生 | 直接可用 |
| 硅基流动 / OpenRouter / 各类中转 | 完全兼容 `/images/generations` | 直接可用 |
| 通义万相（DashScope 兼容模式） | 兼容 | 直接可用 |
| 智谱 CogView | 兼容 | 直接可用 |
| 非标准协议（如仅支持 `/v1/images/generations` 之外路径） | 不兼容 | 走「自定义」填 BaseUrl，必要时填完整路径；后续可扩展 `pathSuffix` 字段 |

### 4.4 对外接口（`core/domain/ImageGenerationProvider.kt`）

```kotlin
interface ImageGenerationProvider {
    suspend fun fetchImageModels(baseUrl: String, apiKey: String): Result<List<String>>
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String>
    suspend fun generateImage(
        baseUrl: String, apiKey: String,
        model: String, prompt: String, size: String, count: Int
    ): Result<GeneratedImage>
}
data class GeneratedImage(val filePath: String, val revisedPrompt: String? = null, val latencyMs: Long = 0L)
```
在 `YuNianApplication.kt` 的 `ServiceRegistry.registerSingleton` 区块追加 3 行注册（与 `AiServiceProvider` 同款）：
`feature:settings` 与 `feature:chat` 均已依赖 `core:network`，可直接取用。

---

## 5. 概率与关键词触发机制

### 5.1 判定流程（`feature:chat/.../ImageGenTrigger.kt`，纯函数便于单测）

```
AI 回复落地
   │
   ├─ ① 总开关关闭 / baseUrl·apiKey·model 任一为空 ──▶ return
   │
   ├─ ② 关键词命中？（用户本条消息 ∪ AI 本轮回复 任一命中）
   │       命中 ──▶ 跳过概率，直接进入 ④
   │
   ├─ ③ 概率抽签 SecureRandom.nextInt(100) < P
   │       未命中 ──▶ return
   │
   ├─ ④ 冷却闸门：now - lastGenAt < cooldownMs ──▶ return
   │
   └─ ⑤ 异步生图 → 成功后 messageWriter.enqueueChat(IMAGE) → 更新 lastGenAt
```

要点：
- **关键词优先于概率**：命中关键词即 100% 触发，不受概率限制（否则关键词形同虚设）。
- **概率 0 = 只走关键词**；概率 100 = 每轮必触发（仍受冷却约束，防刷屏）。
- 冷却状态：内存 `ConcurrentHashMap<Long, Long>` + DataStore `image_gen_last_at_{companionId}` 持久化（重启后仍生效）。
- 生图在**独立协程**中执行，`try/catch` 全包裹，失败仅记日志（可选 Toast），**绝不向上抛**，保证聊天轮次不受影响。

### 5.2 prompt 来源（✅ 已确认：AI 标签优先 + 两级兜底）

1. **AI 显式标签（最高优先）**：新增 `ImageGenPromptRules.systemRules()`，向模型注入一句
   「如需配图，输出 `[[生图: 画面描述]]`」；正则 `\[\[生图[:：]\s*([^\]]+)\]\]` 提取描述作为 prompt，
   并在最终聊天文本中**剥离该标签**（避免标签泄漏到气泡里），剥离由 `TextProcessor` 或本模块自身的
   `stripImageTags(content)` 完成。
2. **关键词命中时**：取用户消息去掉触发词后的剩余文本。
3. **兜底**：取 AI 本轮回复正文（已剥离标签），截断 800 字符。

最后套 `image_gen_prompt_template`（默认 `{content}`，可改为 `画一张：{content}` 等）。

**注入点（最终实现：零 core 改动）**

勘察发现 `AiServiceProvider.streamMessage`（主聊天路径）**没有** `extraSystemRules` 参数，只有
`sendMessage` / `sendMessageWithTools` 有；而 `AiPromptBuilder.buildCompanionSystemSection()`
会把 `AiCompanionInfo.systemPrompt` 作为「【自定义角色指令】」写入系统提示词。

因此改为：在 `ChatGenerationManager.startAiResponse` 里构造一份**带生图规则的 `aiCompanion`**，
在 4 处调用点（`sendMessageWithImage` / `executeWithToolLoop` / `streamMessage` / 追尾气泡 `sendMessage`）
统一使用它 —— 从而无需改动 `core:domain` 接口与 `core:network` 的提示词装配。

```kotlin
val aiCompanion = companion.toAiCompanionInfo().let { base ->
    if (imageGenRules.isBlank()) base
    else base.copy(
        systemPrompt = listOfNotNull(
            base.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
            imageGenRules,
        ).joinToString("\n\n")
    )
}
```
规则文案仅在「生图总开关开启」时注入，关闭时 `imageGenRules` 为空串，`aiCompanion ===` 原对象，**零行为变化**。

标签剥离：`aiContent` 在进入 `deliverResponse` 前先经 `ImageGenTriggerLogic.stripTags()`；
若剥离后为空（模型只输出了标签）则保留原文，避免被误判成「API 返回空内容」。

### 5.3 落库

```kotlin
messageWriter.enqueueChat(
    ChatMessage(
        companionId = companionId,
        content = "[图片]",              // 系统保留标签，不可改
        isFromUser = false,
        type = MessageType.IMAGE,
        linkString = file.absolutePath,   // 持久目录绝对路径
        searchContent = prompt            // 复用现有列存 prompt，便于回溯/搜索
    )
)
```
渲染侧零改动：`ChatListItem` → `ChatListItem.ImageMessage` → `ImageMessageItem`（读 `linkString`）。
`searchContent` 存 prompt 的额外收益：生图记录可被消息搜索命中。

### 5.4 过程反馈（✅ 已确认：全程提示）

复用既有事件通道 `ChatUiEvent`（`ChatUiEvent.Info(message)` → 正常提示，`ChatUiEvent.Error(message)` → 错误提示），无需新增 UI 组件：

| 时机 | 事件 | 文案 |
|---|---|---|
| 触发时 | `Info` | 「正在为你生成配图…」 |
| 成功 | `Info` | 「配图已生成」 |
| 失败 | `Error` | 「配图生成失败：{原因}」 |

- 发送 `Info` 时即刻触发，不影响聊天流；生成耗时长（10–60s）期间聊天可继续。
- `image_gen_toast_enabled` 开关可关闭全部提示。

### 5.5 可选：生成中占位气泡（建议二期）

`MessageCache` 支持负 id 流式消息（`appendChatMessage` + `updateChatMessage`）。可先插一条 `id = -System.nanoTime()` 的「生成中」气泡，完成后用真实 id 替换/移除。
**建议一期不做**：负 id 与 `ChatViewModel.observeCachedMessages` 的 metadata 合并逻辑（历史踩坑点）耦合较深，收益有限。

---

## 6. 前端交互实现

### 6.1 入口（`SettingsScreen.kt` 单点插入）

在既有「视觉模型设置」卡片（约 line 262–330 区块）**之后**，复制同款 `AnimatedVisibility + drawGlass Row` 卡片：
- 图标：`AppIcons.Sparkles`（或 `AppIcons.Image`）
- 标题：「AI 生图」；副标题动态显示：`已开启 · 概率 30% · 关键词 8 个` / `未配置，点击接入`
- 点击：`showImageGenSettings = true`
- 文件末尾追加：
```kotlin
if (showImageGenSettings) {
    ImageGenSettingsScreen(onNavigateBack = { showImageGenSettings = false })
}
```
新增 state 声明 1 行。**不引入 navigation-compose 路由，不动 `MainActivity.kt`。**

### 6.2 页面结构（新文件 `ImageGenSettingsScreen.kt`，约 700 行，风格对齐 `VisionModelSettingsScreen`）

```
┌ 顶栏：← AI 生图 ──────────────────────────────┐
│ 配置AI图像生成模型，支持聊天自动配图           │
├ 卡片 1 · 功能开关 ────────────────────────────┤
│  [图标] AI 生图            [Switch 总开关]     │
├ 卡片 2 · 连接配置（开关开启后展开）────────────┤
│  [Switch] 跟随主API / 独立配置                 │
│  ── 独立配置时 ──                              │
│  模型名 [输入框]  [⟳ 拉取模型列表]             │
│    ↳ LazyColumn 可点选模型（拉取结果）         │
│    ↳ 加载中 CircularProgressIndicator / 空态提示│
│  API 地址 [输入框]   API 密钥 [输入框 👁]      │
│  尺寸  [512²][768²][1024²][1024x1792][1792x1024]│
│  张数  [1][2][3][4]                            │
│  [ ✓ 测试连接 ]                                │
│  ↳ 结果内联展示（成功绿 / 失败红 + 排查清单）  │
├ 卡片 3 · 触发规则 ────────────────────────────┤
│  自动触发概率   12%                            │
│  ├─────────●────────────┤  Slider 0–100       │
│  [关闭0][低10][中30][高60][总是100] 预设 chips │
│  自定义关键词                                  │
│  (画一张)(画个)(生图)… FlowRow 玻璃 chip       │
│  [输入框 添加关键词]  [+ 添加]  长按 chip 删除 │
│  冷却时间  3 分钟  [输入框/步进器]             │
│  ▸ 高级：prompt 模板（折叠）                   │
├ 卡片 4 · 说明 ────────────────────────────────┤
│  概率与关键词是「或」关系，关键词命中必触发     │
└───────────────────────────────────────────────┘
```

### 6.3 ViewModel（新文件 `ImageGenSettingsViewModel.kt`）

- `AndroidViewModel(application)`，与 `SettingsViewModel` **完全独立**，只依赖 `AppSettingsStore` + `ServiceRegistry.getOrThrow(ImageGenerationProvider::class.java)`。
- 暴露：`imageGenEnabled/Provider/BaseUrl/ApiKey/Model/ModelList/Size/Count/Probability/Keywords/CooldownMinutes/PromptTemplate` 的 `StateFlow` + setter（照抄 vision 同款 `collect` 范式）。
- 状态机：`isFetchingModels` / `fetchError` / `isTesting` / `testResult`。
- ⚠️ **不复用 `SettingsViewModel`**：其 982 行且是并行会话高冲突热点；独立 VM 保证零冲突、零回归。

### 6.4 关键交互细节

| 场景 | 行为 |
|---|---|
| 拉取模型列表 | 点击 → loading；成功写入 DataStore 缓存（下次进页直接展示）；失败展示 `error.message` |
| 模型选择 | 拉取结果以可点选列表展示，点选即填入模型名输入框 |
| 概率调节 | Slider 拖动实时写 DataStore（可不做防抖，DataStore 写入廉价）；预设 chip 一键设定 |
| 关键词添加 | 去重、trim、忽略空串；以 `\n` 拼接入库；chip 长按弹删除确认 |
| 关键校验 | 总开关开启 + 概率 > 0 或 关键词非空 且 baseUrl/apiKey/model 齐全，才允许触发；UI 上不满足时显示橙色警示条 |
| 危险提示 | 概率 ≥ 60% 时提示「高概率将频繁消耗生图额度」 |

---

## 7. 文件清单

### 新增（6 个源文件 + 1 个测试）

| # | 路径 | 内容 | 实际行数 |
|---|---|---|---|
| 1 | `core/domain/.../ImageGenerationProvider.kt` | 接口 + `ImageModelCatalog` / `GeneratedImage` DTO | ~55 |
| 2 | `core/network/.../ImageGenerationService.kt` | 拉模型 / 生图 / 测连接 / b64·url 落盘 / 参数回退 | ~380 |
| 3 | `feature/settings/.../ui/viewmodel/ImageGenSettingsViewModel.kt` | 设置页状态与持久化 | ~310 |
| 4 | `feature/settings/.../ui/screen/ImageGenSettingsScreen.kt` | 设置页 UI | ~830 |
| 5 | `feature/chat/.../ui/viewmodel/ImageGenTrigger.kt` | 系统规则文案 + 标签解析/剥离 + 配置解析 + 触发判定 + 异步生图编排 | ~330 |
| 6 | `feature/chat/src/test/.../ImageGenTriggerTest.kt` | 28 条单测 | ~390 |

### 修改（7 个文件，全部为「追加式」改动）

| # | 路径 | 改动 | 冲突风险 |
|---|---|---|---|
| 7 | `core/common/.../AppSettingsStore.kt` | 追加 13 组 key + flow/setter + `ImageGenDefaults` 常量对象 | 低（纯追加） |
| 8 | `app/.../YuNianApplication.kt` | `import ImageGenerationProvider` + `registerSingleton` 4 行 | 低 |
| 9 | `feature/settings/.../ui/screen/SettingsScreen.kt` | +1 state、+1 入口卡片、+5 行页面挂载 | 中（集中在一个区块） |
| 10 | `feature/chat/.../ui/viewmodel/ChatGenerationManager.kt` | 生图规则并入 `aiCompanion`；4 处调用点改用它；标签剥离；+1 次触发调用；新增 `maybeTriggerImageGeneration` 私有方法 | **高**（热点文件）→ 逻辑全外置，本文件净增约 70 行 |
| 11 | `feature/chat/.../data/ChatDetailSettingsStore.kt` | 追加 3 字段（JSON 向后兼容） | 低 |
| 12 | `feature/chat/.../ui/viewmodel/ChatDetailSettingsViewModel.kt` | 追加 3 个全局配置 StateFlow + 3 个 setter | 低 |
| 13 | `feature/chat/.../ui/screen/ChatDetailScreen.kt` | 追加「AI 生图」设置区块（覆盖开关 + 概率 Slider + 关键词编辑） | 中 |

**明确未改动**：`MainActivity.kt`、`AppDatabase.kt`（仍为 v43，零 schema 变更）、`SettingsViewModel.kt`、
`AiService.kt`、`AiPromptBuilder.kt`、`AiServiceProvider.kt`（接口未加参数）。

### 与原方案的两处落地偏差（均为降低风险）

| 项 | 原计划 | 实际实现 | 原因 |
|---|---|---|---|
| 生图协议注入 | 给 3 处调用传 `extraSystemRules` | 把规则并入 `AiCompanionInfo.systemPrompt` 后构造 `aiCompanion` | 主路径 `AiServiceProvider.streamMessage` 无 `extraSystemRules` 参数；而 `AiPromptBuilder` 会把 `systemPrompt` 写进「【自定义角色指令】」。**改动量更小且零 core 改动** |
| 过程提示通道 | 「复用 ChatUiEvent」 | 实测 `Info` → `YuNianToast.info`，`Error` → 错误提示 | 与计划一致，未新增 UI 组件 |

---

## 8. 验证方案

1. **单元测试**：`ImageGenTriggerTest` 覆盖
   - 概率 0 → 永不触发；100 → 必触发（冷却通过时）
   - 关键词命中 → 无视概率 0 仍触发
   - 冷却期内 → 不触发；冷却期外 → 触发
   - 总开关关闭 / 配置缺失 → 不触发
   - prompt 提取：标签优先 > 用户消息 > AI 回复兜底；模板占位符替换
2. **构建**：`./gradlew :core:domain:compileDebugKotlin :core:network:compileDebugKotlin :feature:chat:compileDebugKotlin :feature:settings:compileDebugKotlin` → 再 `assembleDebug`
3. **冒烟（真机）**：
   - 设置页入口可进、开关/概率/关键词保存后重进仍在
   - 拉取模型列表成功（用一个兼容中转站 key 验证）
   - 概率设 100 + 关键词空 → 发一条消息，回复后出现图片气泡，点击可放大
   - 概率设 0 + 关键词「画一张」→ 发「画一张猫」，必出图
   - 关闭总开关 → 不再出图
   - 断网/错误 key → 聊天正常继续，无崩溃
4. **回归重点**：连发多条消息确认 typing/流式/气泡链未被影响；微信 Bridge 镜像路径不受影响。

---

## 9. 风险与回滚

| 风险 | 处置 |
|---|---|
| 生图慢（10–60s）造成用户困惑 | 触发即提示「正在为你生成配图…」，生成期间聊天不受阻塞；成功/失败均有明确提示 |
| 生图失败影响聊天 | 独立协程 + 全 try/catch，失败只记日志 |
| 部分厂商不支持 `b64_json` | 自动回退 `url` 下载；两者都无 → 明确报错文案 |
| 并行会话改到 `ChatGenerationManager.kt` | 该文件仅新增 1 处调用（12 行），合并冲突可手工秒解；触发逻辑全在新文件 |
| 图片占空间 | `generated_images` 目录按 30 天/200 张上限清理（可作二期） |
| 回滚 | 关闭总开关即完全停用；代码层面删除 6 个新文件 + 撤回 4 处追加即可，无残留 schema 影响 |

---

## 10. 已确认决策（用户拍板）

| 决策项 | 结论 |
|---|---|
| 概率/关键词作用域 | **全局 + 单聊覆盖**（单聊可在聊天详情页覆盖，默认关闭覆盖） |
| prompt 来源 | **AI 标签优先 + 两级兜底**（注入 `[[生图: 描述]]` 规则，标签剥离后不入气泡） |
| 过程反馈 | **全程提示**：触发「正在为你生成配图…」/ 成功「配图已生成」/ 失败「配图生成失败：原因」 |
| 额度保护 | **仅冷却时间**（默认同会话 3 分钟 1 次，不加每日上限） |

## 11. 交付记录（S1–S6 已全部完成）

| 步骤 | 内容 | 验证结果 |
|---|---|---|
| S1 | `ImageGenerationProvider` 接口 + `ImageGenerationService` + ServiceRegistry 注册 | ✅ `:core:domain:compileDebugKotlin` / `:core:network:compileDebugKotlin` 通过 |
| S2 | `AppSettingsStore` 追加 13 组 imageGen 配置项 | ✅ `:core:common` 随依赖链编译通过 |
| S3 | `ImageGenSettingsScreen` + `ImageGenSettingsViewModel` + `SettingsScreen` 入口 | ✅ `:feature:settings:compileDebugKotlin` 通过 |
| S4 | `ImageGenTrigger` 纯逻辑 + 编排 + 单测 | ✅ 28 条单测全绿（`tests="28" failures="0" errors="0"`） |
| S5 | `ChatGenerationManager` 挂点（规则注入 + 标签剥离 + 触发 + 提示） | ✅ `:feature:chat:compileDebugKotlin` + 单测通过 |
| S6 | 单聊覆盖（`ChatDetailSettingsStore` + VM + `ChatDetailScreen`） | ✅ 同 S5 编译通过 |
| 终验 | `./gradlew assembleDebug` 全量构建 | ✅ BUILD SUCCESSFUL（app-debug.apk，121 MB，16:10） |

### 仍需真机实测的项（代码层已就绪）

1. 拉取模型列表（需一个兼容 `/v1/models` 的真实 key）
2. 概率 100 + 关键词空 → 发送消息后应出现 `[图片]` 气泡且可点开放大
3. 概率 0 + 关键词「画一张」→ 发送「画一张猫」必出图
4. 关闭总开关 → 不再出图，且**系统提示词不含生图规则**（零行为变化）
5. 冷却期内二次触发被拦（提示不出现）
6. 错误 key / 断网 → 聊天正常继续，仅有失败提示

### 已知限制

- 生图标签依赖模型遵守协议；部分小模型可能不输出标签，此时自动回退用用户消息/回复正文作 prompt，效果略逊但不影响功能。
- `generated_images` 目录暂无自动清理（二期可加 30 天/200 张上限）。
- 单聊覆盖只覆盖「概率 + 关键词」，尺寸/张数/模型仍沿用全局。

---

## 12. 回滚方式

关闭总开关即完全停用。代码层面删除 6 个新文件 + 撤回 7 处追加改动即可，无数据库残留影响（全程零 schema 变更）。
