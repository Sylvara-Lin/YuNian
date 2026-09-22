package com.yunian.ai.uicommon.image.engine

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageTransform {

    fun mapRect(state: TransformState, imageRect: Rect): Rect {
        val left = state.offsetX + imageRect.left * state.scale
        val top = state.offsetY + imageRect.top * state.scale
        val right = state.offsetX + imageRect.right * state.scale
        val bottom = state.offsetY + imageRect.bottom * state.scale
        return Rect(left, top, right, bottom)
    }

    fun inverseMapRect(state: TransformState, viewportRect: Rect): Rect {
        val left = (viewportRect.left - state.offsetX) / state.scale
        val top = (viewportRect.top - state.offsetY) / state.scale
        val right = (viewportRect.right - state.offsetX) / state.scale
        val bottom = (viewportRect.bottom - state.offsetY) / state.scale
        return Rect(left, top, right, bottom)
    }

    fun clampToBounds(state: TransformState, cropRect: Rect): TransformState {
        val imgRect = mapRect(state, Rect(0f, 0f, state.imageSize.width, state.imageSize.height))
        var dx = 0f
        var dy = 0f

        if (imgRect.width >= cropRect.width) {
            if (imgRect.left > cropRect.left) {
                dx = cropRect.left - imgRect.left
            } else if (imgRect.right < cropRect.right) {
                dx = cropRect.right - imgRect.right
            }
        } else {

            dx = cropRect.center.x - imgRect.center.x
        }

        if (imgRect.height >= cropRect.height) {
            if (imgRect.top > cropRect.top) {
                dy = cropRect.top - imgRect.top
            } else if (imgRect.bottom < cropRect.bottom) {
                dy = cropRect.bottom - imgRect.bottom
            }
        } else {
            dy = cropRect.center.y - imgRect.center.y
        }

        return if (dx == 0f && dy == 0f) state
        else state.copy(offsetX = state.offsetX + dx, offsetY = state.offsetY + dy)
    }

    fun fitToCropRect(imageSize: Size, viewportSize: Size, cropRect: Rect): TransformState {
        val scale = max(
            cropRect.width / imageSize.width,
            cropRect.height / imageSize.height
        )
        val scaledW = imageSize.width * scale
        val scaledH = imageSize.height * scale
        val offsetX = cropRect.left + (cropRect.width - scaledW) / 2f
        val offsetY = cropRect.top + (cropRect.height - scaledH) / 2f
        return TransformState(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            imageSize = imageSize,
            viewportSize = viewportSize,
        )
    }

    fun fitInside(imageSize: Size, viewportSize: Size): TransformState {
        if (imageSize.width <= 0f || imageSize.height <= 0f ||
            viewportSize.width <= 0f || viewportSize.height <= 0f
        ) {
            return TransformState(imageSize = imageSize, viewportSize = viewportSize)
        }
        val scale = min(
            viewportSize.width / imageSize.width,
            viewportSize.height / imageSize.height
        )
        val scaledW = imageSize.width * scale
        val scaledH = imageSize.height * scale
        val offsetX = (viewportSize.width - scaledW) / 2f
        val offsetY = (viewportSize.height - scaledH) / 2f
        return TransformState(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            imageSize = imageSize,
            viewportSize = viewportSize,
        )
    }

    fun clampToViewport(state: TransformState): TransformState {
        val viewport = Rect(0f, 0f, state.viewportSize.width, state.viewportSize.height)
        if (viewport.width <= 0f || viewport.height <= 0f) return state
        return clampToBounds(state, viewport)
    }

    fun cropBitmap(bitmap: ImageBitmap, state: TransformState, cropRect: Rect): Bitmap {
        val androidBitmap = bitmap.asAndroidBitmap()
        val srcRect = inverseMapRect(state, cropRect)
        val srcX = srcRect.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val srcY = srcRect.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val srcW = srcRect.width.roundToInt()
            .coerceIn(1, bitmap.width - srcX)
        val srcH = srcRect.height.roundToInt()
            .coerceIn(1, bitmap.height - srcY)
        val dstW = cropRect.width.roundToInt().coerceAtLeast(1)
        val dstH = cropRect.height.roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(
            Bitmap.createBitmap(androidBitmap, srcX, srcY, srcW, srcH),
            dstW, dstH, true
        )
    }
}
