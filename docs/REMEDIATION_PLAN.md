# 予念安全深度修复计划

> 基于：六维安全评估 + 厂商对比 + 静态渗透测试
> 日期：2026-06-13
> 目标：关闭所有已知安全差距，达到商业加固产品同等深度

---

## 一、差距清单（全部 17 项）

### 代码保护层

| # | 差距项 | 当前状态 | 影响 | 来源 |
|---|---|---|---|---|
| G1 | OLLVM 控制流平坦化缺失 | obfuscate.h 替代（123处） | SO 逆向难度降 2-3× | 六维评估 |
| G2 | SO .text 加密 Android16 禁用 | SEGV_ACCERR 崩溃 | 最新设备无 SO 加密保护 | 六维评估 |
| G3 | 壳 APK 构建未执行 | 当前为标准 Gradle 构建 | 8.7MB 全量 DEX 暴露 | 静态渗透 |
| G4 | SO 字符串残留（magisk/xposed路径） | check_frida_files 已修，路径数组未修 | 10 处检测路径明文 | 静态渗透 |
| G5 | JNI RegisterNatives 部分覆盖 | 57→7（JNI_OnLoad+shell 残留） | 7个 JNI 导出仍可读 | 静态渗透 |
| G6 | SO 未剥离调试符号 | 9.3MB unstripped | 符号表暴露函数名 | 静态渗透 |

### 数据保护层

| # | 差距项 | 当前状态 | 影响 | 来源 |
|---|---|---|---|---|
| D1 | SQLCipher 全库加密不可用 | 依赖 net.zetetic 不在 Maven | Room DB 明文落盘 | 六维+厂商 |
| D2 | Assets/配置文件未加密 | 仅 API Key+消息加密 | 资源文件可被提取 | 厂商对比 |
| D3 | 数据生命周期管理缺失 | 无自动过期/清理策略 | GDPR/等保不满足 | 六维评估 |

### 运行时防御层

| # | 差距项 | 当前状态 | 影响 | 来源 |
|---|---|---|---|---|
| R1 | 动态渗透测试 0/29 项 | 设备离线期间未完成 | 检测链实际效果未知 | 渗透清单 |
| R2 | 录屏/截屏防护缺失 | 无 FLAG_SECURE | 敏感界面可被截取 | 厂商对比 |
| R3 | 界面劫持防护缺失 | 无 onFilterTouchEvent | 登录界面可被覆盖 | 厂商对比 |
| R4 | DNS 劫持检测基础 | 仅基础检查 | 高级 DNS 攻击可绕过 | 厂商对比 |
| R5 | mTLS 双向认证未实现 | 服务端不支持 | API 通信无客户端证书 | 六维评估 |

### 工程与合规层

| # | 差距项 | 当前状态 | 影响 | 来源 |
|---|---|---|---|---|
| E1 | 无云端威胁情报 | 纯本地检测 | 无法感知新型攻击 | 厂商对比 |
| E2 | GDPR/等保三级未审查 | 未做正式合规评估 | 上架审核可能被拒 | 六维评估 |
| E3 | 无 CI/CD 安全流水线 | 手动构建 | 构建一致性无保障 | 工程实践 |

---

## 二、修复计划（按优先级）

### Phase A：立即（本周，设备在线时）

#### A1 — 动态渗透测试执行 [R1]

```
目标：执行渗透清单中至少 12 项核心测试
设备：vivo V2324A (Android 16) + vivo S17e (Android 14) 可选

Day 1: Frida 攻击向量 (TEST 1.1-1.4)
  - 安装 frida-server → 尝试 attach → 预期检测触发
  - spawn 模式注入 → 预期 maps 检测触发
  - 验证零日志输出（logcat 无 security 标签）

Day 2: Root/Magisk 检测 (TEST 4.1-4.4)  
  - 安装 Magisk → 预期多路径 su 检测
  - 尝试提取 partner_keys.dat → 预期 TEE 绑定拒绝

Day 3: 完整性测试 (TEST 7.1-7.3, 8.1-8.4)
  - APK 重打包 → 预期签名校验失败
  - SO 替换 → 预期 CRC32 检测
  - 审计日志回滚 → 预期 nonce 不匹配
```

