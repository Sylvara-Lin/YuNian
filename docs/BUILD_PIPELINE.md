---
name: yunian-build-pipeline
description: YuNian 完整打包构建流程 — 从克隆到签名 APK，含壳、Dex2C、安全加固全链路。
category: software-development
---

# YuNian 完整构建流水线

覆盖从零到签名 APK 的全部步骤：环境准备、源码配置、Native 编译、壳构建、安全加固、Dex2C 转译、签名验证。

## 一、环境要求

| 组件 | 版本 | 路径/命令 |
|------|------|-----------|
| JDK | 17+ | `java -version` |
| Android SDK | build-tools 37.0.0, platforms android-35 | `%LOCALAPPDATA%/Android/Sdk` |
| NDK | 30.0.14904198 | `%LOCALAPPDATA%/Android/Sdk/ndk/30.0.14904198` |
| Python | 3.11+ | `python --version` |
| Git Bash (MSYS) | 任意 | 构建脚本执行环境 |
| Gradle | 9.4.1 (wrapper) | `./gradlew` |

## 二、克隆与初始配置

### 2.1 获取源码

```bash
# 方式 A: 从 GitHub 克隆（需 SSH key，国内慢）
git clone -b main-dev --depth 1 git@github.com:linruoxi666/LianYu.git LianYu-maindev

# 方式 B: 解压用户提供的 zip
unzip YuNian-main-dev.zip -d LianYu-maindev
```

### 2.2 SDK 路径

编辑 `local.properties`：
```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

### 2.3 签名配置

**Keystore**: 复制到项目根目录 `release.keystore`
```bash
cp /path/to/Realy_release.keystore ./release.keystore
```

**密码写入** `gradle.properties`（终端会屏蔽密码，必须用 Python 写入）：
```python
# execute_code
with open('gradle.properties', 'a') as f:
    f.write('YUNIAN_STORE_PASSWORD=<REDACTED>\n')
    f.write('YUNIAN_KEY_ALIAS=your_alias\n')
    f.write('YUNIAN_KEY_PASSWORD=<REDACTED>\n')
```

### 2.4 环境变量（可选，推荐 gradle.properties）

```bash
export YUNIAN_STORE_PASSWORD="<你的 store 口令，勿写入仓库>"
export YUNIAN_KEY_ALIAS="your_alias"
export YUNIAN_KEY_PASSWORD="<你的 key 口令，勿写入仓库>"
```

## 三、源码准备

### 3.1 NDK 生成文件 stub

`core/security/src/main/cpp/generated/` 目录需要两个文件：

**dex2c_methods.cpp**:
```cpp
// Stub: dex2c transpiled methods
#include <jni.h>
#include <stdint.h>
#include "dex2c_registry.h"

const uint32_t gDex2cTextCrc32 = 0x00000000;
JNINativeMethod gDex2cMethods[] = {};
const size_t gDex2cMethodCount = 0;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}
```

**dex2c_registry.h**:
```cpp
#pragma once
#include <stdint.h>
#include <stddef.h>

extern JNINativeMethod gDex2cMethods[];
extern const size_t gDex2cMethodCount;
extern const uint32_t gDex2cTextCrc32;
```

### 3.2 从 Security 分支移植缺失类

main-dev 分支缺少以下类（需从 `LianYu-feature-security-six-dimensions` 复制）：

| 文件 | 路径 |
|------|------|
| KmsProvider.kt | `core/security/src/main/java/com/yunian/ai/security/` |
| SecurityOrchestrator.kt | 同上 |
| Sm4Cipher.kt | 同上 |
| CompositeVmpRuntime.kt | 同上 |
| SecurityGuard.kt | 同上 |

```bash
cp LianYu-security/.../core/security/src/main/java/com/yunian/ai/security/{KmsProvider,SecurityOrchestrator,Sm4Cipher,CompositeVmpRuntime,SecurityGuard}.kt \
   LianYu-maindev/core/security/src/main/java/com/yunian/ai/security/
