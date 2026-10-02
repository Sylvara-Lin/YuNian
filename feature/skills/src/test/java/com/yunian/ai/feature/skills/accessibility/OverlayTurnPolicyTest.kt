package com.yunian.ai.feature.skills.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayTurnPolicy] 单测：覆盖「回合内空闲兜底」判定与阈值契约——
 * 回合仍在进行却长时间无活动更新 → 强制隐藏；回合已结束则不适用（走正常 linger 路径）。
 */
class OverlayTurnPolicyTest {

    @Test
    fun `active turn idle beyond timeout forces hide`() {
        assertTrue(
            OverlayTurnPolicy.shouldForceHideForIdle(
                turnActive = true,
                elapsedSinceLastActivityMs = OverlayTurnPolicy.IDLE_TIMEOUT_MS + 1,
            ),
        )
    }

    @Test
    fun `active turn exactly at timeout forces hide (boundary)`() {
        assertTrue(
            OverlayTurnPolicy.shouldForceHideForIdle(
                turnActive = true,
                elapsedSinceLastActivityMs = OverlayTurnPolicy.IDLE_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `active turn within timeout does not hide`() {
        assertFalse(
            OverlayTurnPolicy.shouldForceHideForIdle(
                turnActive = true,
                elapsedSinceLastActivityMs = OverlayTurnPolicy.IDLE_TIMEOUT_MS - 1,
            ),
        )
    }

    @Test
    fun `inactive turn never forces idle hide even if long idle`() {
        // 回合已结束：隐藏由 linger 路径负责，与空闲兜底无关
        assertFalse(
            OverlayTurnPolicy.shouldForceHideForIdle(
                turnActive = false,
                elapsedSinceLastActivityMs = OverlayTurnPolicy.IDLE_TIMEOUT_MS * 10,
            ),
        )
    }

    @Test
    fun `thresholds are within agreed ranges`() {
        // linger 落在「800ms~1.5s」建议区间
        assertTrue(OverlayTurnPolicy.LINGER_MS in 800L..1_500L)
        // 空闲兜底 90s
        assertTrue(OverlayTurnPolicy.IDLE_TIMEOUT_MS == 90_000L)
    }
}
