package com.yunian.ai.common.perf

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADPF（Performance Hint API）封装。
 *
 * 语义：API 31+ 且系统服务可用时创建真实 hint session，向系统声明
 * 「关键线程 + 目标工作时长 + 实际时长」，由系统（高通 WALT / MTK fpsgo 等）决定
 * 提频与摆核；其余情况（API < 31、服务缺失、机型不兼容）安全降级为 no-op，
 * **绝不影响功能、绝不抛异常**。
 *
 * 设计约束（对齐 Android 官方 ADPF 指引）：
 * - tids 必须是 **Linux native thread-id**，即 [Process.myTid]；
 *   **不能**用 `Thread.getId()`（那是 JVM 线程 id，ADPF 无法识别）。
 * - 主线程 tid 在 [init]（运行于主线程）阶段用 [Process.myTid] 一次性捕获。
 * - 应用**不自行设置 CPU 亲和性**，只提交 hint，把摆核/提频决策交还系统。
 * - 所有系统调用逐个 `runCatching` 包裹：部分机型即使 API 31+ 也会抛
 *   `UnsupportedOperationException` / 返回 null，必须吞掉并降级。
 * - 纯逻辑（[isSupportedOn] / [frameIntervalNanosForHz] / [clampTargetNanos] /
 *   [sanitizeActualNanos]）不依赖 Android 框架，可在 JVM 单测直接验证。
 */
object PerfBoost {

    private const val TAG = "PerfBoost"

    /** ADPF（Performance Hint API）起始 API level（Android 12 / S）。 */
    private const val MIN_API_LEVEL = Build.VERSION_CODES.S // 31

    /** 帧间隔换算 / 兜底基准：60Hz。 */
    private const val DEFAULT_REFRESH_RATE_HZ = 60f

    /** 合理刷新率区间；越界视为异常输入，回落 60Hz。 */
    private const val MIN_REFRESH_RATE_HZ = 30f
    private const val MAX_REFRESH_RATE_HZ = 240f

    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** target 时长取整夹取区间：1ms ~ 1s（避免 0/负值/离谱值使 createHintSession 抛异常）。 */
    internal const val MIN_TARGET_NANOS = 1_000_000L
    internal const val MAX_TARGET_NANOS = 1_000_000_000L

    /** 应用上下文（仅 applicationContext，不持有 Activity，无泄漏）。 */
    @Volatile
    private var appContext: Context? = null

    /** 主线程 native tid（[init] 在主线程调用时捕获；0 表示未捕获）。 */
    @Volatile
    private var mainThreadTid: Int = 0

    private val initialized = AtomicBoolean(false)

    /** 系统服务惰性探测 + 缓存：SDK < 31 或服务缺失 → null（即不支持）。 */
    private val hintManager: PerformanceHintManager? by lazy { obtainManager() }

    /**
     * 注入应用上下文，幂等。应在 Application.onCreate 早期调用一次。
     * 该回调运行在主线程，因此可顺带捕获主线程 native tid（ADPF 需要 native tid）。
     *
     * @param context 任意 Context；内部只保留 applicationContext。
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        // Application.onCreate 运行在主线程：此处可安全取得主线程的 native tid。
        mainThreadTid = runCatching { Process.myTid() }.getOrDefault(0)
        // 预热探测：避免首次建 session 时才去取系统服务（全部 runCatching 包裹）。
        runCatching { hintManager }
    }

    /** 是否可用（SDK >= 31 且系统服务非空）；结果缓存，避免每次反射/取服务。 */
    val isSupported: Boolean
        get() = isSupportedOn(Build.VERSION.SDK_INT, hintManager != null)

    /** 纯逻辑，便于单测：给定 API level 与「服务是否可用」判定。 */
    fun isSupportedOn(apiLevel: Int, serviceAvailable: Boolean): Boolean =
        apiLevel >= MIN_API_LEVEL && serviceAvailable

    /**
     * 创建 hint session；不支持时返回 no-op 实现（不分配任何系统资源）。
     *
     * @param tag 仅用于日志定位（如 "chat-stream" / "local-llm" / "tts-synth"）。
     * @param targetWorkDurationNanos 目标工作时长（ns）；内部会夹取到合法区间。
     * @param threadIds 关键线程的 **native tid**（见 [withMainThread] / [currentThreadId]）。
     */
    fun createSession(tag: String, targetWorkDurationNanos: Long, threadIds: IntArray): Session {
        val manager = hintManager
        val validTids = threadIds.filter { it > 0 }.toIntArray()
        if (manager == null || validTids.isEmpty()) return NoopSession
        val target = clampTargetNanos(targetWorkDurationNanos)
        return runCatching {
            val real = manager.createHintSession(validTids, target)
            if (real == null) NoopSession else RealSession(real)
        }.getOrElse { error ->
            // 部分机型即使 API 31+ 也可能抛 UnsupportedOperationException / IllegalArgumentException：
            // 必须吞掉并降级，绝不影响功能。
            Log.w(TAG, "createHintSession($tag) failed, fallback to no-op: ${error.message}")
            NoopSession
        }
    }

