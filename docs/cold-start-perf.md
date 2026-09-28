# 予念 YuNian · App 冷启动性能诊断与打点测量方案

> 角色：软件架构师（诊断与设计，**不含代码改动**）
> 范围：`onCreate → 首帧` 约 1.5s 区间的**可实施打点方案** + 阻塞候选评估 + 优化方向 + 任务分解
> 诚实声明：凡无实测支撑的推断，均显式标注 **【待验证】**；已由真机 A/B 确证的事实标注 **【已确证】**。

---

## 0. 结论速览

| 项 | 结论 |
|----|------|
| 聊天页卡顿 | **【已确证】非问题**：进入聊天页 120 帧全部 <20ms，零 Choreographer 警告 |
| 真实卡顿点 | **【已确证】App 冷启动**：`Skipped 57 frames!` @120Hz ≈ **475ms** 主线程阻塞 |
| 阻塞区间 | **【已确证】`Application.onCreate` → 首帧之间**；加固壳仅占 221ms（`dexLoad` 192ms） |
| 已排除 | 气泡玻璃 / 转场位移 / 布局 traversal / 消息内容 / JIT-AOT（业务 DEX 加密，系统无法预编译） |
| **首要嫌疑（高度可疑，待验证）** | **多个独立 SharedPreferences 文件的首次同步 XML 加载风暴**，全部落在主线程 |
| 次要嫌疑（待验证） | WorkManager 初始化（on-demand init + WorkManager 专属 Room DB 打开）；`MainActivity.onCreate` 中 setContent **之后、首帧之前**的保活/更新调度链 |
| 观测盲区（必须补） | `PerformanceTrace.startupNanos()` 目前从 **MainActivity.onCreate** 起算，**完全不含 Application 侧 initBusiness 耗时** |

---

## 1. 打点测量方案（最高优先级，可直接实施）

### 1.1 设计原则

1. **纯内存、无锁、无分配**：启动路径打点只能用 `AtomicLong` CAS/set（沿用 `PerformanceTrace` 既有约定，`core/common/.../PerformanceTrace.kt`）。
2. **release 可读**：release 包 `Log.d/Log.v` 会被 R8 剥离，且 `SecureLog` 仅在 `isDebug` 时输出 —— **启动测量一律用固定 tag 的 `android.util.Log.i`**。
3. **统一时基**：所有阶段使用 `SystemClock.elapsedRealtimeNanos()`（含深睡，单调，跨进程阶段可比）。
4. **一次 dump**：首帧后统一打印一行多段结果，便于 `adb logcat` 一次 grep。
5. **不改业务行为**：打点只读时间，不改变调用顺序。

### 1.2 新增 API（扩展 `PerformanceTrace`，`core/common/src/main/java/com/yunian/ai/common/PerformanceTrace.kt`）

在既有 object 内**追加**以下成员（不改动现有字段/方法）：

```kotlin
// ===== 冷启动分段打点（新增；纯 AtomicLong/轻量 Map，仅启动期调用）=====
private const val STARTUP_TAG = "YuNianPerf"
private val launchStarted = AtomicLong(0L)
private val startupStages = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val startupOrder = java.util.Collections.synchronizedList(ArrayList<String>(24))
private val startupDumped = AtomicBoolean(false)

/** 进程启动最早可达点（YuNianApplication.onCreate 首行）调用一次。 */
fun startLaunch(startedNanos: Long = SystemClock.elapsedRealtimeNanos()) {
    launchStarted.compareAndSet(0L, startedNanos)
}

/** 记录一个具名启动阶段；幂等（同名只记首次）。仅启动期调用 ~15 次，分配可忽略。 */
fun markStartupStage(name: String) {
    val now = SystemClock.elapsedRealtimeNanos()
    if (startupStages.putIfAbsent(name, now) == null) {
        startupOrder.add(name)
    }
}

/** 首帧后调用一次：把全部阶段（相对 launchStarted 的 ms）打到 logcat，release 亦可读。 */
fun dumpStartupStages() {
    val base = launchStarted.get()
    if (base == 0L || !startupDumped.compareAndSet(false, true)) return
    val sb = StringBuilder("cold-start stages (ms since launch):")
    for (name in startupOrder.toList()) {
        val n = startupStages[name] ?: continue
        sb.append('\n').append("  ").append(name).append('=').append((n - base) / 1_000_000)
    }
    val drawn = startupDrawn.get()
    if (drawn > 0L) sb.append('\n').append("  first_frame=").append((drawn - base) / 1_000_000)
    android.util.Log.i(STARTUP_TAG, sb.toString())
}

fun startupStagesNanos(): Map<String, Long> =
    startupOrder.toList().associateWith { startupStages[it] ?: 0L }
```

> 注意：`dumpStartupStages()` **不要**放进 `drawWithContent`/组合循环体（含分配），只在首帧回调里调一次即可。`markStartupStage` 只在启动同步路径调用，允许极少量分配。

### 1.3 精确打点位置（文件:行 + 测什么）

约定：`markStartupStage("...")` 简写为 `mark("...")`。

#### A. `app/src/main/java/com/yunian/ai/YuNianApplication.kt`

| 位置(行) | 打点 | 测什么 |
|---------|------|--------|
| L108 `attachBaseContext` 首行 | `startLaunch(); mark("app_attach_begin")` | 进程最早可达点（壳 preflight 之后） |
| L112 `onCreate` 首行 | `mark("app_oncreate_begin")` | Application.onCreate 入口 |
| L114-117 4× `Security/System.setProperty` 之后 | `mark("oncreate_props_done")` | 4 条属性设置耗时 |
| L118 `super.onCreate()` 之后 | `mark("oncreate_super_done")` | `super.onCreate()` 耗时 |
| L121 `AppForegroundTracker.init()` 之后 | `mark("app_fgt_init_done")` | ProcessLifecycleOwner 初始化 |
| 进入 L173 `initBusiness` | `mark("initbusiness_begin")` | —— |
| L174 `SaltStore.init(app)` 后 | `mark("ib_salt")` | 仅存 context（预期 ~0） |
| L175 `SecureLog.init(...)` 后 | `mark("ib_securelog")` | 仅置 bool（预期 ~0） |
| L176 `applyStoredLanguage(app)` 后 | `mark("ib_language")` | **SP `language_prefs` 首载** |
| L177 `AiService.initialize(app)` 后 | `mark("ib_aiservice")` | 仅存 context（预期 ~0） |
| L178 `NtpTimeProvider.initialize(app)` 后 | `mark("ib_ntp")` | 仅 launch 协程（预期 ~0） |
| L179 `clearUpdateIgnore(app)` 后 | `mark("ib_clear_update")` | **SP `update_config` 首载 + apply** |
| L181 `ApplicationScopeProvider.init(bgScope)` 后 | `mark("ib_scopeprovider")` | 仅置字段（预期 ~0） |
| L182（同步段结束，进入 bgScope 段） | `mark("initbusiness_sync_done")` | **同步段总计** |
| L230 `ContentFilter.setSafetyClassifier(...)` 后 | `mark("ib_safety_classifier")` | Lazy 分类器外壳（预期 ~0） |
| L233 `DataCleanupManager.schedulePeriodicCleanup(app)` 后 | `mark("ib_cleanup_schedule")` | **WorkManager.getInstance + enqueue** |
| L235 `initBusiness` 末尾 | `mark("initbusiness_done")` | initBusiness 总耗时 |
| L218 `ServiceRegistry.markInitialized()` 前 | `mark("service_registry_ready")` | 后台服务就绪（与首帧相关性） |

