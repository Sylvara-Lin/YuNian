# YuNian 检测覆盖度矩阵

> Android 版本 × 检测点 兼容性矩阵
> 更新: 2026-06

## 检测点覆盖

| # | 检测函数 | 检测方法 | ≤API30 (≤11) | API31-33 (12-13) | API34-35 (14-15) | API36+ (16+) |
|---|---|---|---|---|---|---|
| 1 | check_tracerpid | /proc/self/status TracerPid | ✅ | ✅ | ✅ | ✅ |
| 2 | check_frida_port | TCP端口 27042-27055 | ✅ | ✅ | ✅ | ✅ |
| 3 | check_frida_maps | /proc/self/maps | ✅ (maps) | ✅ (maps) | ✅ (dl_iterate_phdr) | ✅ (dl_iterate_phdr) |
| 4 | check_frida_threads | /proc/self/task/*/comm | ✅ | ✅ | ✅ | ✅ |
| 5 | check_xposed | maps + filesystem | ✅ (maps) | ✅ (maps) | ✅ (dl_iterate_phdr) | ✅ (dl_iterate_phdr) |
| 6 | check_magisk | maps + dl_iterate_phdr | ✅ (maps) | ✅ (maps) | ✅ (dl_iterate_phdr) | ✅ (dl_iterate_phdr) |
| 7 | check_kernel_modules | /proc/modules + kallsyms | ✅ | ✅ | ✅ | ✅ |
| 8 | check_substrate | /proc/self/maps | ✅ (maps) | ✅ (maps) | ✅ (dl_iterate_phdr) | ✅ (dl_iterate_phdr) |
| 9 | check_lsposed | filesystem + maps | ✅ | ✅ | ✅ | ✅ |
| 10 | check_hook_enhanced | dlopen对比 + 函数序言快照 | ✅ | ✅ | ✅ | ✅ |
| 11 | check_emulator | system_props + 传感器 | ✅ | ✅ | ✅ | ⚠️ (传感器API变化) |
| 12 | check_mitm | CA证书 + 代理端口 | ✅ | ✅ | ✅ | ✅ |
| 13 | check_root | su二进制 + SELinux | ✅ | ✅ | ✅ | ✅ |
| 14 | check_debug | Debug API + debug标志 | ✅ | ✅ | ✅ | ✅ |
| 15 | check_integrity_dex | CRC32 DEX校验 | ✅ | ✅ | ✅ | ✅ |
| 16 | check_integrity_so | CRC32 SO三元环 | ✅ | ✅ | ✅ | ✅ |
| 17 | check_integrity_resources | CRC32资源校验 | ✅ | ✅ | ✅ | ✅ |
| 18 | check_tee_attest | TEE KeyStore认证 | ✅ (API28+) | ✅ | ✅ | ✅ |
| 19 | check_wb_aes | WB-AES T-Box自检 | ✅ | ✅ | ✅ | ✅ |
| 20 | check_heartbeat | SO .text CRC32心跳 | ✅ | ✅ | ✅ | ✅ |

**覆盖率**: 20/20 检测点在所有 Android 版本上可用。
**退化点**: API36+ 模拟器检测可能受传感器API变更影响；API≤27 TEE不可用。

## 额外防护层

| 层 | 机制 | 覆盖 |
|---|---|---|
| VMP 字节码检测 | tracer/root/Frida/CRC32 在 VM 内执行 | 全版本 |
| JNI_OnLoad 抢占 | ptrace(PTRACE_TRACEME) + PR_SET_DUMPABLE | 全版本 |
| SecurityGuard.antiDebugInit | inotify内存监控 | API≤35 |

## 关键限制

- Android 14+: `/proc/self/maps` 被系统封锁 → 已迁移到 `dl_iterate_phdr`
- Android 16 (vivo): 晚期ptrace + inotify触发SIGABRT → SecurityGuard已gated
- vivo ROM: `Log.i/d` 被过滤 → 生产构建已全量静默
