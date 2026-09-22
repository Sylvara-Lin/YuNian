<p align="center">
  <picture>
    <img src="logo/logo.png" alt="予念 Logo" width="140" />
  </picture>
</p>

<h1 align="center">💕 予念 · YuNian</h1>

<p align="center">
  <i>你的 AI 伴侣 — 会聊天、会记事儿、会主动找你</i>
</p>

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat-square&logo=kotlin&logoColor=white" /></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Version-1.10.8-FF6B9D?style=flat-square" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Modules-25-7C3AED?style=flat-square" /></a>
  <a href="#"><img src="https://img.shields.io/badge/License-Educational%20Use-6366F1?style=flat-square" /></a>
</p>

---

> **关于更名**：本项目原名「恋语 LianYu」，现已更名为「**予念 YuNian**」（应用名、包名 `com.yunian.ai`、类名与文档均已同步）。
> 为兼容既有服务端与本地数据，部分协议头（`X-LianYu-*`）、native 模块名（`liblianyu_*`）与加密盐仍保留旧名。

## ✨ 功能特性

### 💬 聊天

- **多 AI 伴侣**：创建 / 编辑虚拟恋人（头像、名字、年龄、身材、职业、性格标签、角色设定、系统提示词），支持 **AI 一键生成人设**与**从文件导入角色**，可选 AI 女友 / AI 男友
- **Agent 驱动对话**：文本回合由本地 Rust Agent 决策，支持多轮工具调用与**工具执行前的确认门控**
- **气泡连发**：一轮回复可按角色性格拆成多条气泡连续发出
- **流式输出 + 思考过程**：推理内容可展开 / 折叠，可配置是否显示与自动折叠
- **「对方正在输入…」**状态提示
- **富消息类型**：文本、图片、视频、语音、文件、表情包
- **消息操作**：引用、复制、撤回、重新生成、保存图片
- **消息分页**：向上加载更早的历史
- **草稿保存**：每个角色各自独立
- **连续发消息合并**：短时间内连发多条按窗口合并为一轮，并带防重复守卫
- **会话内切换模型**：不必退出聊天即可换 API
- **内容安全拦截**：命中违规内容时明确提示

### 🎙 语音

- **语音消息**：按住录音、上滑取消
- **语音通话 / 视频通话**
- **TTS 朗读**：9 家服务商，含**端上离线合成**（sherpa-onnx，无需联网、无 API Key）
- **ASR 转写**：系统识别、硅基流动 SenseVoice、**端上离线流式识别**（模型随 APK 打包）

### 🎨 AI 表达

- **表情包**：内置 + 自定义导入（支持 ZIP 批量，带名称/语义/别名），AI 按名称三级回退匹配自动发送，可设概率
- **AI 自动生图**：支持关键词触发（无视概率）与概率触发，带冷却保护；模型用 `[[生图: 画面描述]]` 标记，配「正在生成配图」动画
- **世界书（知识库）**：关键词触发的设定注入，**兼容 SillyTavern World Info JSON 双向导入导出**，可按角色绑定、多本同时生效
- **技能系统**：Markdown + YAML frontmatter 的技能（渐进披露），内置技能 + **技能市场安装**（对 AI 说一句"我想要某能力"即可）
- **MCP 扩展**：以 MCP 客户端接入外部工具服务（SSE / Streamable HTTP）

### 🧠 记忆

- **核心记忆**：事实 / 情感 / 偏好 / 事件 / 习惯 / 关系 六类，可手动增删改与筛选
- **临时记忆**：会话级工作记忆
- **情感日记**：查看 / 手写 / 编辑 / **AI 生成**，含天气、标签、心情
- 记忆**只存本地**，不上传服务器

### 📣 主动陪伴

- **AI 主动消息**：定时主动找你聊天（可判断"此刻不宜打扰"而跳过）
- **按亲密度调整频率**：关系越近，间隔越短
- **追问提醒**：你没回复时，按设定间隔与次数上限再来一句
- **免打扰**：时间段、深夜消息、每日条数上限
- **前台保活 + 开机自启**：被系统清理后自动恢复

### 👥 群聊与通讯录

- **多 AI 群聊**：多角色并行生成、按序发言、跨角色防复读
- **@提及**：可选择要 @ 的角色，AI 回复中的提及会自动规范化
- **通讯录**：拼音首字母侧边栏定位、搜索好友、好友 / 群聊分组

### 🔌 外部通道