#### B. `app/src/main/java/com/yunian/ai/MainActivity.kt`

| 位置(行) | 打点 | 测什么 |
|---------|------|--------|
| L74 `onCreate` 首行（L76 已有 `startStartup`） | `mark("act_oncreate_begin")` | Activity.onCreate 入口 |
| L77 `super.onCreate` 后 | `mark("act_super_done")` | —— |
| L79 `WorkManager.initialize(...)` 后 | `mark("act_workmanager_init")` | **WorkManager 显式初始化** |
| L81 `decorView.post{ applySystemBars }` 注册后 | `mark("act_systembar_posted")` | 仅 post（非阻塞） |
| L107 `WindowMainBackground.applyFromPrefs(this)` 后 | `mark("act_window_bg_prefs")` | **背景 SP 首载** |
| L110 `setContent{...}` 调用返回后（L176 之后） | `mark("act_setcontent_return")` | setContent 同步开销（不含组合） |
| L178-187 之后（setContent 后置链全部执行完） | `mark("act_oncreate_end")` | **保活/更新调度链耗时（首帧前执行！）** |
| 组合体内 L111 `agreementPrefs` 读取后 | `mark("compose_agreement_prefs")` | **SP `agreement_prefs` 首载（组合期主线程）** |
| 组合体内 L113 `userPrefs` 读取后 | `mark("compose_user_prefs")` | **SP `user_prefs` 首载（组合期主线程）** |
| L120 `isServiceReady` 首次为 true 时 | `mark("compose_service_ready")` | 服务就绪到 UI 的耦合点 |
| L162 `else -> MainScreen(activity)` 首次进入 | `mark("compose_mainscreen_enter")` | 首页真正开始组合 |
| L186 `updateManager.checkForUpdates()` 前 | `mark("act_update_check")` | 更新检查（含 SP `update_config` 复用） |

#### C. 首帧与全绘制

| 文件:行 | 打点 | 测什么 |
|--------|------|--------|
| `MainScreen.kt` L195-198 `drawWithContent{}` | 保留 `markStartupDrawn()`；**追加** `PerformanceTrace.markStartupStage("first_frame")` 与 **一次性** `PerformanceTrace.dumpStartupStages()` | 首帧 & 统一输出 |
| `MainScreen.kt` 组合体首行 | `mark("home_first_compose")`（用 `remember{mark(...);true}` 保证幂等，勿放 draw） | 首页首次组合 |
| `MainActivity.kt` onCreate 末尾 | `decorView.post { ActivityCompat.reportFullyDrawn(this) }`（`androidx.core.app`） | 系统 FullyDrawn 口径，可与 Trace 交叉校验 |

> **关键修正**：`PerformanceTrace.startupNanos()` 现在只覆盖 `MainActivity.onCreate → 首帧`；`launchStarted → first_frame` 才是完整冷启动口径。二者都要采集。

### 1.4 如何读取测量结果

```bash
# 1) 统一分段结果（固定 tag YuNianPerf，release 可靠）
adb logcat -c
adb logcat -s YuNianPerf:I | grep -A 30 "cold-start stages"

# 2) 系统口径（进程创建→首帧）
adb shell am start -W -n com.yunian.ai/.MainActivity    # 看 TotalTime / WaitTime

# 3) 主线程跳帧
adb logcat -s Choreographer:I

# 4) 交叉：应用内 StartupState / ServiceRegistry.initialized
adb logcat -s YuNianApplication:I ServiceRegistry:I
```

采集规范：**冷启动**（先 `adb shell am force-stop com.yunian.ai`，且**勿**用 `am start` 前预热），**每配置 ≥5 轮取中位数**；120Hz 设备保持刷新率默认。记录「桌面冷启」与「通知/深链冷启」两类。

---

## 2. 主线程阻塞候选评估（逐个已读实现）

| 调用（文件:行） | 实现事实 | 磁盘 IO / SP 首载 / WM / 网络 / 密钥 / DB | 判定 |
|---|---|---|---|
| `SaltStore.init` (`SaltStore.kt:19-22`) | 仅 `context = appContext`；`getSharedPreferences` 在 `getSalt()` 内**懒加载**，启动不触发 | 无 | **确定无阻塞** |
| `SecureLog.init` (`SecureLog.kt:11-13`) | 仅置 `isDebug` | 无 | **确定无阻塞** |
| `applyStoredLanguage` (`YuNianApplication.kt:582-594`) | `getSharedPreferences("language_prefs").getString` → **该 SP 首次同步 XML 加载**；随后 `Locale.setDefault` | **SP 首载（磁盘）** | **确定有 IO**；耗时【待验证】 |
| `AiService.initialize` (`AiService.kt:384-386`) | companion 仅置 `context`。注意：`AiService(app)` **实例**（L447）在 bgScope 构造，`init{}` 里 `AppDatabase.getDatabase` / `DeviceIdProvider` / `memoryProvider.initialize()` | 无（companion） | **确定无阻塞**（重活在后台） |
| `NtpTimeProvider.initialize` (`NtpTimeProvider.kt:39-41`) | `syncInBackground()` → 自有 IO scope `launch{performSync()}` | 网络在后台 | **确定非阻塞** |
| `clearUpdateIgnore` (`YuNianApplication.kt:549-552`) | `getSharedPreferences("update_config").edit().remove(..).apply()` → **首载同步** + 异步 commit | **SP 首载（磁盘）** | **确定有 IO**；耗时【待验证】 |
| `ApplicationScopeProvider.init` (`ApplicationScopeProvider.kt:17-19`) | 仅置字段 | 无 | **确定无阻塞** |
| `ContentFilter.setSafetyClassifier` (`ContentFilter.kt:310-313`) | 仅置字段 + `Log.i`；`LazyLocalSafetyClassifier` 构造只存 appContext，分类器真正构建在首调时 | 无 | **确定无阻塞** |
| `DataCleanupManager.schedulePeriodicCleanup` (`DataCleanupManager.kt:25-43`) | `WorkManager.getInstance(context).enqueueUniquePeriodicWork(...)` → 触发 **WorkManager 初始化**（可含 WorkManager 专属 Room DB 打开/写入） | **WM / DB（磁盘）** | **确定有 IO**；耗时【待验证】 |
| `AppForegroundTracker.init` (`AppForegroundTracker.kt:15-29`) | `ProcessLifecycleOwner.get().lifecycle` 注册观察者 | 无磁盘 IO（内存） | **确定非阻塞**（预期小） |

### 追加嫌疑（不在原同步清单，但同在「onCreate→首帧」主线程路径，务必打点）

