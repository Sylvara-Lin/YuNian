# YuNian 安全架构重构任务文档

## 架构总览

```
APK壳层 (后做) ── 防拆解/签名绑定/DEX校验
─────────────────────────────────────────────
Kotlin层 ── 编排/审计/密钥供给
─────────────────────────────────────────────
桥层   ── Zero-Trust PDP/PEP + VMP + 检测聚合
─────────────────────────────────────────────
内存层 ── 白盒AES + SM国密 + 安全擦除 + 反dump
─────────────────────────────────────────────
CPU安全层 ── NEON密钥驻留 + 缓存操作 + 五级密钥
```

**核心理念**: 数据永不解密在RAM中 → 推入CPU缓存 → 微秒级生命周期 → 用完即焚

---

## Phase 0: CPU安全层 (地基)

### Task 0.1 五级密钥体系实现
**文件**: `kms-engine.cpp` / `kms-engine.h`
```
MK(白盒表内) → DK_ephemeral(NEON q0-q1, ~200μs) → SK(栈,单次) → BK(确定性派生) → AK(TEE签名)
```
- [ ] 新增 `kms_derive_dk_ephemeral()` — 每次操作新建DK，NEON加载后立即flush RAM
- [ ] 新增 `kms_derive_bk(const uint8_t metadata[16])` — BK=SM4_enc(DK[0:16], metadata)，**确定性派生**
- [ ] 新增 `kms_attest_dk()` — TEE/KeyStore AK签名。**无TEE → 拒绝认证，不降级到白盒签名**
- [ ] 新增 `kms_destroy_dk_ephemeral()` — NEON寄存器 `movi v0..v7,#0` + `dc civac` (clean+invalidate)
- [ ] 修改 `kms_derive_session_key()` — 用新DK+nonce派生，不再依赖全局g_dk
- [ ] 新增 `kms_get_dk_footprint_ms()` — 返回DK在RAM中的存活时间
- [ ] 删除全局 `g_dk` 变量 — DK不再驻留内存
- [ ] 单测: 千次操作后内存dump验证无密钥残留

### Task 0.2 NEON寄存器强化
**文件**: `kms-engine.cpp`
- [ ] `neon_load_key()` 加载后立即 `dc civac` (非cvac！) 清除+invalidate RAM副本
- [ ] `neon_wipe_all_keys()` — 用 `movi` 直接覆盖q0-q7寄存器(不写回内存)，无需cache操作
- [ ] `neon_store_key()` 用后立即wipe栈缓冲 + `dc civac` 确保cache也不残留
- [ ] 添加乱序对抗: 假密钥加载q4-q7填充垃圾数据
- [ ] `dsb sy` 屏障每次操作前后各一次
- [ ] 单测: 反汇编验证 `ldp q0,q1` 后无 `str q0` 泄漏

### Task 0.3 BK盲化层 (确定性派生)
**文件**: `kms-engine.cpp` (新增函数)
- [ ] `kms_blind_input(buf, metadata)` — BK=SM4(DK[:16], metadata)，异或盲化
- [ ] `kms_unblind_output(buf, metadata)` — 同样方法派生BK′，异或反盲化
- [ ] metadata=salt\|counter 随密文输出（前16字节元数据），使解密侧可同步
- [ ] BK用后 `secure_wipe` + q2覆盖，不依赖RNG指令
- [ ] 单测: 同salt+counter→同BK；不同salt→不同BK；加解密往返验证

### Task 0.4 白盒AES三段混淆 (应对R1: BGE攻击)
**文件**: `whitebox-aes.cpp` / `whitebox-aes.h`
```
L1 基础表: CI每个发布版本自动生成新wb_tables.inc
L2 启动混淆: 每次onCreate对基础表异或随机mask（/dev/urandom源）
L3 操作模糊: 每轮pos遍历顺序Fisher-Yates shuffle + 4次假T-Box访问
```
- [ ] 新增 `wb_aes_obfuscate_tables(const uint8_t seed[32])` — L2启动混淆
- [ ] 每轮pos遍历顺序改为随机排列（通过shuffle表实现）
- [ ] 每轮额外4次无效表查询（功耗/时序噪声）
- [ ] checksum盐值每次不同（L3贡献）
- [ ] 单测: 混淆前后 encrypt→decrypt 往返一致性
- [ ] 安全论证: BGE攻击窗口从"一次静态提取"→"必须在同进程内完成静态+动态分析且反调试在线"

---

## Phase 1: 内存层

