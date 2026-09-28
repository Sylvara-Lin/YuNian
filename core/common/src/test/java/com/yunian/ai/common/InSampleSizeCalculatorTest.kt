package com.yunian.ai.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [calcSampleSizeForMaxDimension] 单测（修 FIX-7）。
 *
 * 核心回归：`maxDimension <= 0` 时旧实现 `width / (sampleSize * 2) >= maxDimension` 对 0 **恒真**
 * → `sampleSize` 无限翻倍 → **死循环**（踩一下就卡死主 / IO 线程）。
 * 现以 `coerceAtLeast(1)` 守卫，本测试用 [Test.timeout] 断言其**必然终止**且返回合法值。
 */
class InSampleSizeCalculatorTest {

    /** maxDimension=0 不得死循环（3s 内必须返回）。 */
    @Test(timeout = 3_000)
    fun zeroMaxDimension_terminates_andReturnsPositive() {
        val sample = calcSampleSizeForMaxDimension(width = 2400, height = 1080, maxDimension = 0)
        assertTrue("采样率必须为正（否则后续除法无意义）", sample >= 1)
    }

    /** maxDimension=-1 不得死循环。 */
    @Test(timeout = 3_000)
    fun negativeMaxDimension_terminates_andReturnsPositive() {
        val sample = calcSampleSizeForMaxDimension(width = 2400, height = 1080, maxDimension = -1)
        assertTrue("采样率必须为正", sample >= 1)
    }

    /** 非法上限（0 / 负数）与合法下限 1 等价：均钳到 1，结果一致且确定。 */
    @Test(timeout = 3_000)
    fun nonPositiveMaxDimension_clampsToSameAsOne() {
        val asOne = calcSampleSizeForMaxDimension(2400, 1080, 1)
        assertEquals(asOne, calcSampleSizeForMaxDimension(2400, 1080, 0))
        assertEquals(asOne, calcSampleSizeForMaxDimension(2400, 1080, -1))
        assertEquals(asOne, calcSampleSizeForMaxDimension(2400, 1080, Int.MIN_VALUE))
    }

    /** 极端尺寸 + 非法上限仍终止（防溢出类死循环）。 */
    @Test(timeout = 3_000)
    fun extremeSize_withZeroMaxDimension_terminates() {
        val sample = calcSampleSizeForMaxDimension(Int.MAX_VALUE, Int.MAX_VALUE, 0)
        assertTrue(sample >= 1)
    }

    // -------- 合法输入行为回归（不得因守卫而改变） --------

    @Test
    fun normalInputs_producePowerOfTwoSampleSize() {
        // 长边 / 采样后落在 [maxDimension, 2*maxDimension)
        assertEquals(2, calcSampleSizeForMaxDimension(2400, 1080, 1200))
        assertEquals(1, calcSampleSizeForMaxDimension(1080, 1080, 1200))
        assertEquals(4, calcSampleSizeForMaxDimension(4800, 2400, 1200))
        assertEquals(4, calcSampleSizeForMaxDimension(4000, 3000, 512))
        assertEquals(1, calcSampleSizeForMaxDimension(500, 500, 512))
    }

    @Test
    fun result_isAlwaysPowerOfTwo() {
        for (max in listOf(1, 512, 1200)) {
            for (dim in listOf(64, 1000, 2400, 4800, 10000)) {
                val sample = calcSampleSizeForMaxDimension(dim, dim, max)
                assertTrue("sampleSize=$sample 必须为 2 的幂", sample > 0 && (sample and (sample - 1)) == 0)
            }
        }
    }
}
