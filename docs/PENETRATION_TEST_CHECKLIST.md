# YuNian 渗透测试清单

> 对应分支：`security/vmp-encryption` → `LianYu-feature-security-six-dimensions`
> 执行前提：APK 编译完成，安装到测试设备

---

## 静态测试（已执行）

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| S-1 | strings SO 扫描 | `strings liblianyu_security.so \| grep frida` | 零结果 | ✅ JNI=7, frida-server=0, TracerPid=0 |
| S-2 | JNI 导出符号 | `nm -D liblianyu_security.so \| grep Java_` | <10（仅JNI_OnLoad+shell） | ✅ 7个（从57降到7） |
| S-3 | DEX 类名搜索 | jadx 搜索 `com.yunian.ai.security` | 混淆后 <20 | ⚠️ 需壳APK |
| S-4 | APK 签名 | apksigner verify | v2+v3 ✅ | ✅ RSA 2048 |
| S-5 | DEX 压缩 | unzip -lv | STORED | ⚠️ 需壳APK |

## 动态测试（待设备上线）

| # | 测试项 | 操作 | 预期结果 | 状态 |
|---|--------|------|---------|------|
| 1.1 | Frida attach | `frida -U -l script.js com.yunian.ai` | ptrace + 端口检测 → BREACH | ⬜ |
| 1.2 | Frida spawn | `frida -U -f com.yunian.ai` | maps 检测 → BREACH | ⬜ |
| 4.1 | Magisk 检测 | 安装 Magisk + 隐藏 | su + mount → DETECT_MAGISK | ⬜ |
| 7.1 | APK 重打包 | 修改 DEX 后重新签名 | 签名 v1+v2+v3 → SIGNATURE_FAIL | ⬜ |
| 8.3 | chain.dat 回滚 | 替换旧版本 | 版本号不匹配 → AUDIT_CHAIN_BREACH | ⬜ |

## 验证命令（设备上线后执行）

```bash
# 安装
adb install -r app-release.apk

# 启动并监控
adb shell am start -n com.yunian.ai/.security.SActivity
adb logcat -v time | grep -iE "security|shell|guard|breach|frida"

# 进程存活验证
adb shell pidof com.yunian.ai

# 审计日志验证
adb shell run-as com.yunian.ai cat files/lianyu_audit/audit.log | tail -5
```
