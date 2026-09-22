package com.yunian.ai.uicommon.component.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.yunian.ai.uicommon.component.BackgroundSettingsViewModel
import com.yunian.ai.uicommon.component.PageBackgroundContent
import com.yunian.ai.uicommon.component.WindowMainBackground

/**
 * 二级页面统一玻璃容器。
 *
 * - 背景：用 [BackgroundSettingsViewModel.mainBgKey]（ViewModel 内部已监听 SharedPreferences，
 *   跨 Activity / NavBackStackEntry 作用域同步），切换背景立即生效。
 * - 布局：topBar/bottomBar/FAB 正确传给 [Scaffold]，content 的 PaddingValues 会包含其高度，
 *   避免内容顶到 header 被遮挡。
 */
@Composable
fun GlassPageScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    bottomBar: @Composable (() -> Unit)? = null,
    snackbarHost: @Composable (() -> Unit)? = null,
    floatingActionButton: @Composable (() -> Unit)? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val context = LocalContext.current
    val isDark = remember { WindowMainBackground.resolveIsDarkTheme(context) }

    val backgroundViewModel: BackgroundSettingsViewModel = viewModel()
    val bgKey by backgroundViewModel.mainBgKey.collectAsState()

    // ON_RESUME 兜底：返回页面时强制从 SharedPreferences 重读背景，
    // 不依赖 SP 监听器（跨 ViewModel 实例的通知链路任何环节失效都能被这里纠正）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                backgroundViewModel.refreshFromPrefs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val backdrop = key(bgKey, isDark) {
        rememberLayerBackdrop {
            drawContent()
        }
    }

    ProvidePageBackdrop(backdrop) {
        Box(modifier = modifier.fillMaxSize()) {
            // 纯背景层：预设 / 自定义纯色 / 自定义图片 三种 key 统一渲染，
            // 作为 layerBackdrop 捕获源（只画背景，不含液态玻璃组件，避免 RenderNode 环）
            PageBackgroundContent(
                bgKey = bgKey,
                isDark = isDark,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            )
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = { topBar?.invoke() },
                bottomBar = { bottomBar?.invoke() },
                snackbarHost = { snackbarHost?.invoke() },
                floatingActionButton = { floatingActionButton?.invoke() },
                content = content
            )
        }
    }
}
