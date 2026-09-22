package com.yunian.ai.network

import kotlin.math.roundToInt

/**
 * temperature 序列化助手（core:network 内部可见）。
 *
 * 背景（Bug1 根因）：`ApiConfig.temperature` 是 [Float]，代码里把它 `.toDouble()` 后写进 JSON，
 * Float → Double 会引入二进制精度伪影：
 * ```
 * 0.85f.toDouble() = 0.8500000238418579
 * 0.7f.toDouble()  = 0.699999988079071
 * ```
 * 网关（如 Clove API）限制「小数点 2 位」时会直接拒绝整个请求：
 * 「API调用失败：temperature参数非法：限制小数点[2]位」。因此**只有**走到带
 * `safeTemp.toDouble()` 的路径（聊天/工具/视觉识图）才会稳定报错，识图用户实测必现。
 *
 * 修法：先放大 100 倍取整、再除以 100，得到干净的 2 位小数 [Double]（如 0.85f → 0.85）。
 * 所有把 temperature 写进 JSON 的点都必须经由此助手，避免再次引入伪影。
 */

/**
 * [Float] 配置 → 网关可接受的 2 位小数 temperature。
 *
 * @param min 下界（默认与既有 `coerceIn(0.1f, 1.5f)` 行为一致，避免“重置更激进”）。
 * @param max 上界。
 */
internal fun Float.toApiTemperature(min: Float = 0.1f, max: Float = 1.5f): Double =
    (coerceIn(min, max) * 100f).roundToInt() / 100.0

/**
 * [Double] 形态 temperature 的兜底取整（如固定传入的 0.0 / 0.7 / 0.3 等）。
 *
 * 与 [Float.toApiTemperature] 保持同一语义：仅保留 2 位小数，防止调用方传入
 * 由 Float 派生出的 Double（或其它带伪影的值）时再次触发网关校验失败。
 */
internal fun Double.toApiTemperature(): Double =
    (this * 100.0).roundToInt() / 100.0
