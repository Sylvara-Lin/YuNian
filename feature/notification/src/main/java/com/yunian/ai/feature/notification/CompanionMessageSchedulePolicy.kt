package com.yunian.ai.feature.notification

/**
 * 主动消息「周期任务」调度策略。
 *
 * 纯函数、无 Android 依赖，便于单元测试（对齐本仓库 `AutomationSchedulePolicy`
 * 的「策略对象 + 单元测试」模式）。作用是把期望的重复间隔与弹性窗口，
 * 约束到 WorkManager 对周期性任务的硬性下限之上，避免注册出非法的
 * `PeriodicWorkRequest`（WorkManager 要求周期任务最小间隔 15 分钟、
 * 最小弹性窗口 5 分钟）。
 */
object CompanionMessageSchedulePolicy {

    /** WorkManager 周期任务允许的最小重复间隔（分钟）。 */
    const val MIN_PERIODIC_INTERVAL_MINUTES: Long = 15L

    /** WorkManager 周期任务允许的最小弹性窗口（分钟）。 */
    const val MIN_PERIODIC_FLEX_MINUTES: Long = 5L

    /** 默认弹性窗口占重复间隔的比例分母（即 interval / 3）。 */
    private const val FLEX_DIVISOR: Long = 3L

    /** 一次周期调度的合法区间（单位：分钟）。 */
    data class Interval(
        val intervalMinutes: Long,
        val flexMinutes: Long,
    )

    /**
     * 把任意间隔 / 弹性窗口约束到合法区间：
     * 间隔不小于 [MIN_PERIODIC_INTERVAL_MINUTES]，弹性窗口不小于
     * [MIN_PERIODIC_FLEX_MINUTES] 且不超过间隔本身。
     *
     * @param intervalMinutes 期望的重复间隔（分钟）
     * @param flexMinutes 期望的弹性窗口（分钟）
     * @return 经下限约束后的合法区间
     */
    fun sanitizePeriodicInterval(intervalMinutes: Long, flexMinutes: Long): Interval {
        val safeInterval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
        val safeFlex = flexMinutes
            .coerceAtLeast(MIN_PERIODIC_FLEX_MINUTES)
            .coerceAtMost(safeInterval)
        return Interval(safeInterval, safeFlex)
    }

    /**
     * 首次调度时决定间隔：显式启用且合法（不小于最小周期）时使用自定义值，
     * 否则回退到最小周期；弹性窗口按 1/3 计算后再做达标约束。
     *
     * @param useCustomInterval 是否启用自定义间隔
     * @param customIntervalMinutes 自定义间隔（分钟）
     * @return 经达标约束后的合法区间
     */
    fun initialInterval(useCustomInterval: Boolean, customIntervalMinutes: Long): Interval {
        val interval = if (useCustomInterval && customIntervalMinutes >= MIN_PERIODIC_INTERVAL_MINUTES) {
            customIntervalMinutes
        } else {
            MIN_PERIODIC_INTERVAL_MINUTES
        }
        return sanitizePeriodicInterval(interval, interval / FLEX_DIVISOR)
    }

    /**
     * 按亲密度给出周期区间：亲密度越高，间隔越短（下限仍是 WorkManager 最小值）。
     * [randomMinuteSelector] 用于在 `[min, max]` 之间挑选一个具体分钟数，
     * 抽离随机源以便测试注入确定性实现。
     *
     * @param intimacy 亲密度（越大越亲密）
     * @param randomMinuteSelector 在 `[min, max]` 内取值的随机选择器
     * @return 经达标约束后的合法区间
     */
    fun forIntimacy(
        intimacy: Int,
        randomMinuteSelector: (min: Long, max: Long) -> Long,
    ): Interval {
        val (min, max) = intervalRangeForIntimacy(intimacy)
        val chosen = randomMinuteSelector(min, max).coerceIn(min, max)
        return sanitizePeriodicInterval(chosen, chosen / FLEX_DIVISOR)
    }

    /** 亲密度 → `[最小间隔, 最大间隔]`（分钟）的映射。 */
    private fun intervalRangeForIntimacy(intimacy: Int): Pair<Long, Long> = when {
        intimacy >= 80 -> MIN_PERIODIC_INTERVAL_MINUTES to 30L
        intimacy >= 50 -> 30L to 60L
        intimacy >= 20 -> 60L to 120L
        else -> 120L to 360L
    }
}
