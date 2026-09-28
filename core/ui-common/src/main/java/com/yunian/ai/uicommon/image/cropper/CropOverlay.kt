package com.yunian.ai.uicommon.image.cropper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap

@Composable
fun CropOverlay(
    cropRect: Rect,
    modifier: Modifier = Modifier,
    maskColor: Color = Color.Black.copy(alpha = 0.5f),
    frameColor: Color = Color.White,
    frameStrokeWidth: Float = 2f,
    cornerLength: Float = 28f,
    cornerStrokeWidth: Float = 3f,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawMaskWithHole(cropRect, maskColor)
        drawRect(
            color = frameColor,
            topLeft = cropRect.topLeft,
            size = cropRect.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = frameStrokeWidth)
        )
        drawCorners(cropRect, cornerLength, cornerStrokeWidth, frameColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaskWithHole(
    hole: Rect,
    maskColor: Color
) {
    val path = Path().apply {
        addRect(Rect(0f, 0f, size.width, size.height))
        addRect(hole)
        fillType = PathFillType.EvenOdd
    }
    drawPath(path, maskColor)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCorners(
    rect: Rect,
    length: Float,
    strokeWidth: Float,
    color: Color
) {
    val l = length

    drawLine(color, Offset(rect.left, rect.top + l), Offset(rect.left, rect.top), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.left, rect.top), Offset(rect.left + l, rect.top), strokeWidth, cap = StrokeCap.Round)

    drawLine(color, Offset(rect.right - l, rect.top), Offset(rect.right, rect.top), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.right, rect.top), Offset(rect.right, rect.top + l), strokeWidth, cap = StrokeCap.Round)

    drawLine(color, Offset(rect.left, rect.bottom - l), Offset(rect.left, rect.bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.left, rect.bottom), Offset(rect.left + l, rect.bottom), strokeWidth, cap = StrokeCap.Round)

    drawLine(color, Offset(rect.right - l, rect.bottom), Offset(rect.right, rect.bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - l), strokeWidth, cap = StrokeCap.Round)
}
