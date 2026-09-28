package com.yunian.ai.uicommon.component

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// 果冻入场的统一调参入口。语义 = 起始「横向压扁 + 纵向拉长 + 略微下沉」→ 欠阻尼弹簧回弹到原尺寸
// → 过冲时形变反向（变宽变矮）→ 收敛到 1。形变量都用 graphicsLayer 做，不参与布局。
private const val JELLY_DAMPING = 0.45f      // 阻尼比，越小越"duang"（0.4~0.55 之间调）
private const val JELLY_STIFFNESS = 420f     // 刚度，越大越快收住
private const val JELLY_SQUASH_X = 0.14f     // 起始横向压缩量（p=0 时 scaleX = 1 - 0.14 = 0.86）
private const val JELLY_STRETCH_Y = 0.22f    // 起始纵向拉伸量（p=0 时 scaleY = 1 + 0.22 = 1.22）
private val JELLY_RISE = 8.dp                // 起始下沉量，弹回 0（用 dp 保证不同密度观感一致）

/**
 * 消息气泡「果冻」入场：从横向压缩 + 纵向拉伸的状态，用欠阻尼弹簧回弹到原尺寸，
 * 过冲时形变反向（变宽变矮），形成 duang 的抖动感，最后收敛到 1。
 *
 * - 用 graphicsLayer 做形变，**不改变布局尺寸**，所以不会引起 LazyColumn 重新布局 / 滚动抖动。
 * - 只在组件首次组合时播一次（下方 remember 锁存 play），之后的重组、滚动回收不会重播。
 * - 系统「关闭动画」（开发者选项动画缩放 0 或无障碍「移除动画」）时自动退化为无动画。
 *
 * @param play 该次组合是否应播放入场动画；只在首次组合时被采样（锁存），避免同帧二次组合把动画掐断。
 * @param enabled 是否启用（低端机可由上层整体关掉）。
 * @param transformOrigin 形变基点：一般取气泡「根部」那一侧的下角，让它从小角长出来。
 */
@Composable
fun Modifier.jellyEntrance(
    play: Boolean,
    enabled: Boolean = true,
    transformOrigin: TransformOrigin = TransformOrigin(0.5f, 1f),
): Modifier {
    val shouldPlay = remember { play }
    if (!enabled || !shouldPlay || !ValueAnimator.areAnimatorsEnabled()) return this
    val progress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = JELLY_DAMPING, stiffness = JELLY_STIFFNESS),
        )
    }
    return this.graphicsLayer {
        // progress: 0 → 1，过冲时短暂 > 1；d = progress - 1 于是从 -1 → 0 → 正。
        val d = progress.value - 1f
        scaleX = 1f + d * JELLY_SQUASH_X    // p=0: 0.86（横向压扁）；过冲 d>0: 变宽
        scaleY = 1f - d * JELLY_STRETCH_Y   // p=0: 1.22（纵向拉长）；过冲 d>0: 变矮
        translationY = -d * JELLY_RISE.toPx()   // 起始下沉一点，弹回来
        alpha = (progress.value * 5f).coerceIn(0f, 1f)  // 前 20% 快速淡入，避免"啪"地出现
        this.transformOrigin = transformOrigin
    }
}
