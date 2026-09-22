package com.yunian.ai.uicommon.image.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import com.yunian.ai.uicommon.image.engine.TransformState

@Composable
fun AtomicImageViewer(
    bitmap: ImageBitmap,
    transform: TransformState,
    latestTransform: () -> TransformState,
    onTransformRequest: (TransformState) -> Unit,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onDoubleTap: ((Offset) -> Unit)? = null,
) {

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(onTap, onDoubleTap) {
                if (onTap == null && onDoubleTap == null) return@pointerInput
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { offset -> onDoubleTap?.invoke(offset) }
                )
            }
            .pointerInput(minScale, maxScale) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val current = latestTransform()
                    val oldScale = current.scale
                    val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)

                    val imageX = (centroid.x - current.offsetX) / oldScale
                    val imageY = (centroid.y - current.offsetY) / oldScale
                    val newOffsetX = centroid.x - newScale * imageX + pan.x
                    val newOffsetY = centroid.y - newScale * imageY + pan.y

                    onTransformRequest(
                        current.copy(
                            scale = newScale,
                            offsetX = newOffsetX,
                            offsetY = newOffsetY,
                        )
                    )
                }
            }
    ) {
        withTransform({
            translate(left = transform.offsetX, top = transform.offsetY)
            scale(transform.scale, transform.scale, Offset.Zero)
        }) {
            drawImage(bitmap, topLeft = Offset.Zero)
        }
    }
}
