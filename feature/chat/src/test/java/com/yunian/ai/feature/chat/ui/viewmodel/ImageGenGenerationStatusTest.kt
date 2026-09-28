package com.yunian.ai.feature.chat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图等待动画的进程级状态契约。
 *
 * 真机问题（2026-09-28）：退出会话再进来，等待气泡的秒数从 0 重算，而后台生图仍在跑。
 * 根因是计时起点取自「composable 进入组装的时刻」。本用例锁死新的契约：
 * 起点必须是**第一次 markStarted 的真实时间**，重复 markStarted（重入页面 / 重复触发）不得重置。
 */
class ImageGenGenerationStatusTest {

    @Test
    fun markStartedRecordsStartOnceAndNeverResetsWhileActive() {
        ImageGenGenerationStatus.markFinished(7L)
        assertNull(ImageGenGenerationStatus.startedAtMs.value[7L])

        ImageGenGenerationStatus.markStarted(7L)
        val firstStart = ImageGenGenerationStatus.startedAtMs.value[7L]
        assertTrue("markStarted 必须记录开始时间", firstStart != null)

        // 模拟「退出页面再进入」/ 重复触发：不得重置起点
        Thread.sleep(8)
        ImageGenGenerationStatus.markStarted(7L)
        assertEquals(firstStart, ImageGenGenerationStatus.startedAtMs.value[7L])

        // markFinished 必须同时清掉活动标记与开始时间（否则下次计时会沿用旧起点）
        ImageGenGenerationStatus.markFinished(7L)
        assertFalse(ImageGenGenerationStatus.activeCompanionIds.value.contains(7L))
        assertNull(ImageGenGenerationStatus.startedAtMs.value[7L])
    }

    @Test
    fun activeCompanionIdsTracksConcurrentSessions() {
        ImageGenGenerationStatus.markFinished(8L)
        ImageGenGenerationStatus.markFinished(9L)
        ImageGenGenerationStatus.markStarted(8L)
        ImageGenGenerationStatus.markStarted(9L)
        assertTrue(ImageGenGenerationStatus.activeCompanionIds.value.containsAll(listOf(8L, 9L)))
        ImageGenGenerationStatus.markFinished(8L)
        assertFalse(ImageGenGenerationStatus.activeCompanionIds.value.contains(8L))
        assertTrue(ImageGenGenerationStatus.activeCompanionIds.value.contains(9L))
        ImageGenGenerationStatus.markFinished(9L)
    }
}
