package com.yunian.ai.feature.skills.accessibility

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.yunian.ai.agent.activity.AiActivityPhase
import com.yunian.ai.agent.activity.AiActivityState
import com.yunian.ai.domain.ToolFriendlyNames

/**
 * AI 活动悬浮窗视图：状态圆点 + 中文友好工具名 +（可选）参数摘要。
 *
 * **为何用纯 View 而非 ComposeView**：悬浮窗挂在**系统 WindowManager** 上，不在 Activity
 * 视图树里，Compose 需要手动注入 `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner`，
 * 且 Activity 销毁时 Compose 宿主生命周期随之中断——对一个「常驻、频繁增删」的小窗而言，
 * 纯 View 的生命周期完全由我们掌控，更稳、更轻。（代价：样式需手写，与 App 主题解耦。）
 *
 * **无障碍**：构造时即把根视图设为
 * [OverlayA11yPolicy.REQUIRED_IMPORTANT_FOR_ACCESSIBILITY]（`NO_HIDE_DESCENDANTS`），
 * 使本窗连同子树从无障碍树中彻底移除——否则 AI 的读屏/按文本点击会命中自家悬浮窗，
 * 造成自我污染与「自我锁死」。本类**不依赖主题**，颜色内联，保证在任何应用前台都清晰可读。
 */
internal class AiActivityOverlayView(context: Context) : LinearLayout(context) {

    private val statusDot: View
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = dp(12)
        val padV = dp(8)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(BG_COLOR)
            setStroke(dp(1), STROKE_COLOR)
        }

        // 从无障碍树彻底移除（含子树）——见类注释。
        importantForAccessibility = OverlayA11yPolicy.REQUIRED_IMPORTANT_FOR_ACCESSIBILITY

        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_RUNNING)
            }
        }
        addView(statusDot, LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        titleView = TextView(context).apply {
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = GONE
        }
        textColumn.addView(titleView)
        textColumn.addView(subtitleView)
        addView(
            textColumn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                // 限制最大宽度，避免长参数把悬浮窗撑满中间区域（team 要求「贴边、不挡中间」）
                width = dp(220)
            },
        )
    }

    /** 按最新活动刷新内容（必须在主线程调用）。 */
    fun render(state: AiActivityState) {
        val phase = state.phase
        val (dotColor, statusText) = when (phase) {
            AiActivityPhase.RUNNING -> COLOR_RUNNING to "正在"
            AiActivityPhase.DONE -> COLOR_DONE to "完成"
            AiActivityPhase.FAILED -> COLOR_FAILED to "失败"
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
        titleView.text = "$statusText · ${ToolFriendlyNames.of(state.toolName)}"
        val context = state.argsSummary.takeIf { it.isNotBlank() && it != "{}" }
        if (context != null) {
            subtitleView.text = context
            subtitleView.visibility = VISIBLE
        } else {
            subtitleView.text = null
            subtitleView.visibility = GONE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private companion object {
        /** 半透明深色药丸：在浅色/深色任何界面之上都清晰可读。 */
        const val BG_COLOR = 0xE61C1C22.toInt()
        const val STROKE_COLOR = 0x33FFFFFF
        const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        const val TEXT_SECONDARY = 0xB3FFFFFF.toInt()
        const val COLOR_RUNNING = 0xFF4C8DFF.toInt()
        const val COLOR_DONE = 0xFF34C759.toInt()
        const val COLOR_FAILED = 0xFFFF3B30.toInt()
    }
}