```

### 3.3 ProGuard keep 规则

在 `app/proguard-rules.pro` 添加：
```
-keep class com.yunian.ai.security.SecurityOrchestrator { *; }
-keep class com.yunian.ai.security.Sm4Cipher { *; }
-keep class com.yunian.ai.security.CompositeVmpRuntime { *; }
-keep class com.yunian.ai.security.SecurityGuard { *; }
```

## 四、Native 编译配置

### 4.1 多 ABI 支持

**Application.mk**:
```makefile
APP_ABI := arm64-v8a armeabi-v7a x86_64 x86
APP_PLATFORM := android-26
APP_STL := c++_static
APP_OPTIM := release
APP_SHORT_COMMANDS := true
```

**build.gradle.kts** (`core/security/`):
```kotlin
ndk {
    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
}
```

### 4.2 壳 SO 符号导出 (version-script-shell.map)

```json
{
    global:
        JNI_OnLoad;
        Java_com_yunian_ai_security_StaticApkShell_*;
        Java_com_yunian_ai_security_MethodRecoveryEngine_*;
    local:
        *;
};
```

### 4.3 安全 SO 符号导出 (version-script.map)

```json
{
    global:
        JNI_OnLoad;
        lianyu_d2_decrypt;
        lianyu_text_start;
        lianyu_text_size;
        lianyu_text_decrypt_ptr;
        kms_provider_register_natives;
        Java_com_yunian_ai_common_NativeSafetyFilter_*;
    local:
        *;
};
```

### 4.4 Android.mk 壳 LDFLAGS

```makefile
LOCAL_LDFLAGS := -Wl,--version-script=.../version-script-shell.map -Wl,--gc-sections -Wl,-u,JNI_OnLoad
```

`-Wl,-u,JNI_OnLoad` 必须存在，否则 `--gc-sections` 会剥离 JNI_OnLoad。

### 4.5 dex-packer.cpp gDex2cTextCrc32 stub

```cpp
// 在 #include "g_vmp_config.h" 之后添加
const uint32_t gDex2cTextCrc32 = 0xFFFFFFFF;
```

liblianyu_security.so 和 liblianyu_dex2c.so 是独立 SO，需要各自提供 gDex2cTextCrc32 定义。

## 五、安全加固

### 5.1 WB-AES 白盒表生成

```bash
python tools/setup_wb_key.py --generate --out core/security/src/main/cpp/wb_tables.inc
```

生成真随机密钥表，替换占位符。密钥保存在 `.lianyu_wb_key`（需加入 .gitignore）。

### 5.2 Dex2C 转译

Release 构建时自动运行（Gradle 任务 `dex2cTranspile`）：

```bash
# 手动运行
python tools/dex2c_transpile.py \
  app/build/intermediates/dex/release/minifyReleaseWithR8/classes.dex \
  --whitelist tools/dex2c_whitelist.txt \
  --out-cpp core/security/src/main/cpp/generated/dex2c_methods.cpp \
  --out-h core/security/src/main/cpp/generated/dex2c_registry.h
```

**注意**: 运行后需修复 `dex2c_registry.h` 中的 `static const uint32_t gDex2cTextCrc32 = 0x...;` → `extern const uint32_t gDex2cTextCrc32;`

### 5.3 Dex2C 仅 Release 运行

`core/security/build.gradle.kts`:
```kotlin
tasks.matching { it.name.startsWith("configureNdkBuild") || it.name.startsWith("buildNdkBuild") }.configureEach {
    if (name.contains("Release")) {
        dependsOn("dex2cTranspile")
    }
}
```

Debug 构建不使用 Dex2C（stub 即可）。

## 六、壳 SO JNI 注册

### 6.1 壳 SO (liblianyu_shell.so) JNI 方法

| 类 | 方法数 | 注册方式 |
|----|--------|----------|
| StaticApkShell | 4 (nativeShellInitWithBlob, nativeWipeDexHeader, nativeEnableMemoryGuard, nativeAntiHookInit) | `extern "C"` 导出 |
| MethodRecoveryEngine | 1 (nativeRecoverClassMethods) | `extern "C"` 导出 |
| NativeBridge | 5 (verifySignature, isSafe, isDeviceRooted, isHookDetected, isDebugged) | RegisterNatives |
| StubApp | 3 (interface13/14/15) | RegisterNatives |

### 6.2 dex-extractor.cpp 关键结构

```cpp
// 前向声明
static jboolean nb_verifySignature(JNIEnv*, jobject, jobject);
// ... (其他 nb_* 函数)

