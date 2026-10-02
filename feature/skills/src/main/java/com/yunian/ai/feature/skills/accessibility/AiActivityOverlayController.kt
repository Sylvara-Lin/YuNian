package com.yunian.ai.feature.skills.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import com.yunian.ai.agent.activity.AiActivityBus
import com.yunian.ai.agent.activity.AiActivityPhase
import com.yunian.ai.agent.activity.AiActivityState
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「AI 正在控制手机」悬浮窗控制器。
 *
 * **免权限方案**：窗口类型用 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]——
 * 由无障碍服务添加，**不需要 `SYSTEM_ALERT_WINDOW`**、也不受 Android 12+/14+ 后台启动限制，
 * 用户既然已开启「予念助手控制服务」即天然可用。
 *
 * **不会自我锁死**：窗口带 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]
 * （触摸穿透到下层 App，AI 的 `screen_tap` 不会打到悬浮窗）+ [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]
 * （不抢焦点/输入法）；且视图设 `NO_HIDE_DESCENDANTS` 从无障碍树移除（见 [AiActivityOverlayView]）。
 *
 * **显示时机**：订阅 [AiActivityBus.current]——存在 RUNNING 活动即显示；回合内连续多个工具
 * **复用同一窗口**只做刷新；工具进入终态（DONE/FAILED）后再保留 [IDLE_HIDE_MS] 让用户看到结果，
 * 然后隐藏。服务重连时若读到的是**陈旧终态**（早于一个 [IDLE_HIDE_MS] 窗口）则直接忽略，避免闪窗。
 *
 * **生命周期**：由 [YuNianAccessibilityService] 在 `onServiceConnected` 调 [start]、
 * `onUnbind` 调 [stop]。所有 WindowManager 操作在主线程、异常兜底（绝不让异常穿出服务回调）。
 */
internal class AiActivityOverlayController(
    private val service: YuNianAccessibilityService,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var windowView: AiActivityOverlayView? = null
    private var added = false
    private var hideJob: Job? = null
    private var started = false

    /** 开始订阅活动总线；幂等（重复调用不重复订阅）。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            AiActivityBus.current.collect { render(it) }
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
        hideJob?.cancel()
        hideJob = null
        runCatching { scope.cancel() }
            .onFailure { SecureLog.w(TAG, "scope cancel failed: ${it.message}") }
        removeWindow()
    }

    // region 内部：状态 → 窗口

    private fun render(state: AiActivityState?) {
        if (state == null) {
            removeWindow()
            return
        }
        when (state.phase) {
            AiActivityPhase.RUNNING -> {
                hideJob?.cancel()
                hideJob = null
                showAndRender(state)
            }
            AiActivityPhase.DONE, AiActivityPhase.FAILED -> {
                // 陈旧终态（服务重连时读到的旧结果）不展示，避免闪窗；仅呈现新鲜结果
                val age = System.currentTimeMillis() - state.updatedAtMs
                if (age > IDLE_HIDE_MS) {
                    removeWindow()
                    return
                }
                showAndRender(state)
                scheduleHide()
            }
        }
    }

    private fun showAndRender(state: AiActivityState) {
        val view = windowView ?: AiActivityOverlayView(service).also { windowView = it }
        view.render(state)
        if (!added) {
            val params = buildLayoutParams()
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    added = true
                    SecureLog.d(TAG, "overlay added: tool=${state.toolName} phase=${state.phase}")
                }
                .onFailure {
                    // BadTokenException/IllegalStateException：服务正在断开等，静默降级（不影响 AI 执行）
                    SecureLog.w(TAG, "overlay add failed: ${it.javaClass.simpleName} ${it.message}")
                }
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(IDLE_HIDE_MS)
            removeWindow()
        }
    }

    private fun removeWindow() {
        hideJob?.cancel()
        hideJob = null
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
        }
        SecureLog.d(TAG, "overlay removed")
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

        /** 终态保留时长：让用户看清「完成/失败」后自动隐藏。 */
        private const val IDLE_HIDE_MS = 2_500L

        /** 距顶部边缘的间距（dp）：大致避让状态栏。 */
        private const val TOP_MARGIN_DP = 48
    }
}
