package com.yunian.ai.uicommon.component
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import android.media.MediaPlayer

private object VoicePlaybackCoordinator {
    private var activeStop: (() -> Unit)? = null

    fun activate(stopPlayback: () -> Unit) {
        activeStop?.invoke()
        activeStop = stopPlayback
    }

    fun clear(stopPlayback: () -> Unit) {
        if (activeStop === stopPlayback) activeStop = null
    }
}

@Composable
fun VoiceMessageBubble(
    audioPath: String,
    duration: Int,
    isUser: Boolean,
    onPlayComplete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    containerColor: Color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    contentColor: Color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
) {
    val controlColor = contentColor.copy(alpha = 0.12f)
    val playedWaveColor = contentColor
    val idleWaveColor = contentColor.copy(alpha = 0.28f)

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    val stopPlayback = remember {
        {
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying = false
            currentPosition = 0L
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "voice_wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    DisposableEffect(Unit) {
        onDispose {
            stopPlayback()
            VoicePlaybackCoordinator.clear(stopPlayback)
        }
    }

    fun togglePlayback() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
        } else {
            if (mediaPlayer == null || mediaPlayer?.currentPosition == 0) {
                try {
                    VoicePlaybackCoordinator.activate(stopPlayback)
                    val player = MediaPlayer().apply {
                        setDataSource(audioPath)
                        prepareAsync()
                        setOnPreparedListener { mp ->
                            mp.start()
                            isPlaying = true
                        }
                        setOnCompletionListener { mp ->
                            isPlaying = false
                            currentPosition = 0L
                            VoicePlaybackCoordinator.clear(stopPlayback)
                            onPlayComplete?.invoke()
                            mp.seekTo(0)
                        }
                    }
                    mediaPlayer?.release()
                    mediaPlayer = player
                } catch (e: Exception) {
                    isPlaying = false
                }
            } else {
                VoicePlaybackCoordinator.activate(stopPlayback)
                mediaPlayer?.start()
                isPlaying = true
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            delay(100)
            currentPosition = (mediaPlayer?.currentPosition ?: 0).toLong()
        }
    }

    val waveformWidth = (72 + duration.coerceIn(0, 54)).dp

    Surface(
        modifier = modifier
            .widthIn(min = 120.dp, max = 240.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { togglePlayback() }
            ),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(controlColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            VoiceWaveform(
                isPlaying = isPlaying,
                phase = wavePhase,
                playedColor = playedWaveColor,
                idleColor = idleWaveColor,
                duration = duration.coerceAtLeast(1),
                currentPosition = currentPosition.toInt(),
                modifier = Modifier.width(waveformWidth)
            )

            Text(
                text = formatDuration(if (isPlaying && mediaPlayer != null) ((mediaPlayer?.duration ?: duration * 1000) / 1000) else duration),
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.68f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VoiceWaveform(
    isPlaying: Boolean,
    phase: Float,
    playedColor: Color,
    idleColor: Color,
    duration: Int,
    currentPosition: Int,
    modifier: Modifier = Modifier
) {
    val barCount = 12

    Canvas(modifier = modifier.height(24.dp)) {
        val barWidth = size.width / (barCount * 2.35f)
        val maxHeight = size.height * 0.85f
        val progress = if (duration > 0) currentPosition.toFloat() / (duration * 1000f) else 0f

        for (i in 0 until barCount) {
            val x = barWidth * (i * 2.25f + 0.6f)
            val barProgress = i.toFloat() / barCount

            val heightRatio = when {
                !isPlaying -> 0.32f + 0.18f * (i % 3) / 2f
                barProgress <= progress -> 0.42f + 0.58f * kotlin.math.sin((phase * 2 * kotlin.math.PI.toFloat()) + (i * 0.58f)).toFloat()
                else -> 0.22f + 0.2f * (i % 3) / 2f
            }.coerceIn(0.2f, 1.0f)

            val barHeight = maxHeight * heightRatio
            val y = (size.height - barHeight) / 2
            val barColor = if (barProgress <= progress) playedColor else idleColor

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    return if (seconds < 60) "${seconds}\"" else "${seconds / 60}'${seconds % 60}\""
}
