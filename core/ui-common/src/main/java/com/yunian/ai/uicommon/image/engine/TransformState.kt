package com.yunian.ai.uicommon.image.engine

import androidx.compose.ui.geometry.Size

data class TransformState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    val imageSize: Size = Size.Zero,
    val viewportSize: Size = Size.Zero,
)
