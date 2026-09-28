# YuNian Ultimate Shell — 构建与架构文档

## 概述

YuNian Ultimate Shell 是一套 Android APK 加固系统，通过**纯 Java 壳 + Native SO + VMP 虚拟化**实现对业务逻辑的全方位保护。

```
┌─────────────────────────────────────────────┐
│  YuNian-release.apk (76MB)                  │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ classes.dex (7KB)                    │    │
│  │ 纯 Java 壳：StaticApkShell          │    │
│  │ + SActivity + MethodRecoveryEngine  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ assets/shell/*.dat (40MB encrypted)  │    │
│  │ 业务 DEX — XOR 加密                 │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ lib/liblianyu_shell.so (143KB)      │    │
│  │ 壳 Native — DEX解密/注入/emu检测   │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ lib/liblianyu_security.so (9.2MB)   │    │
│  │ VMP + WB-AES + KMS + ZeroTrust      │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

---

## 一键构建

```bash
# Debug (模拟器)
python tools/build.py

# Release (真机)
python tools/build.py --release
```

**输出：**
- Debug: `app/build/outputs/apk/debug/YuNian-debug.apk`
- Release: `app/build/outputs/apk/release/YuNian-release.apk` + 桌面快捷方式

---

## 构建流程（4 阶段）

### Phase 1: 编译壳 DEX

```
Shell Java 源文件 (纯 Java, 零 Kotlin)
  ├── StaticApkShell.java   (壳 Application)
  ├── SActivity.java         (启动桩)
  └── MethodRecoveryEngine   (JNI 辅助)
              │
              ▼
    javac (Java 编译器)
              │
              ▼
    d8 (DEX 编译器)
              │
              ▼
    classes.dex (~7KB)
```

### Phase 2: Gradle 编译业务代码

```
gradlew assembleDebug/assembleRelease
  ├── Kotlin 业务逻辑编译
  ├── R8 代码优化（Release）
  ├── NDK 编译 Native SO
  │     ├── liblianyu_shell.so (dex-extractor.cpp)
  │     └── liblianyu_security.so (VMP + 安全引擎)
  └── 签名 + 打包
              │
              ▼
    app-debug.apk / app-release.apk
```

### Phase 3: 业务 DEX 加密

```
Gradle APK
  ├── classes.dex (R8合并, 10MB)
  ├── classes2.dex ..
  └── classesN.dex ..
              │
              ▼
    XOR 加密 (HMAC-SHA256 密钥)
              │
              ▼
    assets/shell/
  ├── classes.dat (10MB)
  ├── classes2.dat ..
  └── classesN.dat ..
