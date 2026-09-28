package com.yunian.ai.common

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

object PerformanceTrace {
    private val shellStarted = AtomicLong()
    private val shellAntiHookDone = AtomicLong()
    private val shellNativeInitDone = AtomicLong()
    private val shellRecoveryDone = AtomicLong()
    private val shellMemoryGuardDone = AtomicLong()
    private val shellPreflightDone = AtomicLong()
    private val securityStarted = AtomicLong()
    private val securityTinkDone = AtomicLong()
    private val securityWhiteBoxDone = AtomicLong()
    private val securityAttestationDone = AtomicLong()
    private val securitySignatureDone = AtomicLong()
    private val securityKmsDone = AtomicLong()
    private val securityIntegrityDone = AtomicLong()
    private val securityDone = AtomicLong()
    private val startupStarted = AtomicLong()
    private val startupDrawn = AtomicLong()
    private val contactsStarted = AtomicLong()
        fun startStartup(startedNanos: Long = SystemClock.elapsedRealtimeNanos()) {
            startupDrawn.set(0L)
            startupStarted.set(startedNanos)
        }

        fun markStartupDrawn() {
            startupDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }

    private val contactsDrawn = AtomicLong()
    private val chatStarted = AtomicLong()
    private val chatShellDrawn = AtomicLong()
    private val chatMessagesDrawn = AtomicLong()

    // ---- 进入聊天页转场分段打点（T01） ------------------------------------------------
    // 全部为纯 AtomicLong：仅做无锁 CAS / set，**不分配对象、不做 IO**，
    // 因此可安全地在组合期与 draw 回调中调用（既有约定：见 chatShellDrawn 在 drawWithContent 中）。
    private val tierDetectStarted = AtomicLong()
    private val tierDetectDone = AtomicLong()
    private val chatFirstCompose = AtomicLong()
    private val chatBackdropRecordStarted = AtomicLong()
    private val chatBackdropRecordDone = AtomicLong()
    private val chatTransitionEnd = AtomicLong()

    fun startShell() = resetAndStart(
        shellStarted,
        shellAntiHookDone,
        shellNativeInitDone,
        shellRecoveryDone,
        shellMemoryGuardDone,
        shellPreflightDone
    )

    fun markShellAntiHookDone() = mark(shellAntiHookDone)
    fun markShellNativeInitDone() = mark(shellNativeInitDone)
    fun markShellRecoveryDone() = mark(shellRecoveryDone)
    fun markShellMemoryGuardDone() = mark(shellMemoryGuardDone)
    fun markShellPreflightDone() = mark(shellPreflightDone)

    fun startSecurity() = resetAndStart(
        securityStarted,
        securityTinkDone,
        securityWhiteBoxDone,
        securityAttestationDone,
        securitySignatureDone,
        securityKmsDone,
        securityIntegrityDone,
        securityDone
    )

    fun markSecurityTinkDone() = mark(securityTinkDone)
    fun markSecurityWhiteBoxDone() = mark(securityWhiteBoxDone)
    fun markSecurityAttestationDone() = mark(securityAttestationDone)
    fun markSecuritySignatureDone() = mark(securitySignatureDone)
    fun markSecurityKmsDone() = mark(securityKmsDone)
    fun markSecurityIntegrityDone() = mark(securityIntegrityDone)
    fun markSecurityDone() = mark(securityDone)

    fun shellMetricsNanos(): Map<String, Long> = linkedMapOf(
        "shell_anti_hook" to duration(shellStarted, shellAntiHookDone),
        "shell_native_init" to duration(shellAntiHookDone, shellNativeInitDone),
        "shell_recovery_oat" to duration(shellNativeInitDone, shellRecoveryDone),
        "shell_memory_guard" to duration(shellRecoveryDone, shellMemoryGuardDone),
        "shell_preflight" to duration(shellMemoryGuardDone, shellPreflightDone),
        "shell_total" to duration(shellStarted, shellPreflightDone)
    )