| 位置 | 事实 | 判定 |
|---|---|---|
| `MainActivity.kt:71` `SystemBarController.applyBaseContextLocale`（attachBaseContext） | 读 **SP `theme_prefs`**（`SystemBarController.kt:17`）→ 首载 | **确定有 IO**，位于 attach 早期 |
| `MainActivity.kt:107` `WindowMainBackground.applyFromPrefs` | 主线程读背景 SP（首载） | **确定有 IO** |
| `MainActivity.kt:111/113` 组合期读 `agreement_prefs` / `user_prefs` | **两个独立 SP 首载，落在首帧前组合期主线程** | **确定有 IO** |
| `MainActivity.kt:179-187` setContent **之后** | `CompanionKeepAliveService.start` / `CompanionMessageWorker.schedule` / `scheduleIqooKeepAliveJob`（JobScheduler）/ `KeepAliveAlarmScheduler.scheduleNext`（AlarmManager）/ `updateManager.checkForUpdates()` —— **全部在首帧 doFrame 之前同步执行** | **可能含 WM/Alarm/Job/网络磁盘 IO**，【待验证】 |
| `MainActivity.kt:79` `WorkManager.initialize` | 与 `Configuration.Provider`（`YuNianApplication:103-106`）叠加；WorkManager 亦可能由 `InitializationProvider` 在 **ContentProvider 阶段（attach 后 onCreate 前）** 初始化 | 【待验证】是否落在 221→237ms 那段 |
| `YuNianApplication.kt:538-545` `runBlocking{ AutomationStore.list() + rescheduleAll }` | 在 **bgScope(IO)**，不阻塞主线程；但其 WorkManager enqueue 可能与主线程的 WM 初始化**争 WorkManager DB 锁** | **不阻塞主线程**；争锁【待验证】 |

> **综合判断（待验证）**：启动路径上至少触碰 **`theme_prefs`、`language_prefs`、`update_config`、`agreement_prefs`、`user_prefs`、背景 SP（+ `app_settings`）** 等 **6~7 个独立 SharedPreferences 文件**，每个首次访问都是一次**主线程同步 XML 解析 + 磁盘读**。这与其 475ms 量级高度吻合，是**最优先验证项**。次优为 WorkManager 初始化与 setContent 后置调度链。

---

## 3. 优化方案（基于评估，分项标注做法/收益/风险/时序依赖）

> 排序按「预估收益 / 风险」从高到低。**任何涉及加固壳（192ms dexLoad）的弱化建议单列 §4，需用户决策。**

### O1. 消除 SharedPreferences 首载风暴【首选，预期收益最大】
- **做法**：
  1. 在 `Application.onCreate` **最顶部**（`launchStarted` 之后立即）用 `bgScope.launch` 预热所有已知 SP 文件：`getSharedPreferences(name,0).all`（仅触发加载，不改数据）。
  2. 合并语义相近的 SP 文件（如背景相关并入 `app_settings`），减少文件数。
- **收益**：把 N 次主线程同步 XML 加载挪到 IO 线程；预期**削减 100~300ms【待验证】**。
- **风险 / 时序依赖**：
  - ⚠️ 后台预热**不保证**先于主线程访问完成（竞态）。若主线程先到，仍会同步加载，只是后台那次命中缓存 —— **收益不稳定【待验证】**，需实测。
  - 合并 SP 文件涉及迁移逻辑，**不得破坏 `update_config` 被 `AppUpdateManager` 读取的时序**。
  - **红线**：不得改变 `agreement_prefs`/`user_prefs` 的读取语义（决定首屏是 Agrean/选角 还是主界面）。

### O2. `clearUpdateIgnore` 移入后台 / 并入 `AppUpdateManager`【低风险】
- **做法**：删除 `onCreate` 同步调用，改为 `AppUpdateManager.checkForUpdates()` 内部先清理再检查（同一 SP、同一线程链，天然保序）；`checkForUpdates` 已在 `appScope`（L186）。
- **收益**：省 1 次 SP 首载（若 `checkForUpdates` 延后，则该 SP 首载也后移）。
- **风险**：⚠️ **存在时序依赖** —— 若 `checkForUpdates` 早于清理运行，会读到上一轮 ignore 值。**必须与 O4 一起改**（见 O4）。
- **红线**：不影响保活/心跳/消息管线。

### O3. `DataCleanupManager.schedulePeriodicCleanup` 移入 bgScope【低风险】
- **做法**：从同步段移入 `bgScope.launch { ... }`。
- **收益**：把 WorkManager 初始化 + enqueue 的主线程成本挪走。
- **风险**：⚠️ 若 WorkManager 已由 ContentProvider 阶段初始化，则收益有限【待验证】；`enqueueUniquePeriodicWork` 线程安全，行为不变。
- **红线**：**不得改变周期清理的注册结果**（Doze 下恢复依赖它），仅延后注册时机。

### O4. `MainActivity.onCreate` 中 setContent **之后**的保活/更新调度链后移【中风险，需保活回归】
- **做法**：将 `CompanionKeepAliveService.start` / `CompanionMessageWorker.schedule` / `scheduleIqooKeepAliveJob` / `KeepAliveAlarmScheduler.scheduleNext` / `checkForUpdates` 由「同步执行」改为 `window.decorView.post { ... }` 或 `appScope.launch(Dispatchers.IO)`（首帧后）。
- **收益**：这些当前全部在**首帧 doFrame 之前**执行，后移预期**削减数十~上百 ms【待验证】**。
- **风险 / 时序依赖**：⚠️ **直接触碰保活红线**。后移几百 ms 通常可接受，但必须在**熄屏 2 分钟不掉线 / Doze 恢复 / typing 时序**上回归验证（AGENTS.md §3 历史回归：FGS 被杀须立即自重启，不得延迟依赖 Worker）。
- **红线**：**保留首帧后尽快注册**；不得引入任何异步丢失注册路径。

### O5. 打点长期化：Baseline Profile / App Startup 追踪【收益存疑，勿依赖】
- **说明**：**因业务 DEX 加密存放，Baseline Profile 与 `cmd package compile` 一样大概率失效**（系统无法按 profile 预编译加密 DEX）【与已确证的「强制 AOT 无效」同源】。建议**仅接入 `reportFullyDrawn` + 现有 Trace**，**不要**把 BP 作为主要手段。

### O6.（长期）SharedPreferences → DataStore 演进
- 仅记录方向：DataStore 有独立后台加载，可从根上消除首载风暴，但属大改，**不在本轮范围**。

---

## 4. 红线与需用户决策项

### 4.1 硬红线（AGENTS.md，禁止破坏）
1. **保活链路**：FGS（`CompanionKeepAliveService`）/ Worker / Doze 恢复时序 —— O4 必须回归验证。
2. **心跳机制**：会话建立即稳定心跳、ack 超时、重连退避 —— 本方案未触碰，但改动 `MainActivity` 后需复测。
3. **消息管线时序**：发送→typing→AI 生成 —— 本方案不涉及。
4. **安全模块**：`ContentFilter` / 加固 / 密钥 —— 本方案不触碰；`SecureLog` 与 `Log.i` 选择须遵守（release 不剥离）。
5. **数据库完整性**：不新增 Entity/字段/索引（schema 冻结于 v41），不改 DAO 签名 —— 本方案不改 DB。

### 4.2 需用户决策项（不得由架构师擅自设计）
- **D1 · 加固壳 192ms `dexLoad`（`vmpPayload` 已 skipped）**：任何「弱化/绕过 DEX 解密与加载」的建议都会**降低加固强度**，攻击者可直接从内存 dump 明文 DEX。可选权衡及其**代价**：
  - (a) 缩减加密 DEX 体积 → 需拆包/裁剪模块，**改动大且可能破坏功能**；
  - (b) 解密与 UI 首帧并行 → 需壳与 App 约定线程模型，**削弱「解密先于业务代码」的安全假设**；
  - (c) 接受现状 221ms → **零安全代价**，仅优化剩余 ~1.3s。
  - **建议默认走 (c)**，把优化重心放在 O1~O4；是否动壳由用户拍板。
- **D2 · 是否接受「首帧后再注册保活」**（O4）：涉及保活红线，需用户确认可接受的延后窗口。

