
package com.yunian.ai.uicommon.component.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import com.yunian.ai.uicommon.component.glass.DampedDragAnimation
import com.yunian.ai.uicommon.component.glass.InteractiveHighlight
import com.yunian.ai.uicommon.theme.PinkPrimary
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

internal data class CompactLensMaskBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal fun compactLensMaskBounds(
    containerWidth: Float,
    containerHeight: Float,
    contentPadding: Float,
    tabWidth: Float,
    lensPosition: Float,
    selectionHeight: Float,
    scaleX: Float,
    scaleY: Float,
    isLtr: Boolean,
    overscan: Float = 0f,
): CompactLensMaskBounds {
    val unscaledLeft =
        if (isLtr) {
            contentPadding + lensPosition * tabWidth
        } else {
            containerWidth -
                contentPadding -
                (lensPosition + 1f) * tabWidth
        }
    val width = tabWidth * scaleX + overscan * 2f
    val height = selectionHeight * scaleY + overscan * 2f
    return CompactLensMaskBounds(
        left = unscaledLeft + (tabWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(ContinuousCapsule)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop?,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    containerHeight: Dp = 64.dp,
    contentPadding: Dp = 4.dp,
    showSelectionShadow: Boolean = true,
    captureTabContent: Boolean = true,
    useBackdrop: Boolean = true,
    // 拖拽触发阈值：单击时手指微移不应进入拖拽模式（二选一开关建议 32.dp）
    dragTouchSlopDp: Dp = 16.dp,
    content: @Composable RowScope.() -> Unit
) {
    val accentColor = PinkPrimary
    val compact = containerHeight <= 48.dp

    val containerColor =
        if (isDark) {
            Color(0xFF121212).copy(if (compact) 0.20f else 0.14f)
        } else {
            Color(0xFFFAFAFA).copy(if (compact) 0.20f else 0.14f)
        }

    val effectiveBackdrop = useBackdrop && backdrop != null

    val tabsBackdrop = if (captureTabContent) rememberLayerBackdrop() else null

    val selectionHeight = containerHeight - contentPadding * 2
    val outerLensRadius = 24.dp
    val selectionLensRadius = 10.dp
    val selectionChromaticRadius = 14.dp

    val animationScope = rememberCoroutineScope()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val tabWidthState = remember { mutableFloatStateOf(0f) }
    val offsetAnimation = remember { Animatable(0f) }
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    var currentIndex by remember(selectedTabIndex) {
        mutableIntStateOf(selectedTabIndex())
    }
    val touchSlop = with(LocalDensity.current) { dragTouchSlopDp.toPx() }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedTabIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStarted = {},
            onDragStopped = {
                val targetIndex =
                    targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                val previousIndex = currentIndex
                currentIndex = targetIndex
                if (targetIndex != previousIndex) {
                    currentOnTabSelected(targetIndex)
                }
                animateToValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                val tw = tabWidthState.floatValue
                if (tw > 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tw * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                }
                animationScope.launch {
                    offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                }
            },
            consumeDragChanges = true,
            consumeInitialDown = false,
            touchSlop = touchSlop
        )
    }
    LaunchedEffect(selectedTabIndex) {
        snapshotFlow { selectedTabIndex() }
            .collectLatest { index -> currentIndex = index }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { index ->
                dampedDragAnimation.animateToValue(index.toFloat())
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .height(containerHeight)
            .then(dampedDragAnimation.modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - (contentPadding * 2).toPx()) / tabsCount
        }
        LaunchedEffect(tabWidth) { tabWidthState.floatValue = tabWidth }

        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction =
                    (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width -
                                (dampedDragAnimation.value + 0.5f) * tabWidth +
                                panelOffset
                        },
                        size.height / 2f
                    )
                }
            )
        }

        val containerModifier = Modifier
            .graphicsLayer {
                translationX = panelOffset
            }
            .then(
                if (effectiveBackdrop) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop!!,
                        shape = { ContinuousCapsule },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(outerLensRadius.toPx(), outerLensRadius.toPx())
                        },
                        layerBlock = {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .border(
                        width = 0.8.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                        shape = ContinuousCapsule
                    )
                } else {
                    Modifier
                        .background(containerColor, ContinuousCapsule)
                        .border(
                            width = 0.8.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                            shape = ContinuousCapsule
                        )
                }
            )
            .then(interactiveHighlight.modifier)
            .height(containerHeight)
            .fillMaxWidth()

        val combinedSelectionBackdrop = if (tabsBackdrop != null) {
            rememberCombinedBackdrop(backdrop ?: tabsBackdrop, tabsBackdrop)
        } else {
            backdrop
        }

        val selectionModifier = Modifier
            .padding(horizontal = contentPadding)
            .offset {
                IntOffset(
                    x = if (isLtr) {
                        (dampedDragAnimation.value * tabWidth + panelOffset).toInt()
                    } else {
                        (constraints.maxWidth.toFloat() -
                            (dampedDragAnimation.value + 1f) * tabWidth +
                            panelOffset).toInt()
                    },
                    y = 0
                )
            }
            .then(interactiveHighlight.gestureModifier)
            .then(
                if (effectiveBackdrop) {
                    Modifier.drawBackdrop(
                        backdrop = combinedSelectionBackdrop!!,
                        shape = { ContinuousCapsule },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                selectionLensRadius.toPx() * progress,
                                selectionChromaticRadius.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        shadow = if (showSelectionShadow) {
                            {
                                val progress = dampedDragAnimation.pressProgress
                                Shadow(alpha = progress)
                            }
                        } else {
                            null
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 8.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /=
                                1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *=
                                1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawBehind = {
                            drawRect(Color.Red, blendMode = BlendMode.Clear)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                if (isDark) {
                                    Color.White.copy(0.1f)
                                } else {
                                    Color.Black.copy(0.1f)
                                },
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                } else {
                    Modifier
                        .border(
                            width = 1.5.dp,
                            color = accentColor.copy(alpha = 0.6f),
                            shape = ContinuousCapsule
                        )
                }
            )
            .height(selectionHeight)
            .fillMaxWidth(1f / tabsCount)

        if (compact) {
            Box(selectionModifier)
            Box(containerModifier)
            Row(
                Modifier
                    .graphicsLayer {
                        translationX = panelOffset
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val velocity = dampedDragAnimation.velocity / 10f
                        val maskScaleX =
                            dampedDragAnimation.scaleX /
                                (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                        val maskScaleY =
                            dampedDragAnimation.scaleY *
                                (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                        val mask = compactLensMaskBounds(
                            containerWidth = size.width,
                            containerHeight = size.height,
                            contentPadding = contentPadding.toPx(),
                            tabWidth = tabWidth,
                            lensPosition = dampedDragAnimation.value,
                            selectionHeight = selectionHeight.toPx(),
                            scaleX = maskScaleX,
                            scaleY = maskScaleY,
                            isLtr = isLtr,
                            overscan = 1.5.dp.toPx(),
                        )
                        drawRoundRect(
                            color = Color.Transparent,
                            topLeft = Offset(mask.left, mask.top),
                            size = Size(mask.width, mask.height),
                            cornerRadius = CornerRadius(mask.height / 2f),
                            blendMode = BlendMode.Clear
                        )
                    }
                    .height(containerHeight)
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        } else {
            Row(
                containerModifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
            Box(selectionModifier)
        }

        if (tabsBackdrop != null) {
            CompositionLocalProvider(
                LocalLiquidBottomTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                }
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .then(
                            if (effectiveBackdrop) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop!!,
                                    shape = { ContinuousCapsule },
                                    effects = {
                                        val progress = dampedDragAnimation.pressProgress
                                        vibrancy()
                                        blur(8.dp.toPx())
                                        lens(
                                            outerLensRadius.toPx() * progress,
                                            outerLensRadius.toPx() * progress
                                        )
                                    },
                                    highlight = {
                                        val progress = dampedDragAnimation.pressProgress
                                        Highlight.Default.copy(alpha = progress)
                                    },
                                    onDrawSurface = { drawRect(containerColor) }
                                )
                            } else {
                                Modifier.background(containerColor)
                            }
                        )
                        .then(interactiveHighlight.modifier)
                        .height(selectionHeight)
                        .fillMaxWidth()
                        .padding(horizontal = contentPadding)
                        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }
    }
}
