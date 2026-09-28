# 微信 / QQ 连接故障修复 — GitHub 开源项目调研与推荐排序

> 调研日期：2026-09-09 ｜ 项目：予念 (YuNian)

---

## 一、项目现状定位（调研起点）

| 侧 | 模块 | 当前技术栈 | 故障 |
|---|---|---|---|
| 微信 | `core/wechat` + `feature/wechat` | `io.github.lith0924:wechat-ilink-sdk:2.3.3`（微信官方 iLink Bot 协议：扫码登录 → 长轮询收消息 → 回复） | 发送消息后无任何反应 |
| QQ | `feature/qqbot` | 自研 `QQBotWebSocketClient`（OkHttp WebSocket 直连 QQ 机器人开放平台 Gateway，IDENTIFY / RESUME / 心跳 / 重连全套自实现） | 一直显示"连接中" |

发送链路：`WeChatOutboxCoordinator`（outbox 表 + 退避重试），发送时从 `IlinkSessionStore` 取 `context_token`。

---

## 二、故障根因候选（与开源生态的映射）

### QQ「一直连接中」

按概率排序：

1. **平台配置类（概率最高，建议先查，成本最低）**
   - **IP 白名单**：QQ 开放平台对新增机器人默认启用 IP 白名单，未配置时 OpenAPI（`getAppAccessToken`、`getGateway` 等 REST）会被拒。Android 设备 IP 动态变化，无法稳定通过 → `getGateway()` 抛异常 → `scheduleReconnect()` 无限重试 → UI 永远停在"连接中"。与 `QQBotWebSocketClient.connectLocked()` 的 catch 分支行为完全吻合。
   - **回调方式被切到 Webhook**：开放平台后台一旦配置 Webhook 回调地址，旧 WebSocket 推送链路会被直接停用。此时 WS 能握手成功（onOpen）但服务端永远不下发 HELLO/事件 → 同样卡"连接中"。
2. **代码类**
   - `isConnecting` 竞态：`onOpen` 之后若既无 `onFailure`/`onClosed` 又无服务端消息（如 NAT 死链），状态永远 CONNECTING。
   - `intents` 声明了机器人无权限的位（当前代码含 6 个 intent 位），IDENTIFY 可能被服务端静默拒绝。
3. **背景**：QQ 官方 2026 年仍在维护 WebSocket 通道（AI Agent / OpenClaw 场景官方推荐 WebSocket，45s 心跳），"WS 已停用"的说法仅针对部分老频道机器人——不是全局死路，但配置门槛变高了。

### 微信「发送后无反应」

1. **iLink 协议硬限制**：Bot 只能**回复**用户先发来的消息（不能主动发起），且 `context_token` 24 小时过期。用无效/缺失 token 调 `sendmessage` 时，服务端可能返回 `ret=0` 但**静默不投递**——症状完全一致。`WeChatOutboxCoordinator.resolveContextToken()` 返回 null 时正是这种情况。
2. **SDK 已知 open Issue：lith0924/wechat-ilink-sdk-java#15**（2026-08-30，未解决）："sendmessage 返回 ret=0 但消息不再投递到任何接收者（全新扫码 bot、干净微信号）"——与本项目故障症状一致，属社区已知问题，疑似微信侧策略调整或协议细节变化。
3. 长轮询 cursor（`get_updates_buf`）中断 → 收不到新消息/新 token → 无法回复。

---

## 三、候选项目对比

### ⭐ 微信 / QQ 官方接口路线（推荐主线）