| 通道 | 能力 | 说明 |
|---|---|---|
| **微信** | 扫码绑定，私聊收发、图片与表情包双向中转、消息转发到微信、**每个微信用户可分配不同伴侣** | 基于腾讯 iLink 官方协议，**仅私聊**；登录态有有效期，过期需重新扫码 |
| **QQ 机器人** | 扫码或手填 AppID 绑定，群聊 / 私聊收发、富媒体图片 | 基于 QQ 官方 Bot 开放平台，AccessToken 自动刷新 |
| **瑞幸咖啡** | 让 AI 帮你查门店、搜商品、**预览到手价与优惠券**、下单（生成支付二维码）、查单、取消 | 需在瑞幸开放平台登录取 Token |
| **自动化** | 定时（单次 / 每日 / 每周）触发"伴侣发消息"或"发通知"；可视化工作流支持 `AI_GENERATE` 节点（由 Agent 生成内容） | 任务由 WorkManager 持有，AI 也能通过工具帮你创建 |

### 🎛 个性化与设置

- **多套 API 配置**：增删改、启用切换、连接测试、模型探测；**角色级 API 隔离**（可指定某角色走哪套配置）
- **独立模型配置**：视觉模型（识图）、日记模型可各自单独配置，不占用主聊天 API
- **外观**：浅色 / 深色 / 跟随系统，**液态玻璃**风格，帧率设置
- **5 种语言**：简体中文、繁體中文、English、日本語、한국어
- **病娇模式**：AI 以略带占有欲的语气提及你的设备使用情况（本地统计，明确提示数据不出本机）
- **聊天详情（每角色独立）**：聊天背景、主动消息开关与间隔、新话题、深夜消息、追问、心理活动、精确时间感知（NTP）、表情包概率、生图覆盖配置、免打扰、拉黑、清空记录、重置
- **数据统计**：Token 用量（今日 / 本周 / 本月、按角色、近 30 天）
- **备份与恢复**：导出 `.lybk`（AES-256-GCM + PBKDF2 10 万次迭代，按伴侣勾选），跨设备 / 跨包名可恢复
- **系统适配中心**：权限、电池优化、自启动、后台运行等一键引导

## 🏗 技术架构

### 模块划分（25 个 Gradle 模块）

```
:app                                  应用入口（壳 Application + 导航 + ServiceRegistry 绑定）
├── core:domain                       零依赖契约层（只有接口与数据类 + ServiceRegistry）
├── core:common                       通用工具（内容安全过滤、设置存储、表情包、远程密钥）
├── core:database                     Room 持久化（v45，32 个实体）+ Repository + 逐字段加密
├── core:network                      AI 网络层：双协议客户端、上下文组装、TTS/ASR/生图适配
├── core:security                     Native 安全引擎 + Kotlin 编排（VMP/白盒 AES/KMS/零信任）
├── core:ui-common                    共享 Compose UI（主题、液态玻璃、图片查看/裁剪）
├── core:agent                        Rust Cordis Agent 门面 + UniFFI 绑定（文本回合主干）
├── core:wechat                       微信 iLink 协议客户端
└── feature:*  （15 个）
    chat / companion / groupchat / memory / notification / profile / settings
    wechat / qqbot / backup / coffee / automation / worldbook / mcp / skills
```

依赖方向严格单向：`feature → core`，`core` 之间按 `domain ← common ← database/network/security/ui-common/agent` 分层。feature 之间**不互相依赖**，跨模块协作通过 `ServiceRegistry` 的接口 + `app` 侧绑定完成。

### Agent 架构（本项目当前主干）

文本聊天回合已由 **Rust 实现的 Cordis Agent**（`agent-native/`，UniFFI 暴露为 `liblianyu_agent.so`）接管：

- Rust 侧**直读 Room 的 SQLite 文件**组装上下文、**直发 HTTP/SSE** 流式请求，增量经回调交给 Kotlin 落地
- **分层注入**：环境 → 身份角色 → 安全 → tools → 技能目录（渐进披露）→ 长期记忆召回
- **记忆 / 技能 / 世界书召回**的决策都在 Rust 侧完成，并留有可审计的 `dry_run`
- 工具四级查找：Cordis 核心插件 → 全局工具 → 会话工具 → Rust 内置工具
- Kotlin 侧只保留**副作用**（工具执行 `ToolHost`）与**流式落盘**（`StreamSink`）
- 旧的 Kotlin 本地工具循环与本地流式入口已随之删除；**识图（vision）目前仍走 Kotlin 通道**

### AI 能力

