package com.yunian.ai.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-9 背景解码分档（max = 2000 + RGB_565）的**对抗性回归**（QA 独立验证，纯 JVM）。
 *
 * 目的：把 `BACKGROUND_CROP_SOURCE_MAX_DIMENSION = 2000` 的关键语义与「最坏内存」钉死，
 * 防止后续有人「顺手把 2000 改成 2400 / 3000」而不自知会引入内存倒退。
 *
 * 核心事实（本测试断言的全部依据）：
 *  - [calcSampleSizeForMaxDimension] 保证「采样后长边 ∈ [max, 2*max)」，即 **S 是使
 *    `长边 / S < 2*max` 成立的最小 2 的幂**；由此**解码后每个维度恒 < 2*max**，
 *    故解码像素数上界 = `(2*max)^2`，RGB_565 内存上界 = `(2*max)^2 * 2` 字节。
 *  - 因此 max=2000 的**真实最坏值约 32MB**（近 4000×4000 方图），
 *    常见 4K（3840×2160）即 **16.6MB**——**并非**交付说明里写的「≤12MB」。
 */
class BackgroundDecodeTierQaTest {

    private fun sample(w: Int, h: Int, max: Int) = calcSampleSizeForMaxDimension(w, h, max)
    private fun decoded(w: Int, h: Int, max: Int) = sample(w, h, max).let { s -> w / s to h / s }
    private fun mb565(w: Int, h: Int) = w.toLong() * h.toLong() * 2L / 1_000_000.0

    // ---------- 用户核心诉求：1080×2400（本机截图）不得缩水 ----------

    @Test
    fun fx9_nativeScreenshot_notDownsampled_at2000() {
        // 1080×2400 是 Redmi Note 12 Pro 的截图尺寸；max=2000 必须走 sample=1（原生）。
        assertEquals(1, sample(1080, 2400, 2000))
        assertEquals(1080 to 2400, decoded(1080, 2400, 2000))
    }

    @Test
    fun fx9_nativeScreenshot_wouldShrink_at1200() {
        // 若退回旧的 1200 上限，1080×2400 会被采样为 540×1200 —— 这正是用户要消除的「缩水」。
        assertEquals(2, sample(1080, 2400, 1200))
        assertEquals(540 to 1200, decoded(1080, 2400, 1200))
    }

    // ---------- 交付说明里三行样例必须逐位复现 ----------

    @Test
    fun fx9_specTableRows_reproduced() {
        assertEquals(2, sample(4000, 3000, 2000))
        assertEquals(2000 to 1500, decoded(4000, 3000, 2000))

        assertEquals(2, sample(6000, 4000, 2000))
        assertEquals(3000 to 2000, decoded(6000, 4000, 2000))
    }

    // ---------- 偏离依据：max=2400 的确更差（非单调），deviation 成立 ----------

    @Test
    fun fx9_why2400Rejected_isNonMonotonic() {
        // max=2400：4000×3000 命中 sample=1 → 原生 4000×3000（565 ≈ 24MB）……
        assertEquals(1, sample(4000, 3000, 2400))
        // ……反而比更大的 6000×4000（sample=2 → 3000×2000 → 565 ≈ 12MB）更高，自相矛盾。
        assertEquals(2, sample(6000, 4000, 2400))
        assertTrue(
            "max=2400 下 4000×3000 的 565 内存必须高于 6000×4000（非单调 = 拒绝 2400 的依据）",
            mb565(4000, 3000) > mb565(3000, 2000)
        )
    }

    // ---------- 对抗：max=2000 的真实最坏值远高于交付说明的 12MB ----------

    @Test
    fun adversarial_4K_isAWorstCaseHole_because3840lt4000() {
        // 4K（3840×2160 或竖版 2160×3840，极常见壁纸）长边 3840 < 2*2000=4000 → sample=1 → **原生**。
        assertEquals(1, sample(3840, 2160, 2000))
        assertEquals(1, sample(2160, 3840, 2000))
        // 单张解码内存 16.6MB，显著高于交付说明声称的 ≤12MB。
        assertTrue(mb565(3840, 2160) > 12.0)
        assertEquals(16.5888, mb565(3840, 2160), 1e-6)
        // 与 12MP 相机（4000×3000 → sample=2 → 6MB）对比：更小的图反而更耗内存（同样的非单调）。
        assertTrue(mb565(3840, 2160) > mb565(2000, 1500))
    }

    @Test
    fun adversarial_globalBound_is32MB_not12MB() {
        // 任何 max：解码后每个维度恒 < 2*max → 像素 < (2*max)^2。max=2000 → 上界 ≈ 32MB。
        val max = 2000
        val dims = listOf(
            1080 to 2400, 4000 to 3000, 6000 to 4000, 8000 to 6000,
            3840 to 2160, 3999 to 3999, 3996 to 2248, 3500 to 2000, 3000 to 2000
        )
        var worstMb = 0.0
        for ((w, h) in dims) {
            val (dw, dh) = decoded(w, h, max)
            assertTrue("维度 $dw 必须 < 2*max", dw < 2 * max)
            assertTrue("维度 $dh 必须 < 2*max", dh < 2 * max)
            worstMb = maxOf(worstMb, mb565(dw, dh))
        }
        assertTrue("实测最坏 $worstMb MB 必须达到 ~16MB 量级（证伪 ≤12MB 说法）", worstMb > 16.0)
        // (2*max)^2*2 的理论上界：
        assertEquals(32.0, mb565(2 * max, 2 * max), 1e-6)
    }

    @Test
    fun adversarial_theoreticalWorstJustUnder2maxSquare() {
        // 近 4000×4000 的方图在 max=2000 下不降采样（sample=1），565 ≈ 32MB —— 全局最坏点。
        assertEquals(1, sample(3999, 3999, 2000))
        assertEquals(3999 to 3999, decoded(3999, 3999, 2000))
        assertTrue(mb565(3999, 3999) > 31.0)
    }

    // ---------- 建议值对照（若要把最坏值压到 ~13MB 且不损失本机原生） ----------

    @Test
    fun recommendation_1400_keeps2400Native_andBoundsMemory() {
        val max = 1400
        // 仍不缩水本机截图：
        assertEquals(1, sample(1080, 2400, max))
        // 4K 被压到 1920×1080（565 ≈ 4.15MB）：
        assertEquals(2, sample(3840, 2160, max))
        assertEquals(1920 to 1080, decoded(3840, 2160, max))
        // 最坏（近 2*max=2800 的方图）565 ≈ 15.7MB，显著低于 2000 的 ~32MB：
        assertTrue(mb565(2 * max, 2 * max) < 16.0)
        assertTrue(mb565(2 * max, 2 * max) < mb565(4000, 4000))
    }

    @Test
    fun invariant_longEdgeStaysInHalfOpenRange() {
        // [max, 2*max) 语义：长边采样后严格 < 2*max，且（若原长边 >= max）>= max。
        val max = 2000
        val cases = listOf(2400 to 1080, 4000 to 3000, 6000 to 4000, 3840 to 2160, 8000 to 6000)
        for ((w, h) in cases) {
            val s = sample(w, h, max)
            val longEdge = maxOf(w, h) / s
            assertTrue("长边 $longEdge 必须 < ${2 * max}", longEdge < 2 * max)
            if (maxOf(w, h) >= max) {
                assertTrue("长边 $longEdge 必须 >= $max", longEdge >= max)
            }
        }
    }
}
