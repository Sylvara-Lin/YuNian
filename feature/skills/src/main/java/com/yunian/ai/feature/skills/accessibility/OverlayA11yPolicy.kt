package com.yunian.ai.feature.skills.accessibility

/**
 * AI 活动悬浮窗的无障碍策略（纯逻辑，可 JVM 单测）。
 *
 * **为何需要它**：悬浮窗由**无障碍服务自身**添加（`TYPE_ACCESSIBILITY_OVERLAY`），若它进入
 * 无障碍树，AI 的 `screen_read`（`readScreenText`）就会把「正在点击微信」这类自家文案读进
 * 屏幕内容、`screen_click_text`（`findAndClick`）也可能误命中悬浮窗节点——即**自我污染 /
 * 自我锁死**。因此悬浮窗根视图必须设置 [REQUIRED_IMPORTANT_FOR_ACCESSIBILITY] 从树中彻底移除。
 *
 * 这里把 `android.view.View.IMPORTANT_FOR_ACCESSIBILITY_*` 的取值固化为纯常量，
 * 让策略可被 JVM 单测直接断言，无需 Robolectric。取值与平台常量一一对应（minSdk 26 起稳定）：
 *  - `IMPORTANT_FOR_ACCESSIBILITY_AUTO` = 0
 *  - `IMPORTANT_FOR_ACCESSIBILITY_YES` = 1
 *  - `IMPORTANT_FOR_ACCESSIBILITY_NO` = 2
 *  - `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` = 4
 */
internal object OverlayA11yPolicy {

    /** == `View.IMPORTANT_FOR_ACCESSIBILITY_AUTO` */
    const val AUTO = 0

    /** == `View.IMPORTANT_FOR_ACCESSIBILITY_YES` */
    const val YES = 1

    /** == `View.IMPORTANT_FOR_ACCESSIBILITY_NO` */
    const val NO = 2

    /** == `View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` */
    const val NO_HIDE_DESCENDANTS = 4

    /**
     * 悬浮窗根视图必须设置的重要级别：**彻底移除（含子树）**。
     * 选 `NO_HIDE_DESCENDANTS` 而非 `NO`：即使某个子节点被显式设为可见，也不会泄漏到树中。
     */
    const val REQUIRED_IMPORTANT_FOR_ACCESSIBILITY = NO_HIDE_DESCENDANTS

    /**
     * 给定 `importantForAccessibility` 级别是否已从无障碍树隐藏。
     * 仅 `NO` 与 `NO_HIDE_DESCENDANTS` 视为隐藏。
     */
    fun isHiddenFromAccessibility(level: Int): Boolean =
        level == NO_HIDE_DESCENDANTS || level == NO
}