---

## 5. 任务分解（≤5 项，按依赖排序）

| ID | 任务 | 源文件 | 依赖 | 优先级 | 验收要点 |
|----|------|--------|------|--------|----------|
| **T01** | **打点基础设施**：扩展 `PerformanceTrace`（`startLaunch`/`markStartupStage`/`dumpStartupStages`/`startupStagesNanos`，tag=`YuNianPerf`）；`MainScreen` 首帧处一次性 `dumpStartupStages()` + `reportFullyDrawn()` | `core/common/.../PerformanceTrace.kt`、`app/.../MainScreen.kt` | 无 | P0 | release 包 `adb logcat -s YuNianPerf:I` 能打印一行多段结果；无新增主线程阻塞 |
| **T02** | **Application 侧打点**：在 `YuNianApplication.onCreate`/`initBusiness` 各同步调用前后插入 §1.3-A 全部 mark（SaltStore/SecureLog/language/AiService/Ntp/clearUpdate/ScopeProvider/safetyClassifier/cleanupSchedule + sync_done/initbusiness_done + service_registry_ready） | `app/.../YuNianApplication.kt` | T01 | P0 | 每个同步阶段独自成段；能区分「SP 首载」与「WM 初始化」 |
| **T03** | **Activity/组合侧打点**：`MainActivity.onCreate`（super/WorkManager/systembar/windowBg/setContent/oncreate_end）、组合期 SP 读取（agreement/user）、service_ready、mainscreen_enter、update_check | `app/.../MainActivity.kt` | T01 | P0 | 覆盖 setContent 前/后与首次组合；不改变调用顺序 |
| **T04** | **采集基线**：真机 release（Redmi 24129RT7CC / 120Hz）冷启动 ≥5 轮取中位数；交叉 `am start -W`、Choreographer；产出**分段基线表**，定位主导段（验证 O1 SP 假设） | 无代码（`docs/cold-start-perf.md` 追加「实测基线」节） | T01,T02,T03 | P0 | 给出各段 ms 中位数；明确最大 3 个段 |
| **T05** | **优化实施（按 T04 结果分阶段）**：优先 O2（clearUpdate 并入 AppUpdateManager）+ O3（DataCleanup 移 bgScope）；视数据决定 O1（SP 预热/合并）与 O4（保活调度后移，**需保活回归**） | `YuNianApplication.kt`、`MainActivity.kt`、（可选）`AppUpdateManager` | T04 | P1 | 冷启动首帧改善且**保活/心跳/typing 回归通过**；O4 落地须附「已验证不影响熄屏保活」说明 |

**依赖图**：`T01 → T02 → T04 → T05`；`T01 → T03 → T04`。

---

## 6. 附：与既有 `docs/chat-enter-perf.md` 的关系

本文档聚焦**冷启动（onCreate→首帧）**，与既有**进聊天页转场**打点（`chatEnterMetricsNanos`）互补，共用 `PerformanceTrace` 与 logcat 读取范式。聊天页结论沿用：【已确证】进聊天页不卡。

---
---

# 第二轮：实测数据与结论修正（T04 基线完成）

> T01-T03 打点落地后，真机 release 采集 3 轮。**第一轮的「SP 首载风暴」假设被实测推翻**，真因另有其人。本节保留第一轮内容供对照，以本节结论为准。

## 7. 实测基线（真机 release / Redmi 24129RT7CC / Android 16 / 120Hz）

单位 ms，距**进程启动**（`app_attach_begin=0`）；3 轮数据：

| 阶段 | R1 / R2 / R3 | 相对前段增量 |
|------|--------------|--------------|
| `app_attach_begin` | 0 | — |
| `app_oncreate_begin` | 142 / 145 / 146 | **+142**（进程创建 + 加固壳） |
| `initbusiness_begin` | 142 | 0 |
| `ib_salt … ib_clear_update` | 142 → 151/153/155 | **+9~13**（全部同步调用） |
| `ib_safety_classifier` | 154 / 154 / 166 | ~0 |
| `ib_cleanup_schedule` | 160 / 156 / 167 | **+6~11**（WorkManager 调度） |
| `initbusiness_done` | 160 / 156 / 167 | **同步段合计仅 ~25ms** |
| `act_attach_locale_done` | 183 / 179 / 204 | **+23~37**（theme_prefs 首载） |
| `act_window_bg_prefs` | 269 / 265 / 292 | **+55~76**（背景 SP 首载） |
| `act_worker_schedule` | 274 / 312 / 332 | +2~44（保活链） |
| `act_oncreate_end` | 276 / 314 / 334 | ~0 |
| `compose_agreement_prefs` | 337 / 370 / 385 | ~+60（组合期 SP） |
| `compose_user_prefs` | 337 / 370 / 385 | 0 |
| **`service_registry_ready`** | **1235 / 1121 / 1180** | **★★ +898 / +751 / +795** |
| `compose_service_ready` | 1242 / 1123 / 1185 | ~+7 |
| `home_first_compose` | 1242 / 1123 / 1185 | ~0 |
| `first_frame` | 1705 / 1554 / 1610 | **+463 / +431 / +425** |
| `am start -W TotalTime` | 654 / 684 / 702 | — |

## 8. 结论修正

1. **推翻第一轮「SP 首载风暴」假设**：`initBusiness` 同步调用**全部合计仅 ~25ms**；SP 相关合计约 **170ms**（`act_attach_locale` +25、`act_window_bg_prefs` +60、组合期 +60），**不在 475ms 量级**。
2. **真正主因**：`compose_user_prefs`(≈340ms) → `service_registry_ready`(≈1180ms) 之间存在 **~800~900ms 的纯等待**。
3. **机制（实读代码确证）**：
   - `MainActivity.kt:129` `val isServiceReady by ServiceRegistry.initialized.collectAsStateWithLifecycle()`
   - `MainActivity.kt:162-175`：`!isServiceReady -> { Box { CircularProgressIndicator() } }`，`else -> MainScreen(activity)`。
   - **整个主界面被 `ServiceRegistry.initialized` 门控，期间只显示一个 loading 圈。**
   - `PerformanceTrace` 的 `service_registry_ready` 打在 `YuNianApplication.kt` L188-222 的 `bgScope.launch{ … }` 内、`ServiceRegistry.markInitialized()` **之前**；该块串行执行：`registerServiceProviders` → `UserRepository.repairUserAvatar` → **`AppDatabase.verifyAndRecover`** → **`seedDefaultCompanion`** → **`HomeListCache.warm(AppDatabase.getDatabase)`** → **`ChatRepository.hydrateRecent(lastOpenedId, CHAT_PAGE_SIZE)`**，`finally { markInitialized() }`。

## 9. 根因确认（实读代码）

### 9.1 `ServiceRegistry` 语义（`core/domain/.../ServiceRegistry.kt`）
- `get(type)`：命中 `singletons` → 否则从 `singletonFactories[type]` **懒构造**并缓存 → 否则 `factories`。
- **关键**：`get()` 只依赖「**工厂已注册**」，**完全不依赖 `_initialized`**。`markInitialized()` 只是翻转一个 `StateFlow<Boolean>`，与 `get()` 的能力**无关**。
- ⇒ **UI 真正需要的只是「`registerServiceProviders` 已执行完」**，而当前却把它和后面一串重 IO 绑成同一个 `markInitialized` 门控。