```

### Phase 4: APK 组装 + 签名

```
源 APK (Gradle)
  └─ 剥离 META-INF/ (旧签名)
  └─ 剥离原有 DEX / classes*.dex
  └─ 替换 classes.dex → 壳 DEX (7KB)
  └─ 替换 lib/*.so → 新编译 SO
  └─ 注入 assets/shell/*.dat
              │
              ▼
    apksigner sign (V3 签名)
              │
              ▼
    YuNian-release.apk ✓
```

---

## 启动链路

```
系统启动 APK
  │
  ▼
System.loadLibrary("lianyu_shell")
  │
  ├── JNI_OnLoad
  │     ├── syscall 反调试检测
  │     ├── HMAC .text 完整性校验
  │     ├── 设备指纹校验
  │     └── NativeBridge 注册
  │
  ▼
Application.attachBaseContext()
  │
  ├── nativeAntiHookInit()
  ├── nativeShellInitWithBlob(code_items)
  ├── nativeEnableMemoryGuard()
  │
  ├── injectBusinessDex()
  │     ├── 遍历 assets/shell/*.dat
  │     ├── nativeDeriveDexKey() → XOR 密钥
  │     ├── xorDecrypt() → 解密业务 DEX
  │     ├── InMemoryDexClassLoader (DirectByteBuffer)
  │     └── injectDexElements()
  │           └── 合并到 PathClassLoader
  │               (保持实例不变, ContentProvider兼容)
  │
  ▼
Application.onCreate()
  │
  ├── WorkManager 初始化
  │
  ▼
System 初始化 ContentProvider ✓
  (通过扩展后的 PathClassLoader 加载)
  │
  ▼
MainActivity.onCreate()
  │
  ├── SecurityGuard.init()
  │     ├── 模拟器检测 (Release → killProcess)
  │     ├── WB-AES 初始化
  │     ├── KMS 密钥派生
  │     └── HardwareKeyAttestation (TEE/StrongBox)
  │
  ▼
业务界面显示 ✓
```

---

## 安全架构

### L0: 构建流水线
- 全自动 4 阶段构建
- 零 apktool 依赖，零 Manifest 修改
- Windows / Linux / Mac 全兼容

### L1: Native C++ (13 模块)
| 模块 | 文件 | 功能 |
|------|------|------|
| Native Bridge | native-bridge.cpp (127KB) | JNI 注册 + 40+ 安全方法 |
| Memory Guard | memory-guard.cpp | W^X + Stack Canary |
| Integrity Guard | integrity-guard.cpp | SO .text HMAC-SHA256 |
| Anti-Debug | anti_debug_syscall.h | syscall 级反调试 |
| Zero Trust | zero-trust.cpp | PDP + PEP 信任引擎 |
| WB-AES-256 | whitebox-aes.cpp | 4 级加固 AES |
| KMS v2.0 | kms-engine.cpp | 5 级密钥体系 + NEON |
| SM Cipher | sm-cipher.cpp | SM2/SM3/SM4/HMAC-SHA256 |
| APK 签名绑定 | apk-sig-key.cpp | SM3(证书) → 密钥派生 |
| Native Safety | native_safety.cpp | AC 自动机 + Bayesian |
| String Table | string-table.cpp | AES-CBC 混淆 |
| Decrypt Stub | decrypt-stub.cpp | .text 自解密 |
| Native Codec | native_codec.cpp | 编码支持 |

### L2: VMP v3.0 (8/9 函数)
- wb_aes_keycheck / kms_derive_sk
- vmp_apk_sig_verify / vmp_frida_heartbeat
- vmp_root_detect / vmp_code_integrity
- vmp_sm3_hash / vmp_tee_attest

### L3: Kotlin/Java
- SecurityGuard — 中央安全门
- HardwareKeyAttestation — TEE/StrongBox ECDSA
- CertificatePinning — 12 SPKI 固定

### L4: 网络 + 检测
- WB-AES-256-GCM 请求体加密
- Certificate Pinning（12 服务器）
- MITM/Proxy/VPN 检测

### L5: 硬件信任
- Android KeyStore TEE
- NEON SIMD 寄存器
- Tink AEAD
- ARM 寄存器指纹

---

## 安全特性

| 特性 | 状态 | 说明 |
|------|------|------|
| 业务 DEX 静态加密 | ✅ | XOR + HMAC-SHA256 密钥 |
| Maps 内存布局绑定 | ✅ | /proc/self/maps CRC64 |
| 证书反重打包 | ✅ | cert SHA-256 嵌入 |
| 模拟器 Release 封杀 | ✅ | SecurityGuard + FLAG_DEBUGGABLE |
| VMP 动态调度 | ✅ | g_handler_table PID 混洗 |
| VMP 死循环陷阱 | ✅ | DEAD_LOOP_TRAP |
| VMP fetch 自校验 | ✅ | 每 1024 条指令 |
| CNTVCT_EL0 时间戳 | ✅ | 检测单步调试 |
| DEX AAD 校验和 | ✅ | CRC64 + nonce → AAD |
| HW 签名会话密钥 | ✅ | TEE/StrongBox ECDSA |
| ClassLoader 注入 | ✅ | dexElements (非替换) |

---

## 文件清单

| 文件 | 大小 | 说明 |
|------|------|------|
| classes.dex | 7KB | 纯 Java 壳（jadx 可见） |
| assets/shell/classes.dat | 10-40MB | 业务 DEX（XOR 加密） |
| lib/liblianyu_shell.so | 143KB | 壳 Native |
| lib/liblianyu_security.so | 9.2MB | VMP + 安全引擎 |

---

## 目录结构

```
YuNian/
├── tools/
│   ├── build.py                 ← 一键构建脚本
│   ├── build_ultimate_shell.py  ← 旧版（兼容）
│   └── apktool.jar              ← 不再使用
├── app/
│   ├── build.gradle.kts
│   └── build/tmp/ultimate_shell/  ← 壳源码
├── core/security/src/main/
│   ├── cpp/                     ← Native C++
│   │   ├── dex-extractor.cpp    ← 壳 SO 核心
│   │   ├── vm-engine.cpp        ← VMP 引擎
│   │   ├── native-bridge.cpp    ← JNI 桥
│   │   └── ...
│   └── java/com/yunian/ai/security/
│       ├── SecurityGuard.kt     ← 安全门
│       ├── HardwareKeyAttestation.kt ← TEE
│       └── ...
└── docs/
    └── BUILD.md                 ← 本文档
```

---

## 常见问题

**Q: 为什么不在 Release APK 中剥离 ContentProvider？**
A: 剥离会导致 WorkManager、Firebase 等第三方 SDK 找不到必要的 Provider 而崩溃。改用 `dexElements` 注入方案后，ContentProvider 可以在注入后的 PathClassLoader 中正常加载。

**Q: 模拟器上 Debug 包能运行，Release 包为什么不行？**
A: 设计如此。SecurityGuard.init() 检测到模拟器且 `FLAG_DEBUGGABLE=0` 时，调用 `Process.killProcess()` 自毁。这是反逆向工程的核心防线。

**Q: 重打包（re-sign）能绕过保护吗？**
A: 不能。DEX 解密密钥通过 `derive_key_from_apk_sig()` 绑定到 APK 签名证书。重签会得到不同的证书 → 密钥错误 → DEX 无法解密 → App 闪退。

**Q: Windows 上 apktool 不稳定怎么办？**
A: `build.py` 已完全去掉 apktool 依赖。不再需要解码、修改、重打包 AndroidManifest。