- **14 个服务商**：OpenAI、Anthropic、Gemini、DeepSeek、通义千问、Kimi、小米 MiMo、智谱、硅基流动、OpenRouter、Groq、讯飞星火、Clove（设备签名通道）、自定义
- **2 条协议线**：OpenAI 兼容 `chat/completions`（其余厂商均走此线）与 Anthropic Messages `v1/messages`
- **能力**：流式 SSE、工具调用 / Function Calling、推理内容（多字段名兼容）、视觉识图、多轮工具循环
- **上下文管理**：按 `总预算 − 输出 − 安全余量` 分档分配（system → 本轮上下文 → 滚动摘要 → 记忆 → 历史），滚动摘要带**水位线**避免历史重复注入；内置 50+ 模型上下文窗口映射表
- **复读抑制**：按厂商能力**逐字段门控**注入 `presence_penalty` / `frequency_penalty`，且保证注入值不低于服务端默认值（避免反向削弱）
- **TTS 9 家**（含端上离线）、**ASR 3 家**（含端上离线流式）、**生图**走 OpenAI 兼容 `images/generations`

### 数据

- **Room schema v45**，32 个实体：伴侣、消息（含正文分离与归档表）、群组、记忆、日记、世界书、技能、审计、世界事件账本、Token 用量、微信收发箱等
- **逐字段加密**（非全库加密）：聊天正文 `enc:v1:`（AndroidKeyStore AES/GCM）、记忆 `mem:v1:`、API Key 走 KMS/Tink 分级前缀。**DB 文件自身仍为明文**，敏感字段逐个加密

## 🛡 安全与加固

Release 包采用**壳 + 加密 + 虚拟机保护**的完整加固链路：

| 层 | 措施 |
|---|---|
| **壳层** | `classes.dex` 仅 **KB 级纯 Java 壳**；业务 DEX 全部剥离为 `assets/shell/*.dat` |
| **业务 DEX 加密** | **HMAC-SHA256 派生的 CTR 流密码**（4KiB 分块），密钥绑定**签名证书 + maps CRC64** → 重签即无法解密 |
| **native SO 加密** | `liblianyu_shell.so` / `liblianyu_security.so` 的 `.text` 段构建期加密，运行时自解密 + 完整性校验 |
| **VMP 虚拟化** | 关键函数编译为虚拟机字节码；**每次构建按证书派生种子随机化 opcode**；运行时校验白盒 AES / TEE 认证 / APK 签名三个锚点 |
| **白盒 AES** | 2.18 MB 白盒表（三段混淆 + 每轮假 T Box），搬入 `.text` 段并重算校验 |
| **密钥体系** | 五级密钥派生（白盒表 → NEON 寄存器临时密钥 → 四档会话密钥 → SM4 盲化 → TEE），NEON SIMD 参与并清 cache |
| **内存防护** | 保护页夹击（`mprotect(PROT_NONE)`）、栈金丝雀、`ptrace` 自附加、maps 检查 |
| **零信任** | PDP/PEP + 风险评分状态机（TRUST / SUSPICIOUS / BREACH），BREACH 触发锁死并擦除密钥 |
| **检测链** | **32 项**环境检测（root / hook / Frida / 模拟器 / 调试 / MITM / VPN / Zygisk / 内核模块 / 完整性等） |
| **国密** | SM2（签名/验签）、SM3、SM4 已实现 |
| **硬件信任** | AndroidKeyStore ECDSA P-256 + attestation 挑战，解析认证链；不可用则拒绝而非降级 |
| **证书绑定** | 4 条 SPKI pin（自有 API 域 1 条 + 更新通道 3 条） |
| **防调试** | `syscall` 级反调试 + TracerPid 检测（含 VM 内实现），仅 release 生效 |

### 打包链路（`tools/build.py --release`）

```
Phase 0   生成白盒 AES 表 + .text 硬化 + CRC32
Phase 0b  生成 VMP payload / 配置头
Phase 3b  按证书 CRC64 种子随机化 VMP opcode
Shell DEX javac + d8 → KB 级 classes.dex
Gradle    assembleRelease（跳过 Gradle 自带薄壳，取含明文 DEX 的原始 APK）
         ↓ 派生 DEX 加密密钥（证书 SHA-256）
Phase 6a  加密两个 SO 的 .text 段
Assembly  替换 classes.dex → 壳 / 剥离旧签名与业务 DEX / 注入 assets/shell/*.dat / apksigner 签名
Phase 6b  SO 完整性注入 + 重新签名
```

## 🔄 应用内更新

自建更新通道（非 GitHub Release），四层防护：

1. **客户端密钥**（nginx + 服务端双重校验）
2. **设备签名**：ES256 覆盖「方法 / 路径 / 请求体摘要 / 时间戳 / nonce / 设备号」
3. **时间窗 + 防重放**：±300s 时间窗、nonce 去重、按 IP 限流
4. **响应加固**：清单以 **AES-256-GCM 加密 + HMAC 签名**返回；APK 走**短时签名下载地址**防盗链