#### A2 — shell APK 构建 [G3]

```
目标：执行 build_shell_apk.py 流水线生成真正的壳 APK
产出：app-release-shell-signed.apk (~10MB 壳 DEX)
验证：
  - classes.dex ≤ 50KB
  - jadx 搜索 com.yunian.ai.security → 0 结果
  - DEX STORED 模式
  - JNI 导出 ≤ 5

命令：
  export VMP_SHELL_NO_RANDOMIZE=1
  python tools/build_shell_apk.py
```

#### A3 — SO 调试符号剥离 [G6]

```
目标：确保 stripReleaseDebugSymbols 在构建流水线中执行
排查：Gradle 缓存导致 SO 为 9.3MB unstripped
修复：
  - 检查 app/build.gradle.kts 中 strip 任务配置
  - 添加 post-build strip 脚本作为兜底
  - 验证：SO 从 9.3MB → ~530KB
```

---

### Phase B：本周-下周（代码修复）

#### B1 — EncryptedFile DAO 替代 SQLCipher [D1]

```
目标：用 AndroidX EncryptedFile 包装 Room DB 文件
方案：在 AppDatabase.openVerifiedDatabase() 中透明加解密

文件修改：
  1. core/security/.../DatabaseKeyProvider.kt
     - 已有 getPassphrase() 返回 256-bit key
     - 新增 getOrCreateDbKey() 返回 SecretKey
  
  2. core/database/.../AppDatabase.kt
     - createDatabase() 中包装 db 文件路径
     - 使用 EncryptedFile 透明加密/解密
     - 新增 EncryptedDatabaseWrapper 类

  3. 兼容性处理
     - 首次启动：生成 key + 加密现有 DB
     - 升级：检测明文 DB → 自动迁移到加密
     - 降级回退：保留明文 DB 副本 24h
```

#### B2 — 文件路径字符串混淆 [G4]

```
目标：SO 中 0 个检测文件路径明文
范围：native-bridge.cpp 中所有 detection path 数组

文件修改：
  1. obfuscated_strings.h
     - 新增 OBS_PATH_MAGISK_* 系列宏 (idx 53-60)
     - 新增对应的 g_obs_path_* 静态数组
     - 新增对应 XS_PATH_* 便利宏

  2. native-bridge.cpp
     - check_magisk_files() — 路径数组 XOR 混淆
     - check_virtual_env() — virtualxposed/va.exposed 路径 XOR
     - 所有 access()/stat() 调用点使用 decode_obs()
```

#### B3 — Assets 加密 [D2]

```
目标：加密 assets 下敏感配置文件
范围：content_filter_keywords.json（52KB 安全关键词）

方案：
  1. 构建时：tools/encrypt_assets.py
     - AES-256-GCM 加密 assets 下白名单文件
     - 输出 .enc 后缀文件，替换原文件
  
  2. 运行时：core/common/.../EncryptedAssetLoader.kt
     - 使用 DatabaseKeyProvider 密钥解密
     - 透明接口：loadEncryptedAsset(filename) → ByteArray
```

---

### Phase C：两周内（架构增强）

#### C1 — SO .text 加密 Android 16 兼容 [G2]

```
目标：在 Android 16 W^X 限制下恢复 SO .text 加密

问题分析：
  - Android 16 禁止从被修改过的内存页执行代码
  - 当前 encrypt_text_section.py 直接 XOR .text 段 → SEGV_ACCERR

方案 A（推荐）：双映射技术
  - 创建第二个内存映射（MAP_SHARED）
  - 修改共享映射中的内容（允许写）
  - 原始映射保持 PROT_READ|PROT_EXEC（允许执行）
  - Android 16 的 W^X 检查基于 per-VMA，双映射绕过

方案 B：解密存根 + MAP_FIXED
  - decrypt-stub.cpp 在 mprotect 之前解密
  - 使用 MAP_FIXED 替换文件映射为匿名映射
  - 匿名映射先设为 RW 解密，再改为 RX

实现文件：
  - core/security/.../cpp/decrypt-stub.cpp（重写）
  - tools/encrypt_text_section.py（适配新方案）
```