### 9.2 首页实际依赖（实读 `HomeScreen` / `HomeViewModel` / `ChatGroupViewModel`）
- **`HomeViewModel` 构造函数（主线程，首组合时创建）**：
  - `ServiceRegistry.getOrThrow(CompanionRepository)` / `getOrThrow(ChatRepository)` → **未注册会抛 IllegalArgumentException**（这是门控存在的真实原因之一）；
  - `AppDatabase.getDatabase(application).conversationSummaryDao()` → **需要 DB 可打开**；
  - 首次 `initialValue = if (HomeListCache.isWarmed()) Ready(snapshot) else Loading`；随后 `combine(getAllCompanions(), summariesFlow)` 自动更新。
- **`ChatGroupViewModel`**：只需 `AppDatabase.getDatabase()`；不依赖 ServiceRegistry。
- **`HomeScreen`**：**自带 `Loading` / `Empty` / `Error` 三态**（`HomeScreen.kt:218-269`），**天然可以先渲染**，数据到达后由 Flow 自动填充。
- ⇒ 首页**不需要** `verifyAndRecover` / `seedDefaultCompanion` / `HomeListCache.warm` / `hydrateRecent` **全部完成**；它只需 **(a) provider 已注册、(b) DB 已打开**。

### 9.3 「必须先行」 vs 「可延后」分类

| 初始化项 | 首页首帧是否需要 | 结论 |
|---|---|---|
| `registerServiceProviders(app)`（含 `AppDatabase.getDatabase` 打开 DB） | **需要**（HomeViewModel `getOrThrow` + DB） | **必须先行** |
| `UserRepository.repairUserAvatar` | 否（首页不显示用户头像） | 可延后 |
| `AppDatabase.verifyAndRecover`（`wal_checkpoint(TRUNCATE)` + `PRAGMA user_version`） | 否（安全性网，非渲染依赖） | **可延后**（⚠️见风险） |
| `seedDefaultCompanion`（首启播种，有 `Mutex`、幂等） | 首次启动时会话列表为空→播种后由 Flow 补上；**且首启本来先显示协议页** | **可延后** |
| `HomeListCache.warm`（3 条同步查询） | 否（仅影响首帧是否「秒出」而非 loading） | 可延后（并行更佳） |
| `ChatRepository.hydrateRecent(lastOpenedId, …)` | 否（服务聊天页预热） | **明确可延后到首帧后** |
| `runBlocking{ AutomationStore.list()+rescheduleAll }`（在 `registerServiceProviders` 内 L538-545） | 否 | **必须移出**（否则仍在门控内，且占 IO） |
| `McpToolRegistrar.syncTools` / `refreshSkillIndex`（已 `bgScope.launch`） | 否 | 已在后台 |

## 10. 优化方案（候选评估）

> 目标：让**首页不再等那 ~900ms**。预期收益对照上表换算。

### 方案 A · 拆分 `markInitialized()` 时机【推荐核心】
- **做法**：把 `ServiceRegistry.markInitialized()` 从「整个 try 之后」提前到**紧跟 `registerServiceProviders(app)` 之后**（即 provider 已注册、DB 已打开即放行 UI）；把 `verifyAndRecover` / `seedDefaultCompanion` / `HomeListCache.warm` / `hydrateRecent` 移到**其后另一个 `bgScope.launch`**（或同一协程顺序执行但不阻挡门控）；把 `runBlocking{rescheduleAll}` 从 `registerServiceProviders` 内**移出**为独立 `bgScope.launch`。
- **预期收益**：门控等待由 ~900ms 降至「`registerServiceProviders` 完成时刻」。若 `verifyAndRecover+seed+warm+hydrate` 占其中的 500~700ms，则首帧前移 **同等量级**【待验证——需重测确认 `registerServiceProviders` 自身耗时】。
- **红线**：不触碰保活/心跳/消息管线/安全；**触及 DB 完整性语义（弱化）**——见风险。
- **回归风险**：
  - ⚠️ **DB 损坏暴露**：当前 `verifyAndRecover` 在 UI 前完成，损坏会被静默修复/重建；提前放行后，首页可能在「DB 即将被关闭重建」的窗口内读到旧库 → 短暂空/错，或触发 `HomeViewModel.catch` 显示 Error 态。**概率低但必须标注**；缓解：把 `verifyAndRecover` 放在门控前、其余重活儿放门控后（即 A 的「保守版」）。
  - ⚠️ **首启播种可见性**：首页可能先显示空态再填充（仅首启，且首启先走协议页，基本被掩盖）。
  - ⚠️ **HomeListCache 未 warm**：首帧由「秒出数据」变成「短暂 loading 圈」（HomeScreen 自身态，非全局阻塞）。可接受，观感略降。

### 方案 B · 解除 UI 门控（MainScreen 直渲）
- **做法**：去掉 `!isServiceReady -> spinner`，直接渲染 `MainScreen`，靠 `HomeScreen` 三态兜底。
- **评估**：**单独不可行**——`MainScreen` 首组合即构造 `HomeViewModel`，它 `getOrThrow` 会在 provider 未注册时**直接抛异常崩溃**。**必须与 A 组合**（用一个「仅代表 provider 已注册」的更早信号替代 `initialized`）。
- **收益**：与 A 叠加，可消掉 A 之后残留的 ~7ms 门控抖动 + 避免 loading 圈闪烁。
- **风险**：同上；额外风险是若误用早于注册的信号 → `getOrThrow` 崩溃。

### 方案 C · 首页数据独立快速通道
- **做法**：为首页做一条不依赖全量初始化的最小查询（DB + 2 张表的只读查询）。
- **评估**：**收益有限**——首页的瓶颈不是「查询重」，而是「**等待串行重 IO 完成**」。DB 打开本身省不掉。C 本质是 A 的一个实现细节（DB 打开后尽早放行 + 首页只读查询），**不推荐单列**。

### 方案 D · 架构师推荐组合（D1）
在 A 的基础上，按「**最小放行屏障**」重排 `YuNianApplication.initBusiness` 的 bgScope 块：

```
bgScope.launch {
    registerServiceProviders(app)          // 注册 provider + 打开 DB（放行前唯一硬依赖）
    ServiceRegistry.markInitialized()      // ★ 立即放行 UI（首页/群列表可渲染）
    // —— 以下全部在放行之后，不再阻挡首帧 ——
    runCatching { repairUserAvatar }
    runCatching { AppDatabase.verifyAndRecover }   // 用户决策：完整后移到放行之后
    seedDefaultCompanion(app)
    runCatching { HomeListCache.warm(...) }        // 或并行协程，供首页秒出
    runCatching { hydrateRecent(lastOpened) }
}
// runBlocking{ AutomationStore.list()+rescheduleAll } 独立协程，移出 registerServiceProviders
```
- **预期收益**：门控消除 ~800ms 级等待【待验证】。
- **红线/风险**：同 A。**用户已拍板「完整后移」**：`verifyAndRecover` 也在放行之后执行（接受 DB 损坏极端场景下首页先显示空态再恢复的代价，以换取最大首帧收益）。
- **额外建议**：`registerServiceProviders` 内的**饿汉式** `ServiceRegistry.getOrThrow(CoffeeOrderProvider/MemoryProvider/SkillManager/AutomationStore)`（L484/488/500/505/530/540）会在放行前实例化，若 `UnifiedMemoryProvider` 等构造偏重，建议改为**懒注册**，进一步压低屏障。

### 方案 D1 补充（与 software-engineer 复核后锁定，2026-09）

