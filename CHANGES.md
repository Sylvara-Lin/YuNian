# YuNian 安全加固 — 改动记录

## 概览

本次改造将 YuNian Android 应用从标准 Gradle 构建升级为**极简壳架构**：
- 壳 DEX: 9.5MB → 6.1KB (纯 Java, 零 Kotlin 依赖)
- 壳 SO: 9KB → 122KB (VMP1 双指令集 + syscall 反调试 + HMAC-SHA256 + 设备指纹)
- 启动链: 零桩 — 系统直接加载业务 MainActivity

---

## 一、Kotlin/Java 层安全加固

### 1. fail-open → fail-close (4 文件)

| 文件 | 改动 |
|------|------|
| `core/security/.../NativeBridge.kt` | `UnsatisfiedLinkError` → `throw RuntimeException` |
| `core/security/.../KmsProvider.kt` | `UnsatisfiedLinkError` → `throw RuntimeException` |
| `core/security/.../SecurityGuard.kt` | `catch(_) { false }` → `catch(_) { return true }` |
| `core/security/.../OatDisabler.kt` | 空 `catch` → `Log.w(...)` |

### 2. 审计修复 (4 项)

| ID | 文件 | 修复 |
|----|------|------|
| VULN-01 | `DeviceIdProvider.kt` | `UUID.randomUUID()` → `SHA-256(AndroidID + Build.SERIAL + Build.FINGERPRINT)` |
| VULN-02 | `RemoteKeyProvider.kt` | `sessionKey` 直接当 AES key → PBKDF2-SHA256 100k 迭代派生 |
| VULN-03 | `RemoteKeyProvider.kt` | 删除 `injectFallbackKeys()` 公开后门 |
| VULN-04 | `RemoteKeyProvider.kt` | 硬编码 XOR URL → `serverUrl` 可配置变量 |

### 3. 代码清理

- `AppUpdateManager.kt`: 移除硬编码 GitHub URL
- `native-bridge.cpp`: 清除硬编码 URL

---

## 二、C++ Native 层加固

### 1. 壳 SO (liblianyu_shell.so): 9KB → 122KB

| 功能 | 实现文件 |
|------|---------|
| **VMP1 双指令集加密恢复** | `dex-extractor.cpp`: `shell_decrypt_vmp1_blocks()` + `Java_..._nativeDecryptVmp1Blocks` |
| **syscall 反调试** | `anti_debug_syscall.h`: 绕过 libc hook, 直调 `syscall(__NR_openat/read/connect)` |
| **HMAC-SHA256 完整性校验** | `hmac_sha256.h`: JNI_OnLoad + init_array + 函数入口分散校验 |
| **设备指纹降级容灾** | `device-fingerprint.h`: 四级 (full/partial/degraded/none), 绝不 abort |

### 2. 安全 SO (liblianyu_security.so)

| 功能 | 实现文件 |
|------|---------|
| **g_method_table 栈解密** | `dex-extractor.cpp`: `decrypt_entry_to_stack()` + `SECURE_ZERO` |
| **VMP opcode 随机化** | `randomize_vmp_ops.py`: 签名证书 CRC32 种子随机置换 31 指令 |
| **VMP SBOX/GFMUL 常量时间** | `vm_hardening.h`: 消除侧信道 |
| **VMP 控制流平坦化** | `vm_hardening.h`: 不透明谓词 + 随机气泡 + 死代码注入 |
| **WB-AES CRC32 校验** | `gen_wb_tables.py` + `whitebox-aes.cpp`: 真实 CRC32 (非 XOR 占位) |
| **WB-AES .text 隐藏** | `wb_tables_harden.py`: 3MB 表数据注入 `.text` 段 |
| **KMS dev key ASLR 派生** | `kms-engine.cpp`: 运行时从地址熵派生 |
| **Integrity HMAC 做实** | `integrity-guard.cpp`: 双 HMAC 自校验 |
| **AES-CBC 随机 IV** | `native_bridge.cpp`: `secure_random_bytes` + IV 前缀 |

### 3. Dex2C SO (liblianyu_dex2c.so)

| 功能 | 实现文件 |
|------|---------|
| **类级合并转译** | `dex2c_class_merge.py`: switch-dispatch + 同方法字节码嵌入 |
| **白名单 @class 格式** | `dex2c_whitelist.txt` |

