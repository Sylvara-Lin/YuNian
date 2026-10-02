package com.yunian.ai.feature.skills.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayA11yPolicy] 单测：锁定悬浮窗「从无障碍树彻底移除」这一硬约束，
 * 并锁定无障碍重要级别常量与 `android.view.View` 平台的数值契约。
 *
 * 说明：这里不实例化 `View`（JVM 单测无 Android 运行时），而是对策略的纯函数与常量断言；
 * 视图侧只在构造时写入 [OverlayA11yPolicy.REQUIRED_IMPORTANT_FOR_ACCESSIBILITY] 一处，
 * 因此本测试即锁定「写入值正确且被判定为隐藏」的完整契约。
 */
class OverlayA11yPolicyTest {

    @Test
    fun `required level removes window from accessibility tree`() {
        assertEquals(OverlayA11yPolicy.NO_HIDE_DESCENDANTS, OverlayA11yPolicy.REQUIRED_IMPORTANT_FOR_ACCESSIBILITY)
        assertTrue(
            "悬浮窗的必需重要级别必须被判定为已隐藏",
            OverlayA11yPolicy.isHiddenFromAccessibility(OverlayA11yPolicy.REQUIRED_IMPORTANT_FOR_ACCESSIBILITY),
        )
    }

    @Test
    fun `no and no-hide-descendants are hidden`() {
        assertTrue(OverlayA11yPolicy.isHiddenFromAccessibility(OverlayA11yPolicy.NO))
        assertTrue(OverlayA11yPolicy.isHiddenFromAccessibility(OverlayA11yPolicy.NO_HIDE_DESCENDANTS))
    }

    @Test
    fun `auto and yes remain visible`() {
        assertFalse(OverlayA11yPolicy.isHiddenFromAccessibility(OverlayA11yPolicy.AUTO))
        assertFalse(OverlayA11yPolicy.isHiddenFromAccessibility(OverlayA11yPolicy.YES))
    }

    @Test
    fun `constants match android view accessibility contract`() {
        // 与 android.view.View.IMPORTANT_FOR_ACCESSIBILITY_* 的数值一一对应（minSdk 26 起稳定）
        assertEquals(0, OverlayA11yPolicy.AUTO)
        assertEquals(1, OverlayA11yPolicy.YES)
        assertEquals(2, OverlayA11yPolicy.NO)
        assertEquals(4, OverlayA11yPolicy.NO_HIDE_DESCENDANTS)
    }
}
