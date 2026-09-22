package com.yunian.ai.uicommon.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.PinkPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

data class YuNianToastEvent(
    val message: String,
    val type: Type = Type.Info,
    val durationMs: Long = 2_800L,
    val id: Long = nextId()
) {
    enum class Type { Info, Success, Warning, Error }

    companion object {
        private val seq = AtomicLong(0)
        private fun nextId(): Long = seq.incrementAndGet()
    }
}

@Stable
object YuNianToast {
    private val _events = MutableSharedFlow<YuNianToastEvent>(
        extraBufferCapacity = 8,
        replay = 0
    )
    val events: SharedFlow<YuNianToastEvent> = _events.asSharedFlow()

    fun show(message: String, type: YuNianToastEvent.Type = YuNianToastEvent.Type.Info, durationMs: Long = 2_800L) {
        val text = message.trim()
        if (text.isEmpty()) return
        _events.tryEmit(YuNianToastEvent(message = text, type = type, durationMs = durationMs))
    }

    fun info(message: String) = show(message, YuNianToastEvent.Type.Info)
    fun success(message: String) = show(message, YuNianToastEvent.Type.Success)
    fun warning(message: String) = show(message, YuNianToastEvent.Type.Warning)
    fun error(message: String) = show(message, YuNianToastEvent.Type.Error, durationMs = 3_600L)
}

@Composable
fun YuNianToastHost(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter
) {
    var current by remember { mutableStateOf<YuNianToastEvent?>(null) }

    LaunchedEffect(Unit) {
        YuNianToast.events.collect { event ->
            current = event
            delay(event.durationMs)
            if (current?.id == event.id) {
                current = null
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        YuNianToastCard(
            event = current,
            alignment = alignment
        )
    }
}

@Composable
fun BoxScope.YuNianToastOverlay(
    alignment: Alignment = Alignment.TopCenter
) {
    var current by remember { mutableStateOf<YuNianToastEvent?>(null) }

    LaunchedEffect(Unit) {
        YuNianToast.events.collect { event ->
            current = event
            delay(event.durationMs)
            if (current?.id == event.id) {
                current = null
            }
        }
    }

    YuNianToastCard(event = current, alignment = alignment)
}

@Composable
private fun BoxScope.YuNianToastCard(
    event: YuNianToastEvent?,
    alignment: Alignment
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)

    AnimatedVisibility(
        visible = event != null,
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 20.dp, vertical = 56.dp),
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 3 }
    ) {
        val toast = event ?: return@AnimatedVisibility
        val accent = when (toast.type) {
            YuNianToastEvent.Type.Info -> PinkPrimary
            YuNianToastEvent.Type.Success -> Color(0xFF10A37F)
            YuNianToastEvent.Type.Warning -> Color(0xFFFFA726)
            YuNianToastEvent.Type.Error -> colors.error
        }

        val bg = colors.surface
        val fg = colors.onSurface

        Box(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 360.dp)
                .shadow(10.dp, shape, clip = false)
                .clip(shape)
                .background(bg)
                .border(1.dp, accent.copy(alpha = 0.35f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = toast.message,
                color = fg,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
