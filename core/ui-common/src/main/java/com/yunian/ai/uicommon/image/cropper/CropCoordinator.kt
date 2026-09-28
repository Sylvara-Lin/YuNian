package com.yunian.ai.uicommon.image.cropper

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yunian.ai.uicommon.image.engine.ImageTransform
import com.yunian.ai.uicommon.image.engine.TransformState

class CropCoordinator(
    private val cropRect: Rect
) {

    fun processTransform(raw: TransformState): TransformState {
        return ImageTransform.clampToBounds(raw, cropRect)
    }

    fun crop(bitmap: ImageBitmap, state: TransformState): Bitmap {
        return ImageTransform.cropBitmap(bitmap, state, cropRect)
    }

    fun cropToImageBitmap(bitmap: ImageBitmap, state: TransformState): ImageBitmap {
        return crop(bitmap, state).asImageBitmap()
    }
}