| # | 项目 | 语言/形态 | 活跃度 | 定位 | 集成前提 | 修复潜力 |
|---|---|---|---|---|---|---|
| 1 | [simple-robot/simbot-component-qq-guild](https://github.com/simple-robot/simbot-component-qq-guild) | Kotlin 多平台库 | ★28，**2026-09-06 仍有 push**，12 open issues，LGPL-3.0 | QQ 官方 API 的 Kotlin 标准实现，WebSocket + WebHook 双通道，**支持 QQ 群聊 / C2C 单聊**（4.0.0-beta6 起，当前 4.5.0） | Kotlin + Ktor Client 2.x（可配 OkHttp 引擎，与现有栈兼容）；需要机器人 AppID/Secret | **QQ 侧最高**。其 Gateway 连接状态机（IDENTIFY/RESUME/心跳/校验和/重连）是标准实现，可直接对照修复自研客户端；也可用 `simbot-component-qq-guild-stdlib` 低封装层整体替换 |
| 2 | [lith0924/wechat-ilink-sdk-java](https://github.com/lith0924/wechat-ilink-sdk-java)（在用） | Java SDK（Maven Central） | ★100 / 23 forks，2026-07-20 push，2026-03 创建，issue 响应活跃（13/15 已回复关闭） | 微信官方 iLink 协议封装：二维码登录、context_token/cursor 自动管理、心跳、AES 媒体加解密 | 已集成（2.3.3）；JVM 依赖，Android 可用 | **微信侧首选**。Issue #15 与故障同症状且作者活跃跟进；历史 issue #1（getUpdates 报错）、#5（并发丢消息）均有修复 PR 落地，说明项目有实际修 bug 能力。行动：在 #15 追加复现信息 + 核查自家 context_token 链路 |
| 3 | [zimoyin/qqbot-sdk](https://github.com/zimoyin/qqbot-sdk) | Kotlin/JVM 库 | 最后提交 2025-05（v1.3.4），334 commits | QQ 官方 API 框架，WebHook + WebSocket，含 WebHook→WS 代理 | Kotlin/JVM，SLF4J 2.x | 中。可作参考实现，但维护节奏放缓，不建议直接替换 |
| 4 | [NebulaMao/wechat-iLink-sdk-typescript](https://github.com/NebulaMao/wechat-iLink-sdk-typescript) | TypeScript（Node） | 活跃更新中 | iLink 协议 TS 实现（不可用于 Android） | — | **协议参考文档价值高**：README 完整描述 cursor、context_token、typing ticket、AES-128-ECB 细节，排障时对照协议层最有效 |
| 5 | pig-mesh `ilink-wechat-sdk`（JiLink）1.0.0 | Java 17+，零第三方依赖 | Maven Central 可查（`io.github.pig-mesh.ai:ilink-wechat-sdk:1.0.0`），**公开 GitHub 仓库检索不到** | iLink 协议另一 Java 封装，自动管理 context_token / cursor / typing ticket | Java 17+ | 中低。版本低、仓库不透明；但适合做**交叉验证**——用它在服务端跑同一账号，可快速区分"SDK bug"还是"微信侧限制" |

### 路线 B：OneBot 协议端生态（个人 QQ 号，非官方 Bot API）

| 项目 | 语言 | 活跃度 | 说明 |
|---|---|---|---|
| [NapNeko/NapCatQQ](https://github.com/NapNeko/NapCatQQ) | TypeScript | ★10.4k，2026-08 更新（公告称正在寻找新主维护者） | 基于 NTQQ 的 OneBot 11 协议端，需常驻 QQ 客户端/服务器运行 |
| LLOneBot | TypeScript | ★3.6k，2026-08 更新 | LiteLoaderQQNT 插件，OneBot 11 / Satori / Milky |
| Lagrange.Core | C# | ★3.0k，2026-08 更新；但 **Lagrange.OneBot v1 已于 2025-10 归档停维护** | NTQQ 协议纯 C# 实现 |

> 路线 B 共同问题：部署形态是**服务器/桌面客户端常驻**，无法嵌入 Android App；模拟个人号协议**违反平台协议、有封号风险**；与予念"官方 Bot API 合规接入"的架构方向冲突。仅当彻底放弃官方机器人平台时才考虑，本报告不主推。

### 明确不推荐

wechaty、WeChatFerry、WeChatPadPro、openwechat、itchat 等 hook/逆向类微信方案——封号风险高、需 PC/服务器宿主、稳定性随微信版本漂移，与本项目场景完全不匹配。

---

## 四、推荐排序与行动建议

### 推荐排序（按"修复两个故障的潜力 × 维护活跃度 × 集成成本"）

1. **simple-robot/simbot-component-qq-guild** — QQ 侧首选：活跃度最高、Kotlin 原生、官方 API 标准实现
2. **lith0924/wechat-ilink-sdk-java（继续用 + 跟进 #15）** — 微信侧无更优替代，社区同症状 issue 是最佳突破口
3. **QQ 开放平台配置自查**（非 GitHub 项目，但优先级实际最高）— IP 白名单 + 回调方式，两处配置就能解释"一直连接中"
4. **zimoyin/qqbot-sdk** — QQ 侧备选参考实现
5. **NebulaMao/wechat-iLink-sdk-typescript** — iLink 协议细节参考文档
6. **pig-mesh JiLink** — 微信交叉验证工具
7. **NapCatQQ 等 OneBot 生态** — 仅当切换为个人号+服务器路线时考虑（有封号风险）

### 行动清单

**立即排查（不换库，半小时内可完成）**
1. QQ 开放平台后台 → 「事件订阅与回调方式」确认仍是 **WebSocket**；若配置过 Webhook，WS 链路已被停用
2. QQ 后台 → 「IP 白名单」：确认是否启用。若启用而手机 IP 不在名单，`getGateway`/token 接口全挂 → 这就是"连接中"死循环的根源；沙箱环境不受白名单限制，可先在沙箱验证
3. 拉取 `QQBotWS` 的 SecureLog：若出现 `获取 QQ Gateway 失败` 即为白名单/凭证问题；若 `WebSocket connected` 后无 `READY`，则是后台回调方式或 intents 越权问题
4. 微信侧：在 outbox 失败记录（`recentFailures`）中查 `lastError`；确认发送时 `context_token` 是否为 null/超过 24h

**短期修复**
5. 微信：到 lith0924/wechat-ilink-sdk-java **Issue #15 追加你们的复现信息**（返回 ret=0 不投递），同时用 JiLink 交叉验证定位责任方；outbox 发送前增加"context_token 缺失/过期 → 拦截并提示用户先发一条消息"的守卫
6. QQ：对照 simbot-component-qq-guild 的 Gateway 实现修复 `QQBotWebSocketClient`：`isConnecting` 超时兜底（如 30s 无 HELLO 判定失败）、intents 收敛到实际申请的权限、IDENTIFY 被拒时把服务端错误抛到 UI 层

**中期方案**
7. 若 QQ 白名单对移动端无法满足：评估迁移到 simbot-component-qq-guild 的 **WebHook 模式**（App 内嵌轻量 HTTPS 服务或经自有服务器中转），白名单填服务器 IP 即可绕开移动端动态 IP 问题
8. 关注 lith0924 SDK 对 #15 的修复版本发布，及时升级

---

## 五、主要参考来源

- QQ 官方：bot.q.qq.com/wiki（事件订阅方式、IP 白名单、AI Agent 接入说明）
- LangBot 官方文档：QQ 官方机器人 API 接入现状（WebSocket/WebHook）
- lith0924/wechat-ilink-sdk-java 仓库 issues（#15、#5、#1）
- cc-weixin 项目的 iLink 协议技术解析（context_token / cursor / X-WECHAT-UIN 机制）
- aky.moe QQ 机器人协议框架状态汇总表（OneBot 生态维护状态）
