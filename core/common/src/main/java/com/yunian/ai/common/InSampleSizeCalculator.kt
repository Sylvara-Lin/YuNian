package com.yunian.ai.common

/**
 * 位图降采样率计算（纯函数，无 android.* 依赖，JVM 可测）——**单一真值来源**。
 *
 * [SampledBitmapDecoder]（裁剪输入）与 [StickerManager]（贴纸网格 / 气泡）原先各自实现了一份
 * 等价逻辑（`calcSampleSizeForMaxDimension` / `calcInSampleSize`），现统一收口到此处，
 * 避免两处漂移。
 *
 * 语义：计算 2 的幂 `inSampleSize`，使降采样后的长边落在 `[maxDimension, 2*maxDimension)` 区间
 * （`inSampleSize` 只能取 2 的幂）。
 *
 * 守卫（修 FIX-7）：`maxDimension <= 0` 时，原实现 `width / (sampleSize * 2) >= maxDimension`
 * 对 `maxDimension == 0` **恒为真** → `sampleSize` 无限翻倍 → **死循环**（踩一下就卡死主 / IO 线程）。
 * 采用 `coerceAtLeast(1)` 将非法上限钳到 1：
 *  - 保持函数**全函数性**（任何输入都返回，不抛异常，不新增异常路径）；
 *  - 对合法输入（>0）行为与原实现**逐位一致**（默认 512 / 1200，永远命中此分支）；
 *  - 终止性可证：`safeMax >= 1`，`sampleSize *= 2` 后 `width/(2s)` 终将 < 1。
 */
fun calcSampleSizeForMaxDimension(width: Int, height: Int, maxDimension: Int): Int {
    val safeMaxDimension = maxDimension.coerceAtLeast(1)
    var sampleSize = 1
    while (
        width / (sampleSize * 2) >= safeMaxDimension ||
        height / (sampleSize * 2) >= safeMaxDimension
    ) {
        sampleSize *= 2
    }
    return sampleSize
}