### Task 1.1 白盒AES加固
**文件**: `whitebox-aes.cpp` / `whitebox-aes.h`
- [ ] 新增 `wb_aes_encrypt_blinded()` — 内部调用BK盲化
- [ ] 新增 `wb_aes_side_channel_defense()` — 固定时间表查询(禁用分支预测提示)
- [ ] DFA checksum改为16轮独立校验(非最后一轮累积)
- [ ] 表访问模式随机化 — 每轮pos遍历顺序打乱
- [ ] `wb_aes_wipe_keys()` 增加NEON循环展开填充
- [ ] 单测: 验证256种输入+密钥组合下无时间侧信道

### Task 1.2 SM国密集成
**文件**: `sm-cipher.cpp` / `sm-cipher.h`
- [ ] SM9实现 — BN曲线配对(HLS依赖或纯净C实现)
- [ ] SM2签名/验签实现 — 从stub到完整(基于sm2_curve_t参数)
- [ ] SM4-GCM模式 — 带认证加密替代CBC
- [ ] SM3-HMAC — 用于密钥派生确认
- [ ] 单测: GM/T 0002-2012 测试向量

### Task 1.3 内存卫士
**文件**: 新建 `memory-guard.cpp` / `memory-guard.h`
- [ ] `mg_setup_guard_pages()` — mmap匿名保护页夹击敏感区
- [ ] `mg_check_stack_canary()` — 栈金丝雀校验
- [ ] `mg_anti_read()` — mprotect敏感页为PROT_NONE
- [ ] `mg_proc_maps_poll()` — /proc/self/maps异常段检测
- [ ] `mg_ptrace_self_attach()` — 抢先ptrace占坑反调试
- [ ] 单测: SIGSEGV触发验证保护页生效

---

## Phase 2: 桥层 (跟内存层联调)

### Task 2.1 环境检测扩展 (9→32)
**文件**: `native-bridge.cpp` — 全量重写

| # | 函数 | 检测内容 | 权重 |
|---|------|---------|------|
| 1 | `check_su_binaries()` | su/magisk/ksu等17个路径 | 3 |
| 2 | `check_root_props()` | ro.debuggable/ro.secure/SELinux宽容 | 3 |
| 3 | `check_magisk_process()` | /proc扫描Magisk×5+进程名 | 3 |
| 4 | `check_zygisk()` | Zygisk socket + module注入路径 | 3 |
| 5 | `check_xposed_class()` | ClassLoader查Xposed/LSPosed/Riru类 | 3 |
| 6 | `check_xposed_files()` | /data/data下Xposed/EdXposed/TaiChi/Dreamland | 3 |
| 7 | `check_frida_ports()` | 27042+命名管道+D-Bus | 3 |
| 8 | `check_frida_maps()` | gum-js/frida-agent/fridainject | 3 |
| 9 | `check_substrate()` | Cydia Substrate so库 | 2 |
| 10 | `check_debug_self_ptrace()` | ptrace(PTRACE_TRACEME)自附加 | 2 |
| 11 | `check_debug_status()` | /proc/self/status TracerPid+flags | 2 |
| 12 | `check_debug_inotify()` | inotify监控/proc/self/mem | 2 |
| 13 | `check_emulator_qemu()` | QEMU管道/goldfish电池/传感器 | 2 |
| 14 | `check_emulator_props()` | ro.kernel.qemu/ro.hardware/serial | 2 |
| 15 | `check_virtual_env()` | VirtualApp/ParallelSpace/Sandbox特征 | 2 |
| 16 | `check_mitm_certs()` | 用户CA证书+系统证书异常 | 2 |
| 17 | `check_mitm_proxy()` | HTTP_PROXY属性+端口8080/8888/9090 | 2 |
| 18 | `check_mitm_vpn()` | /dev/tun存在+TUN设备遍历 | 2 |
| 19 | `check_mitm_dns()` | /etc/hosts劫持/DNS over HTTPS异常 | 1 |
| 20 | `check_hook_plt()` | .got.plt表校验CRC32 | 3 |
| 21 | `check_hook_inline()` | .text段首字节校验 | 3 |
| 22 | `check_inject_ldpreload()` | LD_PRELOAD变量检查 | 2 |
| 23 | `check_inject_dlopen()` | dlopen劫持检测(syscall过滤) | 2 |
| 24 | `check_signature()` | APK签名v1+v2+v3验证 | 3 |
| 25 | `check_dex_integrity()` | classes.dex CRC32 | 3 |
| 26 | `check_so_integrity()` | liblianyu_security.so CRC32 | 3 |
| 27 | `check_bootloader()` | ro.boot.verifiedbootstate/ro.boot.flash.locked | 2 |
| 28 | `check_screen_capture()` | FLAG_SECURE生效验证+录屏检测 | 1 |
| 29 | `check_accessibility()` | 辅助功能滥用检测(连点器/按键精灵) | 1 |
| 30 | `check_clipboard()` | 剪贴板监控进程扫描 | 1 |
| 31 | `check_clock_skew()` | CLOCK_MONOTONIC vs SystemClock偏移 | 2 |
| 32 | `check_selinux()` | getenforce状态+策略异常 | 2 |