- **放行信号可更早**：`HomeViewModel` 只依赖 `CompanionRepository`（L357）+ `ChatRepository`（L363）+ 已打开的 DB（L356）。因此 `providersRegistered` **理论上可在「这两个 Repository 注册 + DB 打开」之后置位**，不必等整个 `registerServiceProviders` 返回。
- **但有一个前置条件**：`registerServiceProviders` 内存在**饿汉** `getOrThrow`（`CoffeeOrderProvider` L484 / `UnifiedMemoryProvider` L487 / `SkillManager` L500 / `registerSkillTools` L505 / `AutomationStore` L530）与 **L538-545 `runBlocking{ AutomationStore.list()+rescheduleAll }`**，它们会在「方法返回」之前同步跑。**若 `providersRegistered` 只标在方法返回后，屏障仍会残留这段耗时**。
- **定稿结论（用户已决策：完整后移）**：
  1. **必须先把 L538-545 的 `runBlocking` 移出**为独立 `bgScope.launch`（否则「更早信号」的收益被它吃掉）；
  2. `providersRegistered` **在 `registerServiceProviders` 返回后置位**（避免依赖「方法内某一行」的脆弱契约；先摘除 runBlocking 再置位，屏障已足够低）；
  3. **放行屏障 = `registerServiceProviders` 完成即 `markInitialized()`**；`repairUserAvatar` / **`verifyAndRecover`** / `seedDefaultCompanion` / `HomeListCache.warm` / `hydrateRecent` **全部**在放行之后执行（**非保守版**）；
  4. 新增 `providersRegistered` 为**独立** `StateFlow<Boolean>`（不污染 `initialized` 既有语义，其它读取点/测试无需改），符合架构最小变更。

### 回归清单（T05′ 必测）
| # | 场景 | 关注点 |
|---|------|--------|
| R1 | **冷启动首帧** | `service_registry_ready` 前移；不再整屏 loading；TotalTime 下降 |
| R2 | **首启播种**（清数据首次启动） | 协议页 → 选角 → 首页；seed 后移不导致「空态卡死」；HomeScreen 三态能自愈 |
| R3 | **空数据态** | 无会话时显示 `EmptyHomeState` 而非 Error/永久 loading |
| R4 | **会话列表 + 未读角标** | `combine` Flow 首帧后正确填充；`hasUnread` 正确 |
| R5 | **DB 损坏场景**（可选注入） | 完整后移下首页可能先显示空态/错误态，需**能自动恢复**（verifyAndRecover 完成后 UI 自愈，不卡死） |
| R6 | **微信 / QQ 桥接** | 桥接链路读 `ServiceRegistry.get()` 仍正常（懒构造未受影响） |
| R7 | **熄屏保活 / 心跳 / typing** | 未触碰，但改动 MainActivity 后复测（AGENTS.md 红线） |
| R8 | **进聊天页** | 保持第一轮结论（不卡）；`hydrateRecent` 后移不影响进聊天页帧率 |

## 11. 首帧 ~470ms（`home_first_compose` → `first_frame`）构成与观感优化

- **构成推断【待验证】**：`MainScreen` 首组合 + 测量/布局 + draw：`rememberNavController`、`rememberLayerBackdrop`（液态玻璃捕获层）、`PageBackgroundContent`（背景位图解码）、`LiquidBottomTabs`、`HomeScreen` 列表 + Coil 头像首解码。
- **关键约束**：因**业务 DEX 加密 → 无 AOT**，首次运行大量 Compose/Coil/backdrop 类的 **JIT 编译**在此窗口发生，**Baseline Profile 大概率同源失效**。
- **零观感优化候选**（均需实测，可能牺牲首帧观感 → **需用户/UI 决策**）：
  1. **骨架首帧**：首帧只画导航骨架/背景，玻璃与列表次帧再上（用 `PageBackgroundContent.onPainterReady` 与 `rememberLayerBackdrop` 分层降级）。
  2. **背景位图预解码前移**：把主背景 bitmap 解码提到放行前并行预热。
  3. **玻璃层延后一帧**：`layerBackdrop`/`drawGlass` 在第二帧启用。
- **建议先补打点**：`mainscreen_nav_compose`、`background_painter_ready`（已有 `onPainterReady` 钩子）、`first_layout`、`first_draw`，把 470ms 再切分后再决定是否动观感。

## 12. 红线与需用户决策项（第二轮）

- 硬红线（AGENTS.md）：保活（FGS/Worker/Doze）、心跳、消息管线、`ContentFilter`/加固/密钥、**DB 完整性**。方案 A/D 触及 **DB 完整性语义（把校验/播种移出首帧临界路径）**，须评估。
- **需用户决策**：
  - **D1 ·【已决策：接受提前放行（完整后移）】**：provider 注册完即放行首页，把 DB 校验/播种/缓存预热/预读会话**全部**移到首帧之后；用户已知晓极端（DB 损坏）下首页可能先显示空态再恢复。**故不采用保守版。**
  - **D2 · 是否接受「首页首帧由秒出数据退化为短暂 loading 圈」**（`HomeListCache.warm` 后移）。可用「warm 与放行并行、首页仍显示自身 loading」缓解。
  - **D3 · 首帧 470ms 是否允许降级首帧观感**（骨架/延后玻璃）。
  - **D4 · 加固壳 142ms（`app_oncreate_begin`）+ 192ms `dexLoad`** 维持原判：**建议接受现状**，不动加固（任何弱化必降低防 dump 强度）。

## 13. 任务分解（修订，≤5）

| ID | 任务 | 源文件 | 依赖 | 优先级 | 验收要点 |
|----|------|--------|------|--------|----------|
| **T01′** | **拆分放行屏障（完整后移）**：`markInitialized()` 提前到 `registerServiceProviders` 之后；`repairUserAvatar`/`verifyAndRecover`/`seed`/`warm`/`hydrateRecent` **全部**移到放行后；`runBlocking{rescheduleAll}` 移出 `registerServiceProviders` 独立协程 | `app/.../YuNianApplication.kt` | T04 | P0 | `service_registry_ready` 显著前移；DB 损坏场景可自愈（R5） |
| **T02′** | **释放 UI 门控**：`MainActivity` 用「provider 已注册」信号替代重初始化门控，直渲 `MainScreen`；保留 HomeScreen 三态兜底 | `app/.../MainActivity.kt`、`core/domain/.../ServiceRegistry.kt`（如需新增 `providersRegistered` 信号） | T01′ | P0 | 不再整屏 loading；首页 loading 仅限列表区域 |
| **T03′** | **懒化重注册**：将 `registerServiceProviders` 内饿汉 `getOrThrow(CoffeeOrderProvider/UnifiedMemoryProvider/SkillManager/AutomationStore)` 改懒注册，压低放行屏障 | `app/.../YuNianApplication.kt`（或对应 Provider 实现） | T01′ | P1 | `registerServiceProviders` 自身耗时下降【需补打点确认】 |
| **T04′** | **首页秒出与首帧细分**：`HomeListCache.warm` 与放行并行；补 `mainscreen_nav_compose`/`background_painter_ready`/`first_layout`/`first_draw` 打点，切分 470ms | `YuNianApplication.kt`、`app/.../MainScreen.kt`、`core/.../PerformanceTrace.kt` | T01′ | P1 | 首帧不再等 warm；470ms 可归因 |
| **T05′** | **复测与回归**：真机 release ≥5 轮复测对照；保活/心跳/typing/首启播种/空数据态/**微信-微信群聊桥接**/未读角标回归 | 无代码（追加基线） | T01′-T04′ | P0 | 首帧明显前移且无回归 |

