package com.yunian.ai.feature.chat.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.SystemClock
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.common.perf.PerfBoost
import com.yunian.ai.network.tts.ChatTtsConfig
import com.yunian.ai.network.tts.ChatTtsMode
import com.yunian.ai.network.tts.TtsService
import com.yunian.ai.network.tts.TtsTextCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class ChatTtsController(
    private val context: Context,
    private val ttsService: TtsService,
    @Suppress("UNUSED_PARAMETER") private val scope: CoroutineScope,
    private val configProvider: () -> ChatTtsConfig,
    private val callActiveProvider: () -> Boolean
) {
    private val _state = MutableStateFlow(ChatTtsState.IDLE)
    val state: StateFlow<ChatTtsState> = _state.asStateFlow()

    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private fun ttsMasterEnabled(): Boolean =
        context.getSharedPreferences("tts_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("tts_enabled", true)

    suspend fun synthesizeOnly(text: String): VoiceBarAudio? {
        if (callActiveProvider()) {
            SecureLog.d(TAG, "synthesizeOnly skipped: voice call active")
            return null
        }
        val cfg = configProvider()
        if (cfg.mode != ChatTtsMode.VOICE_BAR) return null

        if (!ttsMasterEnabled()) {
            SecureLog.d(TAG, "synthesizeOnly skipped: TTS master switch off")
            return null
        }
        val cleaned = TtsTextCleaner.clean(text, cfg.skipParentheses)
        if (cleaned.isBlank()) return null
        return try {
            // 核查结论：合成重活（TtsService.synthesize / persistVoiceBarFile）原本已在
            // Dispatchers.IO 上执行，但 MediaMetadataRetriever / MediaPlayer 时长探测
            // （probeDurationMs）会随调用方上下文执行；此处显式整体切到 IO（防御式），
            // 保证主线程绝不被合成或时长探测阻塞。
            withContext(Dispatchers.IO) {
                // ADPF：把本次合成线程声明为关键工作负载，交由系统提频/摆核。
                // 合成是单次阻塞调用（无分块回调），故在结束时上报一次总耗时，频率克制；
                // 仅在支持时创建，否则零开销。
                val perfSession = if (PerfBoost.isSupported) {
                    PerfBoost.createSession(
                        tag = "tts-synth",
                        targetWorkDurationNanos = TTS_SYNTH_TARGET_NANOS,
                        threadIds = intArrayOf(PerfBoost.currentThreadId()),
                    )
                } else {
                    null
                }
                val startedNanos = SystemClock.elapsedRealtimeNanos()
                try {
                    val timeoutMs = TimeoutBudgets.ttsSynthTimeoutMs(cleaned.length)
                    val tempPath = withTimeoutOrNull(timeoutMs) {
                        ttsService.synthesize(cleaned)
                    } ?: return@withContext null
                    val durablePath = persistVoiceBarFile(File(tempPath)) ?: return@withContext null
                    val durationMs = probeDurationMs(durablePath)
                    VoiceBarAudio(path = durablePath, durationMs = durationMs)
                } finally {
                    perfSession?.reportActual(SystemClock.elapsedRealtimeNanos() - startedNanos)
                    perfSession?.close()
                }
            }
        } catch (e: Exception) {
            SecureLog.e(TAG, "synthesizeOnly failed", e)
            null
        }
    }

    private suspend fun persistVoiceBarFile(temp: File): String? = withContext(Dispatchers.IO) {
        if (!temp.exists() || temp.length() <= 0L) return@withContext null
        try {
            val dir = File(context.filesDir, VOICE_BAR_DIR)
            if (!dir.exists()) dir.mkdirs()
            val ext = temp.extension.ifBlank { "mp3" }
            val dest = File(dir, "vb_${System.currentTimeMillis()}_${temp.nameWithoutExtension}.$ext")
            temp.copyTo(dest, overwrite = true)

            runCatching { temp.delete() }
            dest.absolutePath
        } catch (e: Exception) {
            SecureLog.e(TAG, "persistVoiceBarFile failed", e)

            temp.takeIf { it.exists() }?.absolutePath
        }
    }

    private fun probeDurationMs(path: String): Long? {

        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                raw?.toLongOrNull()?.takeIf { it > 0L }
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            val player = MediaPlayer()
            try {
                player.setDataSource(path)
                player.prepare()
                player.duration.toLong().takeIf { it > 0L }
            } finally {
                runCatching { player.release() }
            }
        }.getOrNull()
    }

    fun stop() {
        _state.value = ChatTtsState.IDLE
        _currentText.value = ""
    }

    fun clearSeen() = Unit

    companion object {
        private const val TAG = "ChatTtsController"
        private const val VOICE_BAR_DIR = "tts_voice_bars"

        /** ADPF 合成目标工作时长：TTS 单句合成量级取 300ms（仅作系统提频参考）。 */
        private const val TTS_SYNTH_TARGET_NANOS = 300_000_000L
    }
}

data class VoiceBarAudio(
    val path: String,
    val durationMs: Long?
)

enum class ChatTtsState {
    IDLE,
    SPEAKING
}
