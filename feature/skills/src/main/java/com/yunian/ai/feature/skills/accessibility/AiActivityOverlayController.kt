package com.yunian.ai.feature.skills.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import com.yunian.ai.agent.activity.AiActivityBus
import com.yunian.ai.agent.activity.AiActivityState
import com.yunian.ai.agent.activity.AiTurnState
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「AI 正在控制手机」悬浮窗控制器（**回合级**生命周期）。
 *
 * **免权限方案**：窗口类型用 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]——
 * 由无障碍服务添加，**不需要 `SYSTEM_ALERT_WINDOW`**、也不受 Android 12+/14+ 后台启动限制，
 * 用户既然已开启「予念助手控制服务」即天然可用。
 *
 * **不会自我锁死**：窗口带 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]
 * （触摸穿透到下层 App，AI 的 `screen_tap` 不会打到悬浮窗）+ [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]
 * （不抢焦点/输入法）；且视图设 `NO_HIDE_DESCENDANTS` 从无障碍树移除（见 [AiActivityOverlayView]）。
 *
 * **回合级显示时机**（Task #4，改自「单工具级」）：
 * - 订阅 `AiActivityBus.state`（回合 id + 当前活动的原子快照）；
 * - 本回合**首次**出现工具活动时显示；已显示则只刷新内容，**不重复 addView**（复用同一窗口）；
 * - 回合进行中，工具之间的空档**保持显示**（内容保留上一次动作的终态）——避免「弹→消失→再弹」的抖动；
 * - 仅在**回合结束**（`state.turnId` 由非空变 null）后保留 [OverlayTurnPolicy.LINGER_MS] 再隐藏。
 *
 * **兜底（防永久滞留）**：
 * - 回合内每有一次活动更新就重置**看门狗**；若 [OverlayTurnPolicy.IDLE_TIMEOUT_MS] 内无任何更新
 *   且回合仍未结束 → 强制隐藏 + `SecureLog.w`（见 [resetWatchdog]）；
 * - `YuNianAccessibilityService.onUnbind` → [stop]（先移除窗口，避免 token 泄漏）；
 * - `onInterrupt` → [hideNow]；
 * - 进程被杀：窗口随进程销毁，无需额外处理。
 */
internal class AiActivityOverlayController(
    private val service: YuNianAccessibilityService,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var windowView: AiActivityOverlayView? = null
    private var added = false

    /** 回合结束后的延迟隐藏任务。 */
    private var lingerJob: Job? = null

    /** 空闲兜底看门狗任务。 */
    private var watchdogJob: Job? = null

    /** 上一次观察到的回合 id（用于识别「回合结束」跃迁）。 */
    private var lastTurnId: Long? = null

    /** 最后一次活动更新时间（供看门狗触发时计算空闲时长）。 */
    private var lastActivityAtMs = 0L

    private var started = false

    /** 开始订阅活动总线；幂等（重复调用不重复订阅）。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            AiActivityBus.state.collect { onState(it) }
        }
    }

    /** 停止订阅并移除窗口；幂等；必须先于窗口 token 失效（服务断开）执行，避免窗口泄漏。 */
    fun stop() {
        if (!started) {
            // 即便未订阅也确保无残留窗口
            removeWindow()
            return
        }
        started = false
        cancelTimers()
        runCatching { scope.cancel() }
            .onFailure { SecureLog.w(TAG, "scope cancel failed: ${it.message}") }
        removeWindow()
        lastTurnId = null
    }

    /** 立即隐藏（不移除订阅）：用于无障碍服务 `onInterrupt`——系统要求中断时先撤窗，避免残留。 */
    fun hideNow() {
        cancelTimers()
        removeWindow()
    }

    // region 内部：回合状态 → 窗口

    private fun onState(state: AiTurnState) {
        val turnId = state.turnId
        if (turnId == null) {
            // 回合结束（或初始态）：仅当刚刚从「进行中」跃迁过来才安排延迟隐藏
            if (lastTurnId != null) {
                cancelWatchdog()
                scheduleLingerHide()
            }
            lastTurnId = null
            return
        }
        // 回合进行中
        if (lastTurnId != turnId) {
            // 新回合开始：取消上一次的延迟隐藏与看门狗基准
            lingerJob?.cancel()
            lingerJob = null
            cancelWatchdog()
        }
        lastTurnId = turnId

        val activity = state.activity ?: return // 回合已开始但尚无工具活动：等首个 RUNNING
        lastActivityAtMs = System.currentTimeMillis()
        showAndRender(activity)
        resetWatchdog()
    }

    private fun showAndRender(state: AiActivityState) {
        val view = windowView ?: AiActivityOverlayView(service).also { windowView = it }
        view.render(state)
        if (!added) {
            val params = buildLayoutParams()
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    added = true
                    SecureLog.d(TAG, "overlay added: tool=${state.toolName} phase=${state.phase} turn=${state.turnId}")
                }
                .onFailure {
                    // BadTokenException/IllegalStateException：服务正在断开等，静默降级（不影响 AI 执行）
                    SecureLog.w(TAG, "overlay add failed: ${it.javaClass.simpleName} ${it.message}")
                }
        }
    }

    private fun scheduleLingerHide() {
        lingerJob?.cancel()
        lingerJob = scope.launch {
            delay(OverlayTurnPolicy.LINGER_MS)
            removeWindow()
        }
    }

    private fun resetWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(OverlayTurnPolicy.IDLE_TIMEOUT_MS)
            val elapsed = System.currentTimeMillis() - lastActivityAtMs
            if (OverlayTurnPolicy.shouldForceHideForIdle(
                    turnActive = AiActivityBus.state.value.turnId != null,
                    elapsedSinceLastActivityMs = elapsed,
                )
            ) {
                SecureLog.w(
                    TAG,
                    "回合内 ${elapsed}ms 无活动更新且未收到 endTurn，强制隐藏悬浮窗（疑似回合未正常结束）",
                )
                removeWindow()
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun cancelTimers() {
        lingerJob?.cancel()
        lingerJob = null
        cancelWatchdog()
    }

    private fun removeWindow() {
        val view = windowView ?: return
        if (added) {
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure {
                    // IllegalArgumentException（已移除）/ BadTokenException（token 失效）
                    if (it !is IllegalArgumentException && it !is BadTokenException) {
                        SecureLog.w(TAG, "overlay remove failed: ${it.message}")
                    }
                }
            added = false
            SecureLog.d(TAG, "overlay removed")
        }
        windowView = null
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // ★ 硬性要求：触摸穿透 + 不抢焦点。缺一不可：
            //   - NOT_TOUCHABLE：AI 的 screen_tap 坐标若落在悬浮窗上也会穿透到下层，避免自我锁死；
            //   - NOT_FOCUSABLE ：不打断用户当前输入/不抢 IME。
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 贴顶部边缘居中：避让屏幕中心（AI 操作与用户视线的主要区域），不遮挡内容主体。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = TOP_MARGIN_DP * service.resources.displayMetrics.density.toInt()
        }

    // endregion

    private companion object {
        private const val TAG = "AiActivityOverlay"

        /** 距顶部边缘的间距（dp）：大致避让状态栏。 */
        private const val TOP_MARGIN_DP = 48
    }
}