**依赖图**：`T01′ → T02′ → T03′`；`T01′ → T04′ → T05′`；`T02′ → T05′`。

> 备注：T02′ 若需新增「providersRegistered」信号，建议在 `ServiceRegistry` 增加一个 **独立于 `initialized`** 的 `StateFlow<Boolean>`，避免与既有语义耦合（架构最小变更原则）。

---
---

# 第三轮：复测结果与新瓶颈

## 14. 复测结果（T01′ 放行屏障拆分已生效）

真机 release，11 轮（剔除安装首轮）**中位数**：

| 指标 | 优化前 | 优化后 | 变化 |
|---|---|---|---|
| `service_registry_ready` | ~1180ms | **~511ms** | **−669ms** ✅ |
| `first_frame` | ~1610ms | **~1082ms** | **−528ms** ✅ |

第二批次原始（ms，距进程启动）：

```
轮1 | oncreate_begin=125  ready=284  home_first_compose=385  first_layout=964   first_frame=988
轮2 | oncreate_begin=124  ready=553  home_first_compose=560  first_layout=1181  first_frame=1208
轮3 | oncreate_begin=124  ready=189  home_first_compose=419  first_layout=933   first_frame=957
轮4 | oncreate_begin=132  ready=629  home_first_compose=633  first_layout=1310  first_frame=1337
轮5 | oncreate_begin=136  ready=301  home_first_compose=362  first_layout=1002  first_frame=1029
轮6 | oncreate_begin=135  ready=523  home_first_compose=555  first_layout=711   first_frame=732
```

**新瓶颈**：`home_first_compose → first_layout` **≈580ms**（中位，区间 156~677ms），为当前最大单段。细分：`home_first_compose → background_painter_ready/mainscreen_nav_compose` 仅 **~10~15ms**；`mainscreen_nav_compose → first_layout` **~570ms**（主体）；`first_layout → first_draw/first_frame` **~20~30ms**。

> 解读：**背景与绘制都很快**（画家就绪 ~10ms、绘制 ~25ms）；成本几乎全部落在「**NavHost 组合完成 → 首帧布局完成**」这段的**首次组合 + 测量/布局**上。

## 15. 构成判定（实读 `MainScreen` → `MainNavHost` → `MainTabPager` → `HomeScreen/ContactsScreen` → `FloatingGlassBottomNav`）

### 15.1 首帧实际被组合的子树（该 ~570ms 窗口内）

| 组件（文件:行） | 首帧是否组合 | 成本性质 | 判定 |
|---|---|---|---|
| `MainScreen` 外壳（VM×3、dialogs、theme、update flow）（`MainScreen.kt:65-244`） | 是 | 中等 | 确定 |
| `NavHost` + `PageTransitions`（`MainNavGraph.kt:94-101`） | 是 | 中（导航库首次） | 确定 |
| `MainTabScreen` / `MainTabPager`（`HorizontalPager`，`MainNavGraph.kt:424-464`） | 是 | 中 | 确定 |
| **`HomeScreen` page 0**（`HomeScreen.kt`） | 是（必要） | 较大（LazyColumn + 玻璃 item + 2 VM + HomeTabBar） | 确定（必要） |
| **`ContactsScreen` page 1**（`MainNavGraph.kt:442`） | **是（非必要！）** | **大**（独立 `CompanionListViewModel` + 排序/分组 + Scaffold + 搜索框 + 分段 LazyColumn + 玻璃 item） | **确定（off-screen 提前组合）** |
| `FloatingGlassBottomNav` + `LiquidBottomTabs`（`MainBottomBar.kt:94-137`、`LiquidBottomTabs.kt:154-520`） | 是 | 中~大 | 确定 |
| `PageBackgroundContent`（`MainScreen.kt:221`） | 是 | **小（~10~15ms）** | 确定 |

**关键发现（确定性）**：`MainTabPager` 使用 **`HorizontalPager(beyondViewportPageCount = 1)`**（`MainNavGraph.kt:432`），会**在当前页 ±1 范围内预组合**，因此冷启动时 **page1 = `ContactsScreen` 被完整组合并测量**，而它**根本不可见**。`ContactsScreen` 是重量级列表屏（自带 ViewModel、`Collator` 排序、`groupBy` 分组、搜索框、分段 `LazyColumn`、玻璃 item）——**这是冷启动首帧最大的一块可去除成本（零观感）**。

**另一确定成本**：`LiquidBottomTabs` 默认 `captureTabContent = true`（`LiquidBottomTabs.kt:164, 473-518`），会在 `alpha(0f)` 下**再组合一份完整的 tab 内容**（用于 lens 捕获）→ 底栏内容**被组合两次**。

### 15.2 「可测组合成本」vs「无 AOT 首次 JIT」
- **确定**：首帧组合体量客观很大（HomeScreen + **ContactsScreen** + 玻璃底栏×2份 + Pager + NavHost），且这些类的**首次类加载与首次执行**都发生在这一帧。
- **待验证（高可疑）**：其中相当比例是 **首次 JIT 编译开销**——因**业务 DEX 加密 → 系统 AOT 无效**（`cmd package compile` 已实测无效），首次运行大量 Compose/Coil/backdrop/自定义组件的字节码走解释器+JIT。**量级无法仅凭现有打点拆分**，需 §16 T01″ 的子树级打点归因，或与「关闭 page1 预组合」的 A/B 对照来间接量化。

## 16. 优化方案（零观感优先）

### 16.1 零观感变化候选（优先）
| 方案 | 做法 | 预期收益 | 观感 | 风险 |
|---|---|---|---|---|
| **O-A 冷启动不预组合第 2 页** | `MainTabPager` 的 `HorizontalPager` 把 `beyondViewportPageCount` 由 1 改为 **0**（或按「首帧后」再置 1） | **最大**（直接去掉整屏 `ContactsScreen` 的组合+测量）【待验证量级】 | **零**（page1 本不可见） | ⚠️ **首次横滑到通讯录会即时组合** → 该次滑动可能有一次卡顿（一次性）。需回归；可用「首帧后用 `LaunchedEffect` 延迟把 beyond 置回 1」两全 |
| **O-B 收敛首次组合的 composable 数量** | `MainScreenDialogs`、`updateManager` 流收集等非首屏必需项延后到首帧后（`LaunchedEffect`/`withFrameNanos`） | 小~中 | 零 | 低 |
| **O-C `remember`/`derivedStateOf` 下沉** | 把首帧不需要的派生计算（如 dialogs、非首屏状态）下沉到 effect | 小 | 零 | 低 |
| **O-D LazyColumn 首屏 item 数收敛** | HomeScreen/ContactsScreen 已是 `LazyColumn`（天然只组合可见项）→ **已无此余地** | ~0 | 零 | — |

