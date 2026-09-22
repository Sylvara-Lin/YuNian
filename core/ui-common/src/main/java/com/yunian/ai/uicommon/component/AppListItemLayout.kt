package com.yunian.ai.uicommon.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.yunian.ai.uicommon.theme.AppDimens

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AppListItemLayout(
    isStartAligned: Boolean,
    startSlot: @Composable () -> Unit,
    endSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    slotGap: Dp = AppDimens.AvatarGap,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    val gestureModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                )
                onLongClick?.invoke()
            },
            interactionSource = null,
            indication = null
        )
    } else {
        Modifier
    }

    Layout(
        modifier = modifier,
        content = {
            Box(modifier = Modifier.layoutId("startSlot")) { startSlot() }
            Box(modifier = Modifier.layoutId("endSlot")) { endSlot() }
            Box(modifier = Modifier.layoutId("content").then(gestureModifier)) { content() }
        }
    ) { measurables, constraints ->
        val startMeasurable = measurables.first { it.layoutId == "startSlot" }
        val endMeasurable = measurables.first { it.layoutId == "endSlot" }
        val contentMeasurable = measurables.first { it.layoutId == "content" }

        val startPlaceable = startMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val endPlaceable = endMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gapPx = slotGap.roundToPx()

        val startGapPx = if (startPlaceable.width > 0) gapPx else 0
        val endGapPx = if (endPlaceable.width > 0) gapPx else 0

        val reservedWidth = startPlaceable.width + startGapPx +
            endPlaceable.width + endGapPx
        val contentMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - reservedWidth).coerceAtLeast(0)
        } else {
            constraints.maxWidth
        }

        val contentPlaceable = contentMeasurable.measure(
            Constraints(
                minWidth = 0,
                maxWidth = contentMaxWidth,
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
        )

        val height = maxOf(startPlaceable.height, contentPlaceable.height, endPlaceable.height)
        val measuredWidth = reservedWidth + contentPlaceable.width
        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            measuredWidth.coerceAtLeast(constraints.minWidth)
        }

        layout(layoutWidth, height) {
            if (isStartAligned) {

                startPlaceable.placeRelative(0, 0)
                contentPlaceable.placeRelative(
                    startPlaceable.width + startGapPx,
                    0
                )
                endPlaceable.placeRelative(
                    layoutWidth - endPlaceable.width,
                    0
                )
            } else {

                val startX = layoutWidth - startPlaceable.width
                val contentX = startX - startGapPx - contentPlaceable.width
                endPlaceable.placeRelative(0, 0)
                contentPlaceable.placeRelative(
                    contentX.coerceAtLeast(endPlaceable.width + endGapPx),
                    0
                )
                startPlaceable.placeRelative(
                    startX,
                    0
                )
            }
        }
    }
}