### 4. Android.mk 修复

- `core/security/src/main/cpp/Android.mk`: 追加 `-Wl,-u,JNI_OnLoad` 防止 gc-sections 剥离

---

## 三、极简壳 DEX 构造

### 架构

```
6.1KB 纯 Java 壳 DEX (classes.dex)
    ├── StaticApkShell.java  —— Application, loadLibrary + DEX注入
    ├── SActivity.java       —— 透明跳转到 MainActivity
    └── MethodRecoveryEngine.java —— JNI 桥接

25 业务 DEX (assets/shell/*.dex)
    └── 由 InMemoryDexClassLoader → PathClassLoader.dexElements 注入
```

### 关键技术

- **纯 Java, 零 Kotlin**: javac + d8 手搓, 无 `kotlin.jvm.internal.Intrinsics` (省 1.2MB)
- **DEX 路径注入**: 反射 `BaseDexClassLoader.pathList.dexElements` 合并数组
- **ContentProvider 删除**: manifest 中所有 Provider 会在 `attachBaseContext` 前初始化, 导致 ClassNotFoundException 阻止 Application 启动
- **appComponentFactory 删除**: `CoreComponentFactory` 缺类导致静默降级

### 一键构建

```bash
python tools/build_ultimate_shell.py
```

---

## 四、文件清单

### 新增文件

| 文件 | 用途 |
|------|------|
| `tools/build_ultimate_shell.py` | 一键打包脚本 |
| `tools/apktool.jar` | APK 解码/重打包工具 |
| `tools/proguard-shell.pro` | R8 ProGuard 白名单规则 (备用) |
| `tools/r8_shell_extract.py` | R8 DEX 裁剪脚本 (备用) |
| `core/security/src/main/cpp/anti_debug_syscall.h` | syscall 反调试 |
| `core/security/src/main/cpp/hmac_sha256.h` | HMAC-SHA256 紧凑实现 |
| `core/security/src/main/cpp/device-fingerprint.h` | 设备指纹降级 |
| `core/security/src/main/cpp/vm_hardening.h` | VMP 硬化层 |
| `core/security/src/main/cpp/g_vmp_config.h` | VMP opcode 随机映射 |
| `tools/randomize_vmp_ops.py` | VMP opcode 随机化工具 |
| `tools/gen_wb_tables.py` | WB-AES 表生成器 (CRC32) |
| `tools/wb_tables_harden.py` | WB-AES 表注入 .text 段 |
| `tools/dex2c_class_merge.py` | Dex2C 类级合并转译器 |
| `tools/dex2c_whitelist.txt` | Dex2C 白名单 (@class 格式) |

### 修改文件

| 文件 | 改动 |
|------|------|
| `core/security/src/main/cpp/dex-extractor.cpp` | VMP1 解密 + syscall + HMAC + 指纹 + table 加密 |
| `core/security/src/main/cpp/vm-engine.cpp` | 集成 vm_hardening.h |
| `core/security/src/main/cpp/vm-bytecode.cpp` | 随机化 opcode |
| `core/security/src/main/cpp/whitebox-aes.cpp` | CRC32 校验 + 注释清理 |
| `core/security/src/main/cpp/integrity-guard.cpp` | HMAC 做实 |
| `core/security/src/main/cpp/kms-engine.cpp` | dev key ASLR 派生 |
| `core/security/src/main/cpp/native-bridge.cpp` | 随机 IV |
| `core/security/src/main/cpp/dex-packer.cpp` | HMAC 替换 CRC32 |
| `core/security/src/main/cpp/Android.mk` | 追加 JNI_OnLoad 导出 |
| `core/security/src/main/java/.../NativeBridge.kt` | fail-close |
| `core/security/src/main/java/.../KmsProvider.kt` | fail-close |
| `core/security/src/main/java/.../SecurityGuard.kt` | fail-closed catch |
| `core/security/src/main/java/.../OatDisabler.kt` | 日志替代空 catch |
| `core/common/src/main/java/.../DeviceIdProvider.kt` | SHA-256 硬件绑定 |
| `core/common/src/main/java/.../RemoteKeyProvider.kt` | PBKDF2 + 后门删除 + URL 可配置 |
| `shell/build.gradle.kts` | 恢复为 JVM 模块 |
| `app/proguard-rules.pro` | 修复通配符 |