    fun securityMetricsNanos(): Map<String, Long> = linkedMapOf(
        "security_tink" to duration(securityStarted, securityTinkDone),
        "security_white_box" to duration(securityTinkDone, securityWhiteBoxDone),
        "security_attestation" to duration(securityWhiteBoxDone, securityAttestationDone),
        "security_signature" to duration(securityAttestationDone, securitySignatureDone),
        "security_kms" to duration(securitySignatureDone, securityKmsDone),
        "security_integrity" to duration(securityKmsDone, securityIntegrityDone),
        "security_finalize" to duration(securityIntegrityDone, securityDone),
        "security_total" to duration(securityStarted, securityDone)
    )

    fun persistReleaseMetrics(context: Context) {
        val editor = context.getSharedPreferences(RELEASE_METRICS_PREFS, Context.MODE_PRIVATE).edit()
        (shellMetricsNanos() + securityMetricsNanos()).forEach { (name, nanos) ->
            editor.putLong(name, nanos)
        }
        editor.putLong("recorded_at_elapsed_nanos", SystemClock.elapsedRealtimeNanos())
        editor.apply()
    }

    fun startContacts() {
        contactsDrawn.set(0L)
        contactsStarted.set(SystemClock.elapsedRealtimeNanos())
    }

    fun markContactsDrawn() {
        if (contactsStarted.get() != 0L) {
            contactsDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun startChat() {
        chatShellDrawn.set(0L)
        chatMessagesDrawn.set(0L)
        chatFirstCompose.set(0L)
        chatBackdropRecordStarted.set(0L)
        chatBackdropRecordDone.set(0L)
        chatTransitionEnd.set(0L)
        chatStarted.set(SystemClock.elapsedRealtimeNanos())
    }

    fun markChatShellDrawn() {
        if (chatStarted.get() != 0L) {
            chatShellDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun markChatMessagesDrawn() {
        if (chatStarted.get() != 0L) {
            chatMessagesDrawn.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    /** 首屏消息列表就绪（语义同 [markChatMessagesDrawn]，命名对齐 T01 打点口径）。 */
    fun markChatListReady() = markChatMessagesDrawn()

    /** ChatScreen 首次组合完成（效果 block 首次进入时调用）。 */
    fun markChatFirstCompose() {
        if (chatStarted.get() != 0L) {
            chatFirstCompose.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    /** 背景捕获层开始录制（在 `layerBackdrop` 的绘制 lambda 中调用，纯 CAS、无分配）。 */
    fun markChatBackdropRecordStart() {
        if (chatStarted.get() != 0L) {
            chatBackdropRecordStarted.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    /** 背景捕获层录制结束（同 [markChatBackdropRecordStart] 的绘制路径）。 */
    fun markChatBackdropRecordDone() {
        if (chatStarted.get() != 0L) {
            chatBackdropRecordDone.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    /** 进入转场结束（ChatScreen 侧按转场时长延后打点）。 */
    fun markChatTransitionEnd() {
        if (chatStarted.get() != 0L) {
            chatTransitionEnd.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    /** 硬件档位冷探测耗时：开始（由后台预热触发，`warmUp` 调用）。 */
    fun markTierDetectStart() {
        if (tierDetectStarted.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())) {
            tierDetectDone.set(0L)
        }
    }

    /** 硬件档位冷探测耗时：结束。 */
    fun markTierDetectDone() {
        if (tierDetectStarted.get() != 0L) {
            tierDetectDone.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun tierDetectNanos(): Long = duration(tierDetectStarted, tierDetectDone)

    /** 进入聊天页转场各分段耗时（纳秒），供调试面板 / logcat 读取。 */
    fun chatEnterMetricsNanos(): Map<String, Long> = linkedMapOf(
        "tier_detect" to duration(tierDetectStarted, tierDetectDone),
        "chat_first_compose" to duration(chatStarted, chatFirstCompose),
        "chat_backdrop_record" to duration(chatBackdropRecordStarted, chatBackdropRecordDone),
        "chat_shell_drawn" to duration(chatStarted, chatShellDrawn),
        "chat_list_ready" to duration(chatStarted, chatMessagesDrawn),
        "chat_transition_end" to duration(chatStarted, chatTransitionEnd),
        "chat_total" to duration(chatStarted, chatMessagesDrawn)
    )

    fun startupNanos(): Long = duration(startupStarted, startupDrawn)

    fun contactsNanos(): Long = duration(contactsStarted, contactsDrawn)

    fun chatShellNanos(): Long = duration(chatStarted, chatShellDrawn)

    fun chatMessagesNanos(): Long = duration(chatShellDrawn, chatMessagesDrawn)

    fun chatTotalNanos(): Long = duration(chatStarted, chatMessagesDrawn)

    private fun duration(start: AtomicLong, end: AtomicLong): Long {
        val startNanos = start.get()
        val endNanos = end.get()
        return if (startNanos > 0L && endNanos >= startNanos) endNanos - startNanos else 0L
    }

    private fun mark(target: AtomicLong) {
        target.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
    }

    private fun resetAndStart(start: AtomicLong, vararg stages: AtomicLong) {
        stages.forEach { it.set(0L) }
        start.set(SystemClock.elapsedRealtimeNanos())
    }

    const val RELEASE_METRICS_PREFS = "release_performance_metrics"

    // ===== 冷启动分段打点（新增；纯 AtomicLong / 轻量并发 Map，仅启动期调用）===================
    // 设计约束（见 docs/cold-start-perf.md §1）：
    //   1) 纯内存、无锁 CAS，**严禁 IO、严禁对象分配**（markStartupStage 允许极少量 map 条目分配）；
    //   2) 统一时基 SystemClock.elapsedRealtimeNanos()（含深睡、单调、跨阶段可比）；
    //   3) 具名打点幂等（同名只记首次）；
    //   4) release 可读：dump 使用固定 tag 的 android.util.Log.i（Log.d/Log.v 会被 R8 剥离）。
    // 旧口径 startupStarted→startupDrawn（MainActivity.onCreate→首帧）语义**保持不变**；
    // 新口径 launchStarted→first_frame（attachBaseContext→首帧）覆盖完整冷启动，二者并存。
    private const val STARTUP_TAG = "YuNianPerf"
    private val launchStarted = AtomicLong(0L)
    private val startupStages = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val startupOrder = java.util.Collections.synchronizedList(java.util.ArrayList<String>(24))
    private val startupDumped = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 进程内最早可达点（YuNianApplication.attachBaseContext 首行）调用一次，作为冷启动时基。
     * 幂等：仅记录首次调用时间，重复调用不覆盖。
     *
     * @param startedNanos 起始时间（默认取当前 elapsedRealtimeNanos）。
     */
    fun startLaunch(startedNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        launchStarted.compareAndSet(0L, startedNanos)
    }

    /**
     * 记录一个具名启动阶段（幂等：同名只记首次）。仅在启动同步路径调用约 15 次，
     * 允许极少量分配（首个耗时 map 条目），不做任何 IO。
     */
    fun markStartupStage(name: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (startupStages.putIfAbsent(name, now) == null) {
            startupOrder.add(name)
        }
    }

    /**
     * 首帧后调用一次：把全部阶段（相对 [startLaunch] 起点的 ms）打到 logcat，release 亦可读。
     * 内部用 AtomicBoolean 保证**整个启动周期只输出一次**，避免 draw 回调反复刷屏。
     * **不要**在组合循环体 / drawWithContent 每帧调用（含字符串构建分配）。
     */
    fun dumpStartupStages() {
        val base = launchStarted.get()
        if (base == 0L || !startupDumped.compareAndSet(false, true)) return
        val sb = StringBuilder("cold-start stages (ms since launch):")
        for (name in startupOrder.toList()) {
            val n = startupStages[name] ?: continue
            sb.append('\n').append("  ").append(name).append('=').append((n - base) / 1_000_000)
        }
        // 若已通过 markStartupStage("first_frame") 记录，则上面的循环已输出，避免重复键。
        val drawn = startupDrawn.get()
        if (drawn > 0L && !startupStages.containsKey("first_frame")) {
            sb.append('\n').append("  first_frame=").append((drawn - base) / 1_000_000)
        }
        android.util.Log.i(STARTUP_TAG, sb.toString())
    }

    /** 启动各阶段的绝对时间（elapsedRealtimeNanos），按记录顺序返回，供调试面板读取。 */
    fun startupStagesNanos(): Map<String, Long> =
        startupOrder.toList().associateWith { startupStages[it] ?: 0L }
}