**总权重**: 0-72 (阈值: TRUST=0, SUSPICIOUS=1-6, BREACH=7+)

**检测灰度策略**: 首批上10项核心检测(Root/Frida/Xposed/Signature) → 观察FP率 → 逐步开启其余22项

**Android 14+ 兼容**: `ptrace(PTRACE_TRACEME)` 在Android 14被限制，改用 `prctl(PR_SET_DUMPABLE,0)` + `/proc/self/status` TracerPid监控双重防护

### Task 2.2 Zero-Trust评分扩展
**文件**: `zero-trust.cpp`
- [ ] 评分矩阵从10项→32项
- [ ] 权重表从`#define`→`const uint8_t ZT_WEIGHTS[32]`运行时表
- [ ] BREACH状态新增冷却期(300s内不可降级)
- [ ] 新增 `zero_trust_get_detection_detail(int id)` — 单点查询
- [ ] PEP新增操作类型 `ZT_OP_CRYPTO` 用于密码操作前置检查
- [ ] 单测: 注入模拟→检测状态迁移→验证锁死

### Task 2.3 VMP引擎强化
**文件**: `vm-engine.cpp` / `vm-bytecode.cpp`
- [ ] 新增指令: OP_NOT/OP_NEG/OP_MUL/OP_DIV — 完善算术
- [ ] 新增指令: OP_MEMCPY/OP_MEMSET — 安全内存操作
- [ ] 新增指令: OP_SM3HASH/OP_HMAC — 密码学原语
- [ ] 新增hypercall: HYPER_GET_PROP/HYPER_CHECK_CLASS — Android调用
- [ ] VM寄存器文件用NEON q8-q15存储(非struct->regs[]内存)
- [ ] 单测: 在VM内完成完整AES解密

---

## Phase 3: Kotlin层

### Task 3.1 统一安全入口
**文件**: 新建 `SecurityOrchestrator.kt`
- [ ] `fun encrypt(data: ByteArray): EncryptionResult` — 单入口
- [ ] 自动调用检测链 → KMS派生 → 盲化 → 加密 → 审计
- [ ] BREACH状态下返回零长度+审计记录
- [ ] 线程安全: `@Synchronized` + 自旋锁

### Task 3.2 审计日志增强 — 三层存储 (应对R7: 链完整性)
**文件**: `AuditLogger.kt` — 扩展

**三层审计存储**:
```
L1: 链头哈希
  ├ 有TEE: 存 KeyStore (RSA/EC密钥包裹)
  └ 无TEE: 存 SQLCipher加密表 (密钥由KMS派生 → 只有本app能读)

L2: 审计条目 (环形)
  └ SQLite表 audit_log (sqlcipher加密)
  └ 每行: {seq, timestamp, event_code, data_hash, prev_hash, signature}
  └ 环形: MAX 100,000条，超出覆盖最旧

L3: 完整性验证
  └ 启动时: 读链头→遍历全链→重新计算每个prev_hash
  └ 不一致→触发ZT_BREACH
  └ 审计链被篡改本身 = 安全事件
```
- [ ] 新增事件: `DK_DERIVED/SK_DERIVED/BK_GENERATED/KEY_DESTROYED`
- [ ] 新增事件: `DETECT_ROOT/DETECT_FRIDA/DETECT_XPOSED/BREACH_ESCALATED`
- [ ] 审计链: 每条记录含前条Hash(SM3链式)
- [ ] 启动验证: `verifyAuditChain()` 全链校验

### Task 3.3 KeyStore集成 (修正R5: 砍掉白盒签名降级)
**文件**: 新建 `HardwareKeyAttestor.kt`
- [ ] `fun attestDk(dkHash: ByteArray): ByteArray?` — AK签名，失败返回null
- [ ] StrongBox → KeyStore → **拒绝**(不降级到白盒签名)
- [ ] `fun isTeeAvailable(): Boolean`
- [ ] AK为可选项: 无TEE → 本地加密仍可用，远程认证不可用
- [ ] 证书链验证(Google Hardware Attestation Root)