### 16.2 需用户拍板（观感变化）
| 方案 | 做法 | 预期收益 | 观感变化（需明示） | 风险 |
|---|---|---|---|---|
| **O-E 底栏延后一帧** | `FloatingGlassBottomNav` 组合推迟到第二帧 | 中 | **首帧无底部导航，次帧出现**（可能被感知为闪现） | 低 |
| **O-F 关闭 `captureTabContent`** | 主底栏 `LiquidBottomTabs(captureTabContent = false)` | 中（省一份 tab 组合） | **tab 内容的 refract/lens 捕获效果减弱**（玻璃质感变化） | 玻璃观感回归 |
| **O-G 玻璃层延后一帧** | `layerBackdrop`/`drawGlass` 第二帧启用 | 小~中 | 首帧玻璃不折射（一次） | 观感 |
| **O-H 骨架屏首帧** | 首帧画导航骨架，内容次帧 | 中 | 首帧为骨架 | 需 UI 设计 |

> 优先推 **O-A**：**唯一同时满足「零观感 + 大收益」**的项。建议先做 O-A 的 A/B 复测，量化它占 570ms 的比例，再决定是否需要 O-B~O-H。

### 16.3 Baseline Profile 是否真的完全无效？（判断，非臆断）
- **高置信度结论：对业务代码基本无效。** 理由链：
  1. 业务 DEX **加密存放**，安装期 `dexopt` 看到的磁盘 dex **不含业务类** → Baseline Profile/`.prof` 无匹配方法 → **无 AOT 产物**；
  2. 运行时 JIT 的产物**默认不持久化**；即便写入 runtime profile，下次冷启动要 `dexopt` 消费它仍需**业务类在磁盘 dex 中** → 依然失效；
  3. 与**已实测的「`cmd package compile -m speed -f` 强制 AOT 无效」**一致 → 相互印证。
- **可能的例外（待验证）**：若加固壳把「解密后 DEX 落盘」或注册了 runtime profile / 走 `profileinstaller` 触发对**磁盘明文 dex** 的 `dexopt`，则**部分**生效——但**明文 DEX 落盘会削弱加固**（攻击者可直接 dump），属**安全红线，不可取**。
- **建议**：**不投入 Baseline Profile**；若要一锤定音，做一次对照实验（加 profile 前后测 `mainscreen_nav_compose→first_layout`）即可，预期无变化。真正能持久化 AOT 的唯一路径是「业务 DEX 不加密」——**与加固冲突，交用户决策**。

## 17. 红线与需用户决策项（第三轮）
- 硬红线：保活/心跳/消息管线/安全（加固）/DB 完整性。本轮优化只动 **UI 组合时机**，**不触碰**保活与安全；O-A 需回归「首次横滑通讯录」。
- 需用户决策：**O-E 底栏延后一帧** / **O-F 关闭 `captureTabContent`** / **O-G 玻璃延后一帧** / **O-H 骨架屏** 均改观感，需拍板；**O-A 零观感，建议直接做**。

## 18. 任务分解（第三轮，≤5）
| ID | 任务 | 源文件 | 依赖 | 优先级 | 验收要点 |
|----|------|--------|------|--------|----------|
| **T01″** | **子树级归因打点**：在 `HomeScreen`（组合完成/首条 item 布局）、`ContactsScreen`（组合完成）、`FloatingGlassBottomNav`（组合完成）、`MainTabPager`（page1 是否被组合）埋点，把 570ms 归属到具体子树；加 `pager_page1_composed` 标志 | `MainScreen.kt`、`MainNavGraph.kt`、`HomeScreen.kt`、`ContactsScreen.kt`、`MainBottomBar.kt` | T04′ | P0 | 570ms 可归因到子树；确认 page1 预组合占比 |
| **T02″** | **O-A 冷启动不预组合第 2 页**：`HorizontalPager.beyondViewportPageCount` 冷启动置 0，首帧后延迟恢复为 1 | `MainNavGraph.kt` | T01″ | P0 | 首帧前移；首次横滑通讯录无卡顿（或可控） |
| **T03″** | **零观感收敛**（视 T01″ 结果）：O-B dialogs/流收集延后、O-C 派生计算下沉 | `MainScreen.kt` | T01″ | P1 | 首帧前移，观感不变 |
| **T04″** | **需拍板项实施**（O-E/O-F/O-G/O-H，按用户选择） | `MainScreen.kt`、`MainBottomBar.kt`、`LiquidBottomTabs.kt` | T01″ + 用户决策 | P1 | 按拍板口径实施并回归观感 |
| **T05″** | **复测 + 回归**：≥5 轮对照；首次横滑通讯录、底栏/玻璃观感、保活/心跳/typing、进聊天页 | 无代码（追加基线） | T02″-T04″ | P0 | 首帧明显前移且无回归 |

**依赖图**：`T01″ → {T02″(P0), T03″(P1), T04″(需拍板)}`；`T02″/T03″/T04″ → T05″`。

> 结论排序：**先 T01″ 归因 → 落 T02″（O-A，唯一零观感大收益）→ 复测 → 再按数据决定是否动需拍板的观感项**。Baseline Profile 建议放弃（§16.3）。

## 19. O-A 落地定案补充（与 software-engineer-2 对齐）

### 19.1 恢复时机（定案：方案1 + 方案2 叠加）
`beyondViewportPageCount` 需提升为**状态**（固定实参无法运行时改）；状态定义在 **`MainTabPager` 内部**，改动收敛 `MainNavGraph.kt`。恢复 `beyond=1` 采用「**空闲窗口 + 非滑动中**」双保险：

```kotlin
LaunchedEffect(Unit){
    withFrameNanos{}                                   // 让首帧稳定
    delay(300)                                         // 避开首帧与第2帧
    snapshotFlow { pagerState.isScrollInProgress }.first { !it }  // 若正在横滑，等其结束
    beyond = 1
}
```
- 纯挂起、零主线程阻塞；避免「恢复到拖动帧」撞车。
- **方案3 兜底**：若首帧后 500ms 内出现新增 Choreographer 告警，则永久 `beyond=0`（代价=首次横滑通讯录即时组合）。

### 19.2 打点盲区（engineer-2 提出 · 采纳，须修）
现有 `dumpStartupStages()` 是 **一次性**（在 `first_frame` 的 `drawWithContent` 调一次，`MainScreen.kt:214`）。因此：
- `beyond=0` 时 `contacts_subtree_compose` 在**首帧不会发生**；延迟恢复后它在**第二/后续帧**才组合，**晚于那次唯一 dump → 永不进入冷启 dump**。
- ⇒ 「`contacts_subtree_compose` 未出现」在 `beyond=0` 下**恒成立**，**只能证明「首帧未组合 page1」，不能量化 O-A 收益、也无法定位 page1 组合落在哪一帧**。
- **修复要求（T01″）**：增设**第二个时序出口** —— 在 `ContactsScreen` 组合处一次性 `markStartupStage("contacts_subtree_compose")` **并**直接 `Log.i("YuNianPerf", ...)`（含相对 `startLaunch` 的 ms，可复用 `startupStagesNanos()`）；或给 `dumpStartupStages` 增加「可重复第二档」（如 `dumpStartupStages(label)`，在恢复后再调一次 `label="post_restore"`）。否则只能靠 `Choreographer:I` 交叉验证 hitch，**无法给出 page1 组合时刻**。

### 19.3 O-A 验收口径（v2，三条同时满足）
1. 首帧 `first_frame` 前移；
2. **首帧后 500ms 内无新增 Choreographer 告警**（用 `adb logcat -s Choreographer:I` + 第二时序出口交叉验证）；
3. 首次横滑到通讯录不卡（page1 按需组合，或已在空闲窗口预组合完毕）。

> 命名：`home_subtree_compose`（HomeScreen 组合体首行，`remember{}` 一次性）与既有 `home_first_compose`（MainScreen 首行）区分。