客户端支持**弱网自适应**：主源失败自动切镜像、断点续传、低速判定切换、最终 SHA-256 校验；提供前台更新与后台静默下载两种模式，支持忽略该版本与强制更新标志。

## 🚀 构建

### 环境要求

| 项目 | 版本 |
|---|---|
| JDK | **17** |
| Android SDK | compileSdk / targetSdk **35**，minSdk **26** |
| NDK | **30.0.14904198**（native 走 `ndk-build`） |
| JDK / Gradle / AGP | AGP **9.2.1** / Gradle **9.4.1**（腾讯镜像分发） |
| Python（仅打包） | 3.12+，需 `gmssl`；加固阶段另需 `pyelftools` |

### 调试构建

```bash
./gradlew assembleDebug
```

### 正式版（加固 + 签名）

正式版**必须**走打包脚本（裸 `assembleRelease` 的产物未加固，不可发布）：

```bash
python tools/build.py --release --skip-wb-aes
```

- `--skip-wb-aes`：沿用仓库内既有的白盒 AES 表（不加会重新生成密钥并覆写 `wb_tables.inc`，等于轮换安全材料）
- 签名口令通过环境变量提供：`YUNIAN_STORE_PASSWORD` / `YUNIAN_KEY_PASSWORD` / `YUNIAN_KEY_ALIAS`
- 若 `gradle.properties` 中的 JDK 路径与本机不一致（该文件可能被写成开发机专有路径），可用环境变量覆盖而无需改文件：

```bash
YUNIAN_GRADLE_JAVA_HOME='D:/Android Studio/jbr' \
YUNIAN_JDK_INSTALLATIONS='D:/Android Studio/jbr,C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot' \
python tools/build.py --release --skip-wb-aes
```

产物：项目根 `YuNian-v2.apk`（release 会额外拷到桌面）。

### 测试

```bash
./gradlew :core:common:testDebugUnitTest :core:network:testDebugUnitTest
```

> 注意：不加 `--rerun-tasks` 时 Gradle 可能报 `UP-TO-DATE`（缓存命中而非真实执行）。

## 📁 目录结构

```
YuNian/
├── app/                    应用入口、导航、ServiceRegistry 绑定、壳清单
├── core/
│   ├── domain/             零依赖契约层
│   ├── common/             通用工具与基础服务
│   ├── database/           Room 持久化（v45）
│   ├── network/            AI 网络层 / TTS / ASR / 生图
│   ├── security/           Native 安全引擎（C++）+ Kotlin 编排
│   ├── ui-common/          共享 Compose UI 与主题
│   ├── agent/              Rust Cordis Agent 门面 + UniFFI
│   └── wechat/             微信 iLink 协议客户端
├── feature/                15 个业务功能模块
├── agent-native/           Agent 的 Rust 源码（crate: lianyu-agent）
├── tools/                  打包与加固脚本（build.py 为正式版入口）
├── _server_update/         自建更新通道服务端（Node.js + nginx 片段）
└── docs/                   架构与安全文档
```

## ⚠️ 已知限制

以下能力**尚未接通或未实现**，请勿期待：

- **聊天页位置分享**：入口存在但点击提示"暂不可用"
- **群聊相机**：提示"相机功能开发中"
- **修改群名称 / 分享资料卡 / 举报**：界面存在，逻辑未接通（拉黑功能在聊天详情页可用）
- **厂商推送**：仅**华为**为真实实现，小米 / OPPO / vivo 仅预留接口（密钥为空）
- **MCP 服务器配置**：当前仅存内存，重启后需重新添加
- **微信服务端中继通道**：代码存在但未接入（现走 iLink 直连）
- **QQ 频道**：有发送接口，但未订阅频道事件，实际未开通
- **自动备份**：仅有手动导出 / 导入
- **本地 TTS 内置模型的自动下载**：未落地，需自备自定义模型
- **Dex2C 转译**：链路已接入 release 构建，但当前白名单转译产出为空
- **SM9**：仅有接口声明

## 🙏 致谢与声明

- 本项目使用或参考了众多开源项目（Compose、OkHttp、Room、sherpa-onnx、Kyant Backdrop、Cordis、SillyTavern 世界书格式等），详见应用内「关于 → 开源致谢」
- 仅供**学习与个人使用**；请遵守各第三方平台（微信、QQ、瑞幸等）的服务条款，勿用于商业或滥用用途
- 隐私：记忆、日记、聊天数据与统计均存储在**本机**，不上传服务器；网络请求仅发生在你自己配置的 AI 服务商与官方平台接口

---

<p align="center"><i>予念 · 予你一份念想</i></p>
