package com.yunian.ai.uicommon.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bounceVerticalScroll(
    resistance: Float = 0.35f,
    maxOverscrollDp: Float = 96f
): Modifier {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val maxPx = with(density) { maxOverscrollDp.dp.toPx() }
    val overscroll = remember { Animatable(0f) }

    val connection = remember(resistance, maxPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = overscroll.value
                if (current == 0f || available.y == 0f) return Offset.Zero

                if (current.sign != available.y.sign) {
                    val consumed = available.y.coerceIn(
                        minOf(0f, -current),
                        maxOf(0f, -current)
                    )
                    scope.launch { overscroll.snapTo(current + consumed) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                val next = (overscroll.value + available.y * resistance)
                    .coerceIn(-maxPx, maxPx)
                val delta = next - overscroll.value
                if (abs(delta) > 0.5f) {
                    scope.launch { overscroll.snapTo(next) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll.value != 0f) {
                    overscroll.animateTo(
                        0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (overscroll.value != 0f) {
                    overscroll.animateTo(
                        0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .then(
            Modifier.offsetY { overscroll.value }
        )
}

private fun Modifier.offsetY(y: () -> Float): Modifier =
    this.graphicsLayer { translationY = y() }
