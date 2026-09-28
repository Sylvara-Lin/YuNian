package com.yunian.ai.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug1 回归测试：temperature 写 JSON 前必须取整为 2 位小数，杜绝 Float→Double 二进制伪影。
 *
 * 根因备忘：
 *   0.85f.toDouble() = 0.8500000238418579  → 网关「限制小数点[2]位」直接拒绝（识图必现）
 *   0.7f.toDouble()  = 0.699999988079071
 */
class ApiTemperatureTest {

    /** toString() 小数点后的位数（无小数点返回 0）。 */
    private fun decimalPlaces(value: Double): Int {
        val s = value.toString()
        val dot = s.indexOf('.')
        return if (dot < 0) 0 else s.length - dot - 1
    }

    @Test
    fun `Float 配置取整为两位小数 - 常见值`() {
        assertEquals(0.85, 0.85f.toApiTemperature(), 1e-12)
        assertEquals(0.7, 0.7f.toApiTemperature(), 1e-12)
        assertEquals(0.1, 0.1f.toApiTemperature(), 1e-12)
        assertEquals(1.5, 1.5f.toApiTemperature(), 1e-12)
        assertEquals(1.0, 1.0f.toApiTemperature(), 1e-12)
    }

    @Test
    fun `越界值按上下界钳制后取整`() {
        // 2.0f 超上界 1.5 → 钳到 1.5
        assertEquals(1.5, 2.0f.toApiTemperature(), 1e-12)
        // 0.0f 低于下界 0.1 → 钳到 0.1
        assertEquals(0.1, 0.0f.toApiTemperature(), 1e-12)
    }

    @Test
    fun `直接 toDouble 的伪影被助手抹平`() {
        // 复现原始 bug 值：Float→Double 带伪影
        assertEquals(0.8500000238418579, 0.85f.toDouble(), 1e-15)
        assertEquals(0.699999988079071, 0.7f.toDouble(), 1e-15)
        // 经助手后回到干净的两小数
        assertEquals(0.85, 0.85f.toDouble().toApiTemperature(), 1e-12)
        assertEquals(0.7, 0.7f.toDouble().toApiTemperature(), 1e-12)
    }

    @Test
    fun `Double 助手同样只保留两位小数`() {
        assertEquals(0.3, 0.3.toApiTemperature(), 1e-12)
        assertEquals(0.0, 0.0.toApiTemperature(), 1e-12)
        assertEquals(1.0, 0.7.toApiTemperature() + 0.3.toApiTemperature(), 1e-12)
    }

    @Test
    fun `回归护栏 - 输出字符串小数位不超过两位`() {
        val samples = listOf(0.85f, 0.7f, 0.1f, 1.5f, 1.0f, 2.0f, 0.33f, 1.234f, 0.0f)
        for (f in samples) {
            val result = f.toApiTemperature()
            assertTrue(
                "Float $f -> $result 小数位应 ≤ 2",
                decimalPlaces(result) <= 2
            )
        }
        val doubleSamples = listOf(0.3, 0.8500000238418579, 0.699999988079071, 0.0, 1.5)
        for (d in doubleSamples) {
            val result = d.toApiTemperature()
            assertTrue(
                "Double $d -> $result 小数位应 ≤ 2",
                decimalPlaces(result) <= 2
            )
        }
    }
}