// JNI_OnLoad 显式 visibility
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) 
    __attribute__((visibility("default")));

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    // 1. RegisterNatives for NativeBridge (shim stubs)
    // 2. RegisterNatives for StubApp (interface13/14/15)
    return JNI_VERSION_1_6;
}
```

### 6.3 反 Frida 检测（已有，XOR 混淆）

壳 SO 内置（`dex-extractor.cpp`）：
- Frida 端口扫描 (27040-27055)
- TracerPid 检测
- Frida/Xposed/Magisk/KernelSU 字符串匹配（XOR 混淆）
- HTTPCanary 代理检测

## 七、构建命令

### 7.1 Debug APK

```bash
# 在 git-bash 中
export YUNIAN_STORE_PASSWORD="<你的 store 口令，勿写入仓库>"
export YUNIAN_KEY_ALIAS="your_alias"
export YUNIAN_KEY_PASSWORD="<你的 key 口令，勿写入仓库>"

rm -rf core/security/build app/build/outputs/apk/debug

# 用 cmd.exe 调用（Msys bash 中 gradlew.bat 需要）
cmd.exe //c "gradlew.bat assembleDebug"
```

### 7.2 Release APK

```bash
# Native clean rebuild（改过 .cpp/.mk/.map 后必须）
rm -rf core/security/build app/build/outputs/apk/release

# 构建（跳过 lint 避免 Windows 文件锁）
cmd.exe //c "gradlew.bat assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease -x lintVitalReportRelease"
```

### 7.3 仅重建 Native SO（调试用）

```bash
# 单个 ABI
cmd.exe //c "gradlew.bat :core:security:buildNdkBuildRelease[arm64-v8a] --rerun-tasks"

# 全部 ABI
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  cmd.exe //c "gradlew.bat :core:security:buildNdkBuildRelease[$abi] --rerun-tasks"
done
```

## 八、验证

### 8.1 APK 签名

```bash
SDK="$LOCALAPPDATA/Android/Sdk"
BT=$(ls "$SDK/build-tools" | sort -V | tail -1)
cmd.exe //c "$SDK/build-tools/$BT/apksigner.bat" verify --print-certs <apk>
```

### 8.2 壳 SO 动态符号

```bash
NDK_NM="$SDK/ndk/30.0.14904198/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm"
$NDK_NM -D <path/to/liblianyu_shell.so>
```

期望输出：
```
T JNI_OnLoad
T Java_com_yunian_ai_security_StaticApkShell_nativeShellInitWithBlob
T Java_com_yunian_ai_security_StaticApkShell_nativeWipeDexHeader
T Java_com_yunian_ai_security_StaticApkShell_nativeEnableMemoryGuard
T Java_com_yunian_ai_security_StaticApkShell_nativeAntiHookInit
T Java_com_yunian_ai_security_MethodRecoveryEngine_nativeRecoverClassMethods
```

### 8.3 APK 结构审计

```bash
python tools/apk_audit.py <apk>
```

### 8.4 安全 SO WB-AES 检查

```bash
# 不应出现 "Placeholder WB-AES"
$NDK_NM -D <path/to/liblianyu_security.so> | grep -i placeholder
```

## 九、输出

| 构建类型 | 路径 | 大小 |
|----------|------|------|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | ~114 MB |
| Release | `app/build/outputs/apk/release/app-release.apk` | ~90 MB |

## 十、常见问题

### Q: `BUILD FAILED` NDK 报 `No rule to make target generated/dex2c_methods.cpp`
创建 stub 文件（见第三章 3.1）。

### Q: `error: redefinition of 'gDex2cTextCrc32'`
`dex2c_registry.h` 中改为 `extern const`（见第五章 5.2 注意事项）。

### Q: `UnsatisfiedLinkError` 在 StaticApkShell 方法上
检查 `version-script-shell.map` 是否包含 `Java_com_yunian_ai_security_StaticApkShell_*`。

### Q: JNI_OnLoad 不存在于动态符号表
检查 `Android.mk` 壳 SO 的 `LOCAL_LDFLAGS` 是否有 `-Wl,-u,JNI_OnLoad`。

### Q: 构建成功但 APK 大小不对（缺 ABI）
检查 `Application.mk` 的 `APP_ABI` 和 `build.gradle.kts` 的 `abiFilters`。

### Q: Gradle 任务 UP-TO-DATE 不重编 Native
删除 `core/security/build`，确保 `.cpp` 文件时间戳已更新，使用 `--rerun-tasks`。

### Q: Windows 文件锁 `Device or resource busy`
`./gradlew --stop` 杀掉守护进程，关闭 Android Studio，删除 build 目录。

### Q: 终端屏蔽密码（显示 `***`）
密码通过 `execute_code` + Python `open()` 写入 `gradle.properties`，禁止在 `terminal` 命令中传递密码。
