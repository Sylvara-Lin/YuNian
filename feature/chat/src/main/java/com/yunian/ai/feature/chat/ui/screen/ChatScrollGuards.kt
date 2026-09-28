package com.yunian.ai.feature.chat.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlin.math.abs

@Composable
internal fun rememberHorizontalSwipeGuard(): NestedScrollConnection {
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (abs(available.y) > abs(available.x)) {
                    Offset(available.x, 0f)
                } else {
                    Offset.Zero
                }
            }
        }
    }
}