    /**
     * 主线程 tid（native）+ 指定线程 tid，去重。
     * 主线程 tid 优先取 [init] 阶段捕获值；兜底见 [resolveMainThreadTid]。
     */
    fun withMainThread(extraThreadId: Int? = null): IntArray {
        val ids = LinkedHashSet<Int>(2)
        val mainTid = resolveMainThreadTid()
        if (mainTid > 0) ids.add(mainTid)
        if (extraThreadId != null && extraThreadId > 0) ids.add(extraThreadId)
        return ids.toIntArray()
    }

    /** 当前线程的 Linux native tid（ADPF 要求 native tid，不能用 `Thread.id`）。 */
    fun currentThreadId(): Int = runCatching { Process.myTid() }.getOrDefault(0)

    /** 由屏幕刷新率推导帧间隔（ns），取不到时回落 60Hz。 */
    fun frameIntervalNanos(context: Context): Long {
        val refreshHz = runCatching { resolveRefreshRateHz(context) }
            .getOrDefault(DEFAULT_REFRESH_RATE_HZ)
        return frameIntervalNanosForHz(refreshHz)
    }

    // ---- 纯逻辑（不依赖 Android 框架，可在 JVM 单测直接验证）--------------------------------

    /**
     * 帧间隔换算：refreshHz → ns。
     * 异常 / 越界输入（<=0、NaN、Infinity、超出 [MIN_REFRESH_RATE_HZ, MAX_REFRESH_RATE_HZ]）
     * 一律回落 60Hz，保证调用方拿到合法值。
     */
    internal fun frameIntervalNanosForHz(refreshHz: Float): Long {
        val hz = if (refreshHz.isFinite() &&
            refreshHz >= MIN_REFRESH_RATE_HZ &&
            refreshHz <= MAX_REFRESH_RATE_HZ
        ) {
            refreshHz
        } else {
            DEFAULT_REFRESH_RATE_HZ
        }
        // 用 Double 运算避免 Float 精度导致 ±1ns 偏差。
        return (NANOS_PER_SECOND.toDouble() / hz.toDouble()).toLong()
    }

    /** target 时长取整夹取，避免 0 / 负值 / 离谱值令 createHintSession 抛异常。 */
    internal fun clampTargetNanos(nanos: Long): Long = when {
        nanos < MIN_TARGET_NANOS -> MIN_TARGET_NANOS
        nanos > MAX_TARGET_NANOS -> MAX_TARGET_NANOS
        else -> nanos
    }

    /** reportActual 轻量保护：忽略 <= 0 的异常值（返回 null 表示不上报）。 */
    internal fun sanitizeActualNanos(actualNanos: Long): Long? =
        if (actualNanos > 0L) actualNanos else null

    // ---- Android 框架依赖 -------------------------------------------------------------------

    private fun obtainManager(): PerformanceHintManager? {
        if (Build.VERSION.SDK_INT < MIN_API_LEVEL) return null
        val ctx = appContext ?: return null
        return runCatching { ctx.getSystemService(PerformanceHintManager::class.java) }.getOrNull()
    }

    /**
     * 解析主线程 native tid：
     * 1) 优先返回 [init] 阶段在主线程捕获的 tid；
     * 2) 若当前线程即主线程，直接取 [Process.myTid]；
     * 3) 否则尽力返回 Looper 的 JVM 线程 id（可能非 native tid → 上层会降级为 no-op）。
     */
    private fun resolveMainThreadTid(): Int {
        val cached = mainThreadTid
        if (cached > 0) return cached
        return runCatching {
            val mainThread = Looper.getMainLooper()?.thread ?: return@runCatching -1
            if (mainThread === Thread.currentThread()) Process.myTid() else mainThread.id.toInt()
        }.getOrDefault(-1)
    }

    private fun resolveRefreshRateHz(context: Context): Float {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                ?.defaultDisplay
        }
        return display?.refreshRate ?: DEFAULT_REFRESH_RATE_HZ
    }

    // ---- Session 实现 -----------------------------------------------------------------------

    /** 一次性能提示会话；不支持的机型返回 [NoopSession]（全部方法为空操作）。 */
    interface Session {
        /** 上报上一周期实际工作时长（ns）。binder 调用，频率克制：每帧 / 每个流事件一次即可。 */
        fun reportActual(actualNanos: Long)

        /** 更新目标工作时长（ns）。 */
        fun updateTarget(targetNanos: Long)

        /** 关闭会话（幂等）。 */
        fun close()
    }

    private object NoopSession : Session {
        override fun reportActual(actualNanos: Long) = Unit
        override fun updateTarget(targetNanos: Long) = Unit
        override fun close() = Unit
    }

    private class RealSession(
        private val delegate: PerformanceHintManager.Session,
    ) : Session {

        private val closed = AtomicBoolean(false)

        override fun reportActual(actualNanos: Long) {
            if (closed.get()) return
            val sanitized = sanitizeActualNanos(actualNanos) ?: return
            runCatching { delegate.reportActualWorkDuration(sanitized) }
        }

        override fun updateTarget(targetNanos: Long) {
            if (closed.get()) return
            runCatching { delegate.updateTargetWorkDuration(clampTargetNanos(targetNanos)) }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { delegate.close() }
        }
    }
}