---

## Phase 4: 集成测试(等编译环境恢复)

### Task 4.1 单元测试 (C++侧)
10个测试文件 (`R1-R10`)，已创建框架于 `core/security/src/test/`

### Task 4.2 集成测试 (Android侧)
- [ ] 安全启动流程: Application.onCreate → KMS init → ZT init → Attestation
- [ ] 加密往返: 1000次随机数据encrypt→decrypt，0字节泄漏
- [ ] 压力测试: 并发100线程加密，DK不冲突
- [ ] 故障注入: 修改.so CRC→触发BREACH→验证锁死

### Task 4.3 渗透测试清单
- [ ] Frida attach尝试→应被ptrace自附加+端口检测拦截
- [ ] Xposed hook SecurityGuard→应触发类检测BREACH
- [ ] MITM证书安装→应触发CA证书扫描
- [ ] 内存dump→应返回空数据(密钥在NEON不在RAM)
- [ ] /proc/self/mem读取→保护页mprotect阻止

---

## 执行顺序

```
Phase 0 (CPU安全层)
  Task 0.1 → Task 0.2 → Task 0.3
          ↓
Phase 1 (内存层)
  Task 1.1 → Task 1.2 → Task 1.3
          ↓
Phase 2 (桥层) ← 与 Phase 1 交叉调试
  Task 2.1 → Task 2.2 → Task 2.3
          ↓
Phase 3 (Kotlin层)
  Task 3.1 → Task 3.2 → Task 3.3
          ↓
Phase 4 (集成测试)
  Task 4.1 → Task 4.2 → Task 4.3
```

---

## 文件变更总览

| 文件 | 操作 | 行数估算 |
|------|------|---------|
| `kms-engine.cpp` | 重写核心 | +200行 |
| `kms-engine.h` | 新增接口 | +60行 |
| `whitebox-aes.cpp` | 加固 | +100行 |
| `whitebox-aes.h` | 新增接口 | +30行 |
| `sm-cipher.cpp` | SM9+SM2+SM4-GCM | +400行 |
| `memory-guard.cpp/h` | **新建** | +300行 |
| `native-bridge.cpp` | 全量重写 | +800行 |
| `zero-trust.cpp` | 评分扩展 | +200行 |
| `vm-engine.cpp` | 指令+hypercall | +200行 |
| `SecurityOrchestrator.kt` | **新建** | +150行 |
| `HardwareKeyAttestor.kt` | **新建** | +100行 |
| `AuditLogger.kt` | 扩展 | +100行 |
| `EnvironmentDetector.kt` | **新建** | +200行 |
| `test/*` | 已完成框架 | +500行 |

**总计**: 约3,400行代码 + 测试

---

## 规则

1. **每完成一个Task → 立即验证** (编译或逻辑审阅)
2. **Phase 0-1修改时必须跳过Phase 2的文件** (最小改动原则)
3. **所有C++线程安全: 原子操作，零锁**
4. **所有密钥函数返回后: 无残留**
5. **Kotlin侧不持有密钥引用长于一次JNI调用**

---

## 附录A: 技术决策与修正记录

### A.1 SK性能分层策略 (R3修正)

针对"每次加密都重建SK开销过大"问题，SK按使用场景分4级:

| 场景 | 密钥类型 | 派生频率 | 寿命 | 性能 |
|------|---------|---------|------|------|
| 数据库每行加密 | **SK_bulk** | 一次/启动 | 整session | 0开销 |
| 网络TLS会话 | **SK_session** | 一次/连接 | 单连接 | ~0.6ms |
| 聊天消息加密 | **SK_msg** | 每100条 | 100条消息 | 0.006ms/条 |
| 密钥包裹/根操作 | **SK_root** | **每次操作** | 即用即焚 | ~0.6ms/次 |

```
wb_aes_encrypt:  ~0.5ms (Cortex-A76)
SM3-KBKDF:       ~0.1ms
─────────────────────────
合计:            ~0.6ms/次

消息加密: 0.6ms/100条 = 6μs/条 ✅
数据库写入: 0.6ms/启动 ✅
仅极密根操作才用SK_root即用即焚
```

### A.2 BK确定性派生协议 (R4修正)

BK≠纯随机 → 改为确定性派生，使加解密两侧可同步:

```
metadata = salt(8B) || counter(8B)  ← 随密文输出(前16B元数据)

加密侧:                             解密侧:
BK = SM4_enc(DK[:16], metadata)     BK = SM4_enc(DK[:16], metadata)
plain XOR BK                        (metadata从密文头部提取)
  → wb_aes_encrypt                  (同一DK保证BK相同)
  → cipher XOR BK'                   → wb_aes_decrypt
(BK'=SM4_enc(BK, DK[16:32]))         → plain XOR BK'
                                       → plain ✓

关键: DK在NEON中一致 → BK确定性相同
     salt每次随机(启动时生成) → 不同启动周期BK不同
     counter单调递增 → 同一周期内每次操作BK不同
```

### A.3 RNG回退策略 (R6修正)

`mrs x0, RNDR` 不适用于普通APK ≠ BK盲化方案失效:

- BK已改为SM4确定性派生(R4)，**不依赖RNG指令**
- 初始salt来源: `getrandom()` syscall (Linux内核随机池)
- 编译期检测: `#if defined(__aarch64__) && __ARM_ARCH >= 8`
- 运行时检测: 读 `ID_AA64ISAR0_EL1` 寄存器bit 60-63判断RNDR存在
- 即使RNDR可用，也不用于密钥派生——仅用于salt生成

### A.4 风险修正对照表

| 原风险 | 严重度 | 修正方案 | 状态 |
|--------|--------|---------|------|
| R1: BGE提取白盒密钥 | 🔴致命 | 三段混淆(L1版本表+L2启动mask+L3操作随机化) | ✅ |
| R2: NEON清除不可靠 | 🔴致命 | dc civac替代cvac + movi寄存器覆盖 | ✅ |
| R3: SK派生性能 | 🟡严重 | 四级分层，高频用SK_bulk/SK_msg | ✅ |
| R4: BK反盲化缺陷 | 🟡严重 | 确定性SM4派生 + metadata前导 | ✅ |
| R5: 白盒签名无意义 | 🟡严重 | 砍掉降级链，无TEE拒绝认证 | ✅ |
| R6: ARMv8 RNG崩溃 | 🟡严重 | BK不依赖RNG + getrandom回退 | ✅ |
| R7: 审计链被篡改 | 🟡严重 | 三层存储 + 启动全链校验 | ✅ |
| R8: 白盒侧信道 | 🟡严重 | 表随机化+假访问+固定时间 | ✅ |
| R9: 审计链胀满磁盘 | 🟢一般 | 环形10万条上限 | ✅ |
| R10: VMP性能差20× | 🟢一般 | VM仅保护关键代码，不做数据加密 | ✅ |
| R11: Linux无法编译NEON | 🟢一般 | Phase 0写完跳过编译，等ARM环境 | ✅ |
| R12: 反Xposed单点 | 🟢一般 | 三维检测(ClassLoader+文件+进程) | ✅ |

### A.5 APK壳层启动修复 (2026-06-10)

**问题**: `AndroidManifest.xml` 注册 `com.stub.StubApp` 作为 Application 入口，
但 `StubApp.kt` 位于 `:shell` 模块（JVM 插件，不参与 Android 构建），
导致 `ClassNotFoundException: com.stub.StubApp`，应用无法启动。

**根因**: `:shell` 模块使用 `kotlin("jvm")` 插件，编译产物未纳入 APK。
`StubApp` 中的 `interface13/14/15` external 方法声明了 `liblianyu_shell.so`
的 JNI 接口，但 native 层仅有 `StaticApkShell` 的实现，未注册 StubApp 的方法。

**修正方案**:
1. `StubApp.kt` 从 `:shell` 移至 `app/src/main/java/com/stub/`
2. 移除未实现的 `external fun interface13/14/15`
3. 改为透传代理 — 反射调用 `YuNianApplication.attach()` 并转发生命周期事件
4. 删除 `Bridge.kt`（仅含 external 声明，无实现）
5. `proguard-rules.pro` 添加 `-keep class com.stub.StubApp`

**验证**: V2324A (Android 16) 设备安装启动成功，`YuNianApplication.onCreate()` 正常执行，
Locale zh-CN 初始化正常，无 `UnsatisfiedLinkError`。

**后续**: native 层壳功能（DEX 解密、签名校验、反调试）由 `StaticApkShell` +
`liblianyu_shell.so` 的 `nativeShellInitWithBlob` / `nativeAntiHookInit` 提供。
StubApp 当前为轻量代理，若需完整 360 式加壳，需在 `dex-extractor.cpp` 的
`JNI_OnLoad` 中补充 `RegisterNatives` 注册 StubApp 的 external 方法。