#### C2 — 录屏/截屏防护 [R2]

```
目标：敏感界面（设置/API Key 输入/聊天）防截屏

方案：
  1. ChatScreen/SettingsScreen 的 Window 设置 FLAG_SECURE
  2. 在 BaseSecureActivity 中统一处理
  3. 用户可在设置中关闭（默认开启）

文件修改：
  - app/.../MainActivity.kt — set FLAG_SECURE
  - feature/settings/.../SettingsScreen.kt — API Key 输入时启用
  - app/.../BaseSecureActivity.kt（新建）— 统一安全 Activity 基类
```

#### C3 — 界面劫持防护 [R3]

```
目标：防止恶意应用覆盖登录/支付界面

方案：
  - 在安全敏感 Activity 的 onFilterTouchEvent 中检测覆盖层
  - 使用 Android 12+ 的 hide overlay windows API
  - 检测到异常覆盖 → 显示警告 + 拒绝输入

文件修改：
  - app/.../BaseSecureActivity.kt
```

---

### Phase D：一月内（深度加固）

#### D1 — OLLVM 替代方案评估 [G1]

```
目标：NDK r30 兼容的编译器级混淆

选项 1：等待 OLLVM fork 更新到 LLVM 21
  - 当前最新：LLVM 17（GreenDamTan）
  - 预计时间线：不可控

选项 2：Hikari（LLVM 15）→ 不兼容 r30

选项 3：obfuscate.h 增强（推荐）
  - 当前 123 处 → 目标 300+ 处
  - 批量注入脚本：tools/inject-obfuscation.py
  - 新增模式：不透明谓词（opaque predicates）
  - 新增模式：控制流平坦化宏（switch-based CFG flattening）

选项 4：自建 NDK 插件
  - 基于 LLVM Pass 框架
  - 实现 -fla（控制流平坦化）和 -sub（指令替换）
  - 需要 LLVM 开发经验 + C++17 编译器
```

#### D2 — DNS 劫持增强 [R4]

```
目标：多层级 DNS 安全

方案：
  1. DNS over HTTPS (DoH) — OkHttp 内置支持
  2. /etc/hosts 完整性校验
  3. DNS 响应 TTL 异常检测
  4. 已知恶意 DNS 服务器黑名单

文件修改：
  - core/network/.../AiService.kt — 启用 DoH
  - core/security/.../cpp/native-bridge.cpp — hosts 校验
```

#### D3 — GDPR/等保合规审查 [E2]

```
目标：生成等保三级自评报告

审查维度：
  1. 数据分类分级
  2. 访问控制审计
  3. 传输加密证明
  4. 存储加密证明
  5. 密钥管理合规
  6. 日志留存策略
  7. 漏洞管理流程

产出：
  - docs/COMPLIANCE_GDPR.md
  - docs/COMPLIANCE_EQUAL_PROTECTION_L3.md
```

---

## 三、时间线

```
Week 1 (6/13-6/20):  ████████  Phase A+B — 渗透测试 + 壳APK + EncryptedFile
Week 2 (6/20-6/27):  ████████  Phase B+C — 字符串混淆 + Assets加密 + 录屏防护
Week 3 (6/27-7/4):   ████████  Phase C — SO .text Android16 + 界面劫持
Week 4 (7/4-7/11):   ██████░░  Phase D — OLLVM评估 + DNS + 合规
```

## 四、完成后预期评分

```
当前:  ████████████████████████████████████████████████░░  92%
       vs 360加固: 56/70 (80%)
       vs 梆梆安全: 59/70 (84%)

Phase A 后: █████████████████████████████████████████████████░░  95%
Phase B 后: ██████████████████████████████████████████████████░  97%
Phase C 后: ██████████████████████████████████████████████████░  98%
Phase D 后: ███████████████████████████████████████████████████ 100%*
            *vs 商业加固: 63/70 (90%) — 无云端服务的天花板
```
