# lianyu-agent — LianYu Agent 中间层（Rust 核心 + UniFFI 桥接）

## 定位

Agent 层独立于消息层，纯 Rust 实现核心决策逻辑，通过 UniFFI 桥接 Kotlin：

```
用户请求 → AgentRunner(Rust) → ModelGateway回调(Kotlin: AiServiceProvider)
              │
              ├→ 内置工具(纯 Rust)：emit_segmented(按标点分段) / send_sticker / emit_bubble
              ├→ 会话级工具(执行走 ToolHost 回调)
              │
              ▼ 输出 AgentEvent(bubble/sticker/status)
         消息层落地（本次不耦合，事件流由 Kotlin 消费）
```

## 目录

| 路径 | 说明 |
|---|---|
| `src/segmenter.rs` | 按标点分段算法（移植 `core:common MessageSegmenter`，UTF-8 字符级） |
| `src/agent.rs` | 工具注册表 + Agent 循环状态机 + UniFFI 导出 |
| `src/lib.rs` | crate 入口 + UniFFI scaffolding |
| `uniffi.toml` | Kotlin 绑定配置（包名 `com.lianyu.ai.agent.uniffi`） |
| `bindings/` | uniffi-bindgen 生成的 Kotlin 绑定（generated/ 不入库） |

## 构建

```bash
# 1. 安装 Android targets（TUN 代理直连即可）
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# 2. 安装 cargo-ndk
cargo install cargo-ndk

# 3. 宿主编译验证（含单元测试）
cargo build
cargo test

# 4. 交叉编译 4 ABI → core/agent jniLibs
#    （Windows 需先设 ANDROID_NDK_HOME）
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
  -o ../core/agent/src/main/jniLibs build --release

# 5. 生成 Kotlin 绑定（library 模式，从 .so 提取元数据）
cargo run --features cli-bin --bin uniffi-bindgen -- generate \
  --library target/aarch64-linux-android/release/liblianyu_agent.so \
  --language kotlin --out-dir ../core/agent/src/main/kotlin --no-format
```

## 关键陷阱（踩坑记录）

1. **`strip = true` 会杀死 bindgen**：release profile 若 `strip = true`，
   `.symtab` 被移除，而 `uniffi-bindgen generate --library` 的 ELF 提取逻辑遍历
   `.symtab` 中的 `UNIFFI_META_*` 符号。结果：命令退出码 0、静默产出**空结果**。
   必须用 `strip = "debuginfo"`（保留符号表、去掉调试信息）。
2. **uniffi 0.29 无独立 bindgen 二进制**：CLI 是库 API `uniffi::uniffi_bindgen_main()`，
   本 crate 内置 wrapper bin（`--features cli-bin`）。
3. **`uniffi.toml` 中 `cdylib_name` 是字符串**，不是 per-platform map：
   `cdylib_name = "lianyu_agent"`（0.29 仅支持单值）。
4. **proc-macro 模式不需要 build.rs**（`generate_scaffolding` 会按 UDL 解析 lib.rs 而失败），
   `setup_scaffolding!()` 在编译期生成 scaffolding。
5. **回调 trait 以 `Arc<dyn Trait>` 传递**（`Box<dyn Trait>` 不满足 Lift/TypeId）。
6. **Android 侧绑定依赖 JNA**：`implementation("net.java.dev.jna:jna:5.13.0@aar")`，
   AGP 自动打包 `libjnidispatch.so` 进 APK。

## 与旧架构关系

- Rust 侧等价物 = 现有 `AiToolLoopRunner` + `ToolRegistry` + `MessageSegmenter`
- 现有 Kotlin 代码**不改动**；`core:agent` 作为独立模块接入，`AiServiceProvider` 经 `ModelGateway` 回调复用
- 工具副作用（表情包落库、气泡落地）经 `ToolHost` 回调 / `AgentEvent` 事件流由 Kotlin 消费
