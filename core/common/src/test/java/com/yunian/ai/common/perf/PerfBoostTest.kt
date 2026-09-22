package com.yunian.ai.common.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PerfBoost 纯逻辑单测（JVM），不触碰任何 Android 框架 API。
 * 覆盖：支持判定、帧间隔换算、target 夹取、reportActual 保护。
 */
class PerfBoostTest {

    // ---- isSupportedOn ---------------------------------------------------------------

    @Test
    fun `API 30 不支持`() {
        assertFalse(PerfBoost.isSupportedOn(30, serviceAvailable = true))
    }

    @Test
    fun `API 31 且服务可用时支持`() {
        assertTrue(PerfBoost.isSupportedOn(31, serviceAvailable = true))
    }

    @Test
    fun `API 31 但服务缺失时不支持`() {
        assertFalse(PerfBoost.isSupportedOn(31, serviceAvailable = false))
    }

    @Test
    fun `更高 API 且服务可用时支持`() {
        assertTrue(PerfBoost.isSupportedOn(34, serviceAvailable = true))
        assertFalse(PerfBoost.isSupportedOn(34, serviceAvailable = false))
        // 边界：更低版本即便服务存在也不支持
        assertFalse(PerfBoost.isSupportedOn(0, serviceAvailable = true))
    }

    // ---- 帧间隔换算 -------------------------------------------------------------------

    @Test
    fun `帧间隔换算 60Hz`() {
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(60f))
    }

    @Test
    fun `帧间隔换算 90Hz`() {
        assertEquals(11_111_111L, PerfBoost.frameIntervalNanosForHz(90f))
    }

    @Test
    fun `帧间隔换算 120Hz`() {
        assertEquals(8_333_333L, PerfBoost.frameIntervalNanosForHz(120f))
    }

    @Test
    fun `异常刷新率回落 60Hz`() {
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(0f))
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(-1f))
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(Float.NaN))
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(Float.POSITIVE_INFINITY))
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(Float.NEGATIVE_INFINITY))
        // 越界（< MIN / > MAX）同样回落
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(1f))
        assertEquals(16_666_666L, PerfBoost.frameIntervalNanosForHz(1000f))
    }

    // ---- target 夹取 ------------------------------------------------------------------

    @Test
    fun `target 取整夹取`() {
        assertEquals(PerfBoost.MIN_TARGET_NANOS, PerfBoost.clampTargetNanos(0L))
        assertEquals(PerfBoost.MIN_TARGET_NANOS, PerfBoost.clampTargetNanos(-5L))
        assertEquals(16_666_666L, PerfBoost.clampTargetNanos(16_666_666L))
        assertEquals(PerfBoost.MAX_TARGET_NANOS, PerfBoost.clampTargetNanos(Long.MAX_VALUE))
    }

    // ---- reportActual 保护 -------------------------------------------------------------

    @Test
    fun `reportActual 异常值保护`() {
        assertNull(PerfBoost.sanitizeActualNanos(0L))
        assertNull(PerfBoost.sanitizeActualNanos(-1L))
        assertEquals(1L, PerfBoost.sanitizeActualNanos(1L))
        assertEquals(80_000_000L, PerfBoost.sanitizeActualNanos(80_000_000L))
    }
}
