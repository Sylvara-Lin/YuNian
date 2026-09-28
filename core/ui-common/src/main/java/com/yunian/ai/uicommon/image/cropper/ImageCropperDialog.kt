package com.yunian.ai.uicommon.image.cropper
import com.yunian.ai.uicommon.icon.AppIcons


import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yunian.ai.uicommon.image.engine.ImageTransform
import com.yunian.ai.uicommon.image.engine.TransformState
import com.yunian.ai.uicommon.image.viewer.AtomicImageViewer
import com.yunian.ai.uicommon.theme.WeChatDarkBackground
import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageCropperDialog(
    bitmap: ImageBitmap,
    cropRatio: Float = 1f,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {

    var containerSize by remember { mutableStateOf(Size.Zero) }

    val cropRect = remember(containerSize, cropRatio) {
        if (containerSize == Size.Zero) {
            Rect.Zero
        } else {
            val maxWidth = containerSize.width * 0.9f
            val maxHeight = containerSize.height * 0.72f
            val cropW: Float
            val cropH: Float
            if (maxWidth / maxHeight > cropRatio) {
                cropH = maxHeight
                cropW = cropH * cropRatio
            } else {
                cropW = maxWidth
                cropH = cropW / cropRatio
            }
            val left = (containerSize.width - cropW) / 2f
            val top = (containerSize.height - cropH) / 2f
            Rect(left, top, left + cropW, top + cropH)
        }
    }

    val initialTransform = remember(bitmap, containerSize, cropRect) {
        if (cropRect == Rect.Zero || containerSize == Size.Zero) {
            TransformState(
                imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                viewportSize = containerSize
            )
        } else {
            ImageTransform.fitToCropRect(
                imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                viewportSize = containerSize,
                cropRect = cropRect
            )
        }
    }

    val coordinator = remember(cropRect) { CropCoordinator(cropRect) }

    var currentTransform by remember(initialTransform) { mutableStateOf(initialTransform) }

    val latestTransform = { currentTransform }

    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {

        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                )
                w.setBackgroundDrawable(ColorDrawable(0xFF1A1216.toInt()))
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WeChatDarkBackground)
                .onSizeChanged { containerSize = it.toSize() }
        ) {

            if (cropRect != Rect.Zero) {
                AtomicImageViewer(
                    bitmap = bitmap,
                    transform = currentTransform,
                    latestTransform = latestTransform,
                    onTransformRequest = { requested ->
                        currentTransform = coordinator.processTransform(requested)
                    },
                    minScale = initialTransform.scale,
                    maxScale = initialTransform.scale * 5f,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (cropRect != Rect.Zero) {
                CropOverlay(
                    cropRect = cropRect,
                    modifier = Modifier.fillMaxSize()
                )
            }

            TopToolbar(
                onCancel = onDismiss,
                onConfirm = {
                    scope.launch {
                        val result = withContext(AppDispatchers.cpu) {
                            coordinator.crop(bitmap, currentTransform)
                        }
                        onConfirm(result)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .zIndex(1f)
            )

            BottomToolbar(
                onReset = {
                    currentTransform = initialTransform
                },
                onRotate = {

                },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .zIndex(1f)
            )
        }
    }
}

@Composable
private fun TopToolbar(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text("取消", color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onConfirm) {
            Icon(
                AppIcons.Check,
                contentDescription = "确认",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun BottomToolbar(
    onReset: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        IconButton(onClick = onRotate) {
            Icon(
                AppIcons.RotateCw,
                contentDescription = "旋转",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        IconButton(onClick = onReset) {
            Icon(
                AppIcons.RefreshCw,
                contentDescription = "重置",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
