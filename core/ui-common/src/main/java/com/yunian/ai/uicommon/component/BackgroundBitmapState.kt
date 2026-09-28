package com.yunian.ai.uicommon.component

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext

/**
 * 背景位图异步加载的显式状态机（修 B1/B2/B3）。
 *
 * - `Idle`：尚未开始（或 key 为空 / 非自定义背景）。
 * - `Loading`：已发起解码，尚未完成。
 * - `Ready`：解码成功，持有可直接绘制的 [Bitmap]。
 * - `Failed`：解码失败（or 资源不存在）；**不写入缓存**，后续重组可重试。
 *
 * 设计要点：
 * 1. 状态以 `remember(key)` 为键 —— **key 变化即重置**，杜绝切换到另一个「已缓存」key
 *    时残留上一张位图（原缺陷根因）。
 * 2. 只在 [LaunchedEffect] 中写状态，**严禁在组合期写 state**。
 * 3. 命中缓存直接 `Ready`，未命中先 `Loading` 再异步加载，避免空白帧。
 */
sealed interface BackgroundBitmapState {
    data object Idle : BackgroundBitmapState
    data object Loading : BackgroundBitmapState
    data class Ready(val bitmap: Bitmap) : BackgroundBitmapState
    data class Failed(val reason: String?) : BackgroundBitmapState
}

/**
 * 以 [key] 为生命周期，异步加载自定义背景位图并暴露完整状态。
 *
 * 调用方（需要 loading / 失败重试 UI 的场景）可使用本函数；
 * 仅需要「有则画、无则回退」的调用方请继续使用 [rememberBackgroundBitmap]（签名与语义不变）。
 */
@Composable
fun rememberBackgroundBitmapState(key: String): BackgroundBitmapState {
    val context = LocalContext.current

    // key 变化即重建状态：命中缓存时同步初值为 Ready，避免缓存命中时的空帧闪烁（B1 修复关键）。
    var state by remember(key) {
        val cached = if (key.isNotBlank() && isCustomBackground(key)) {
            ChatBackgroundCache.getCachedBitmap(key)
        } else {
            null
        }
        mutableStateOf<BackgroundBitmapState>(
            if (cached != null) BackgroundBitmapState.Ready(cached) else BackgroundBitmapState.Idle
        )
    }

    LaunchedEffect(key) {
        if (state is BackgroundBitmapState.Ready) return@LaunchedEffect

        if (key.isBlank() || !isCustomBackground(key)) {
            state = BackgroundBitmapState.Failed(null)
            return@LaunchedEffect
        }

        ChatBackgroundCache.getCachedBitmap(key)?.let { cached ->
            state = BackgroundBitmapState.Ready(cached)
            return@LaunchedEffect
        }

        state = BackgroundBitmapState.Loading
        val loaded = ChatBackgroundCache.load(context.applicationContext, key)
        state = if (loaded != null) {
            BackgroundBitmapState.Ready(loaded)
        } else {
            BackgroundBitmapState.Failed(null)
        }
    }

    return state
}

/**
 * 兼容 API：签名与语义保持不变（`BitmapPainter?`）。
 *
 * 内部委托 [rememberBackgroundBitmapState]：仅当 [BackgroundBitmapState.Ready] 时返回 painter，
 * 其余状态（Idle / Loading / Failed）一律返回 `null`，让调用方回退到纯色 / 渐变。
 * 调用方（MainScreen / GlassPageScaffold / ChatScreen / GroupChatScreen）零改动。
 */
@Composable
fun rememberBackgroundBitmap(key: String): BitmapPainter? {
    val bitmap = (rememberBackgroundBitmapState(key) as? BackgroundBitmapState.Ready)?.bitmap
    return remember(bitmap) {
        bitmap?.let { BitmapPainter(it.asImageBitmap()) }
    }
}

/**
 * 兼容 API：签名不变（`ImageBitmap?`）。
 *
 * 修复了与 [rememberBackgroundBitmap] 相同的组合期写 state / 非 key 感知缺陷（原
 * `ChatBackgroundCache.kt` 内实现），现统一委托 [rememberBackgroundBitmapState]。
 */
@Composable
fun rememberBackgroundImageBitmap(key: String): ImageBitmap? {
    val bitmap = (rememberBackgroundBitmapState(key) as? BackgroundBitmapState.Ready)?.bitmap
    return remember(bitmap) { bitmap?.asImageBitmap() }
}
