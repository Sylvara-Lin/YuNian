# YuNian Release APK 安全审计报告
> 日期: 2026-06-15 | 目标: app-release.apk 90.5MB | 环境: 模拟器 x86_64 API35

## 1. 签名校验 ✅
- V2+V3 签名有效
- Android OS 级别阻止重签名安装 (INSTALL_FAILED_UPDATE_INCOMPATIBLE)
- App 层 NativeBridge 签名验证日志不可见 (SecureLog 静默)

## 2. 壳安全 ✅
- Anti-hook check passed (TracerPid + Frida端口 + maps注入)
- Memory guard enabled
- 5 个 JNI 导出: initWithBlob / wipeHeader / enableGuard / antiHook / recoverMethods
- Shell payload 加密+HMAC 保护

## 3. WB-AES 🔴 CRITICAL
- liblianyu_security.so .rodata 含 8.9MB WB-AES 表
- 字符串检测: "FATAL: Placeholder WB-AES tables detected in PRODUCTION_BUILD!"
- 占位符表可被逆向恢复 → 所有 WB-AES 加密数据可解密
- 修复: tools/setup_wb_key.py --generate

## 4. KMS 🔴 CRITICAL
- 日志: "Failed to register native method KmsProvider.nativeInit()V"
- 安全 SO 动态符号表无任何 KmsProvider JNI 导出
- KMS 模块完全不可用, SM4 加密回退到纯 Java
- 修复: 将 KmsProvider native 实现编译进安全 SO

## 5. VMP ⚠️
- code_items.bin 5.7MB 已打包
- Dex2C SO .text 仅 74 字节 (可能是 stub)
- 运行时无 VMP 日志输出

## 6. 零信任 ⚠️
- SecurityOrchestrator / SecurityGuard / NativeBridge 全部日志静默
- 无法验证运行时签名校验、root检测、完整性校验

## 7. 混淆
- DEX: R8 full + repackageclasses ✓
- SO: 无 RTTI 泄露 ✓
- SO: 无 OLLVM 控制流平坦化
- 未发现硬编码密钥/URL

## 修复优先级
1. 🔴 WB-AES 生成真实部署密钥
2. 🔴 KMS native 方法编译/注册
3. 🟡 SecurityGuard critical 日志走 System.err
4. 🟡 OLLVM 混淆启用
