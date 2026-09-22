
package com.yunian.ai.uicommon.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun ProvidePageBackdrop(backdrop: Backdrop?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageBackdrop provides backdrop, content = content)
}
