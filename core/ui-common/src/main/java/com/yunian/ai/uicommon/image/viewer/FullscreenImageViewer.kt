package com.yunian.ai.uicommon.image.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yunian.ai.uicommon.image.engine.ImageTransform
import com.yunian.ai.uicommon.image.engine.TransformState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Composable
fun FullscreenImageViewer(
    models: List<Any>,
    initialIndex: Int = 0,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenFailed: ((Any) -> Unit)? = null,
    scrimColor: Color = Color.Black,
    modifier: Modifier = Modifier,
) {
    if (!visible || models.isEmpty()) return

    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val window = activity?.window

    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (previousBehavior != null) {
                controller?.systemBarsBehavior = previousBehavior
            }
        }
    }

    var isExiting by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val enterProgress by animateFloatAsState(
        targetValue = when {
            isExiting -> 0f
            entered -> 1f
            else -> 0f
        },
        animationSpec = tween(if (isExiting) 180 else 280),
        label = "fullscreenEnter",
        finishedListener = { value ->
            if (isExiting && value == 0f) onDismiss()
        }
    )

    fun requestDismiss() {
        if (!isExiting) isExiting = true
    }

    BackHandler(enabled = !isExiting, onBack = { requestDismiss() })

    val safeInitial = initialIndex.coerceIn(0, models.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeInitial,
        pageCount = { models.size }
    )

    var pageAllowsSwipe by remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage) {

        pageAllowsSwipe = true
    }

    val enterScale = 0.72f + 0.28f * enterProgress
    val scrimAlpha = (0.96f * enterProgress).coerceIn(0f, 0.96f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(40f)
            .background(scrimColor.copy(alpha = scrimAlpha))
            .graphicsLayer {
                scaleX = enterScale
                scaleY = enterScale
                alpha = enterProgress
            },
        contentAlignment = Alignment.Center
    ) {
        HorizontalPager(
            state = pagerState,

            reverseLayout = true,
            userScrollEnabled = pageAllowsSwipe && !isExiting && models.size > 1,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val model = models[page]
            FullscreenImagePage(
                model = model,
                isCurrentPage = page == pagerState.currentPage,
                onAtBaseScaleChanged = { atBase ->
                    if (page == pagerState.currentPage) {
                        pageAllowsSwipe = atBase
                    }
                },
                onTapDismiss = { requestDismiss() },
                onOpenFailed = { onOpenFailed?.invoke(model) }
            )
        }
    }
}

@Composable
fun FullscreenImageViewer(
    model: Any?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenFailed: (() -> Unit)? = null,
    scrimColor: Color = Color.Black,
    modifier: Modifier = Modifier,
) {
    FullscreenImageViewer(
        models = listOfNotNull(model),
        initialIndex = 0,
        visible = visible && model != null,
        onDismiss = onDismiss,
        onOpenFailed = onOpenFailed?.let { cb -> { cb() } },
        scrimColor = scrimColor,
        modifier = modifier,
    )
}

@Composable
private fun FullscreenImagePage(
    model: Any,
    isCurrentPage: Boolean,
    onAtBaseScaleChanged: (Boolean) -> Unit,
    onTapDismiss: () -> Unit,
    onOpenFailed: () -> Unit,
) {
    val context = LocalContext.current

    var bitmap by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(model) { mutableStateOf(false) }

    LaunchedEffect(model) {
        bitmap = null
        loadFailed = false
        val decoded = withContext(Dispatchers.IO) {
            decodePreviewBitmap(context, model)
        }
        if (decoded == null) {
            loadFailed = true
            if (isCurrentPage) onOpenFailed()
        } else {
            bitmap = decoded
        }
    }

    var viewportSize by remember { mutableStateOf(Size.Zero) }
    val imageBitmap = bitmap

    val imageSize = remember(imageBitmap) {
        if (imageBitmap == null) Size.Zero
        else Size(imageBitmap.width.toFloat(), imageBitmap.height.toFloat())
    }

    val baseTransform = remember(imageSize, viewportSize) {
        if (imageSize == Size.Zero || viewportSize == Size.Zero) {
            TransformState(imageSize = imageSize, viewportSize = viewportSize)
        } else {
            ImageTransform.fitInside(imageSize, viewportSize)
        }
    }

    var currentTransform by remember(baseTransform) { mutableStateOf(baseTransform) }
    val latestTransform = { currentTransform }

    val minScale = baseTransform.scale.takeIf { it > 0f } ?: 1f
    val maxScale = minScale * 5f
    val doubleTapScale = minScale * 2.5f

    val atBaseScale = currentTransform.scale <= minScale * 1.05f
    LaunchedEffect(isCurrentPage, atBaseScale) {
        if (isCurrentPage) onAtBaseScaleChanged(atBaseScale)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it.toSize() },
        contentAlignment = Alignment.Center
    ) {
        when {
            loadFailed -> Unit
            imageBitmap == null || viewportSize == Size.Zero || baseTransform.scale <= 0f -> {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
            }
            else -> {
                AtomicImageViewer(
                    bitmap = imageBitmap,
                    transform = currentTransform,
                    latestTransform = latestTransform,
                    onTransformRequest = { requested ->
                        currentTransform = ImageTransform.clampToViewport(requested)
                    },
                    minScale = minScale,
                    maxScale = maxScale,
                    modifier = Modifier.fillMaxSize(),
                    onTap = {
                        if (currentTransform.scale <= minScale * 1.05f) {
                            onTapDismiss()
                        }
                    },
                    onDoubleTap = { tapOffset ->
                        if (currentTransform.scale > minScale * 1.05f) {
                            currentTransform = baseTransform
                        } else {
                            val old = currentTransform
                            val targetScale = doubleTapScale.coerceAtMost(maxScale)
                            val imageX = (tapOffset.x - old.offsetX) / old.scale
                            val imageY = (tapOffset.y - old.offsetY) / old.scale
                            val next = old.copy(
                                scale = targetScale,
                                offsetX = tapOffset.x - targetScale * imageX,
                                offsetY = tapOffset.y - targetScale * imageY,
                            )
                            currentTransform = ImageTransform.clampToViewport(next)
                        }
                    }
                )
            }
        }
    }
}

private fun decodePreviewBitmap(context: Context, model: Any): ImageBitmap? {
    return try {
        val maxSide = 4096
        when (model) {
            is ImageBitmap -> model
            is android.graphics.Bitmap -> model.asImageBitmap()
            is File -> decodeFileSampled(model, maxSide)
            is String -> {
                val file = File(model)
                if (file.exists()) decodeFileSampled(file, maxSide) else null
            }
            is android.net.Uri -> {
                context.contentResolver.openInputStream(model)?.use { input ->
                    val bytes = input.readBytes()
                    decodeBytesSampled(bytes, maxSide)
                }
            }
            else -> null
        }
    } catch (_: OutOfMemoryError) {
        // 兜底（修 FIX-1）：Uri 分支 readBytes/decode 均可能 OOM（Error），不能只 catch Exception。
        null
    } catch (_: Exception) {
        null
    }
}

private fun decodeFileSampled(file: File, maxSide: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
    } catch (_: OutOfMemoryError) {
        // OOM 是 Error 非 Exception：不本地兜底会穿透到调用方（修 FIX-1）。
        null
    } catch (_: Exception) {
        null
    }
}

private fun decodeBytesSampled(bytes: ByteArray, maxSide: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / sample > maxSide) {
        sample *= 2
    }
    return sample.coerceAtLeast(1)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
