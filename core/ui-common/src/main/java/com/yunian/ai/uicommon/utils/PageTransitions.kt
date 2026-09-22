package com.yunian.ai.uicommon.utils

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.yunian.ai.common.HardwareInfo

object PageTransitions {

    private const val DURATION_ENTER = 350

    private const val DURATION_EXIT = 280

    private const val DURATION_SIMPLE = 180

    private const val SLIDE_FRACTION = 0.28f

    /**
     * 进入转场时长（毫秒）：与 [enterTransition] 使用的时长严格一致；LOW 档无转场返回 0。
     *
     * 供 ChatScreen 判定「转场结束」的时机（T01 打点 / T04 把重活延后到转场之后）。
     * 只读取 [HardwareInfo.tier] 的缓存值（由应用启动时的 [HardwareInfo.warmUp] 预热填充），
     * 自身**不触发**冷探测。
     */
    fun enterDurationMillis(): Int = when (HardwareInfo.tier) {
        HardwareInfo.Tier.LOW -> 0
        HardwareInfo.Tier.MEDIUM -> DURATION_SIMPLE
        else -> DURATION_ENTER
    }

    fun enterTransition(): EnterTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return EnterTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_ENTER
        val easing = FastOutSlowInEasing

        return slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            initialOffsetX = { (it * SLIDE_FRACTION).toInt() }
        ) + fadeIn(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }

    fun exitTransition(): ExitTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return ExitTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_EXIT
        val easing = FastOutSlowInEasing

        return slideOutHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            targetOffsetX = { -(it * SLIDE_FRACTION).toInt() }
        ) + fadeOut(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }

    fun popEnterTransition(): EnterTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return EnterTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_ENTER
        val easing = FastOutSlowInEasing

        return slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            initialOffsetX = { -(it * SLIDE_FRACTION).toInt() }
        ) + fadeIn(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }

    fun popExitTransition(): ExitTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return ExitTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_EXIT
        val easing = FastOutSlowInEasing

        return slideOutHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            targetOffsetX = { (it * SLIDE_FRACTION).toInt() }
        ) + fadeOut(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }
}
