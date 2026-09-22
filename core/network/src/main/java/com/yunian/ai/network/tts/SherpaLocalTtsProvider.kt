package com.yunian.ai.network.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 VITS 合成是 **CPU 密集型**：用 [AppDispatchers.tts]（串行 view）执行，
 * 与 IO 池隔离（避免与网络/文件阻塞任务互相拖累），且同一时刻只跑一段合成（引擎串行）。
 */
private val TTS_INFERENCE_DISPATCHER = AppDispatchers.tts

class SherpaLocalTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val mutex = Mutex()
    private var offlineTts: OfflineTts? = null
    private var loadedModelId: String? = null
    private var config: TtsConfig = TtsConfig()

    override fun updateConfig(config: TtsConfig) {

        if (this.config.localTtsSpeed != config.localTtsSpeed ||
            this.config.localTtsSid != config.localTtsSid
        ) {

        }
        this.config = config
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? =
        withContext(TTS_INFERENCE_DISPATCHER) {
            try {
                val preferences = LocalTtsPreferences(context)
                val modelId = preferences.selectedModelId.first()
                val modelState = preferences.modelState(modelId).first()
                if (!modelState.isEnabled) {
                    SecureLog.w(TAG, "本地 TTS 未启用 (model=$modelId)")
                    return@withContext null
                }
                val model = LocalTtsCatalog.findById(modelId)
                if (!model.isAllFilesPresent(context)) {
                    SecureLog.w(TAG, "模型文件缺失: ${model.id}")
                    return@withContext null
                }
                val tts = getOrLoadOfflineTts(context, model)
                val sid = parseSid(voiceId, model)
                val speed = config.localTtsSpeed.coerceIn(0.5f, 2.0f)

                SecureLog.d(TAG, "合成: model=${model.id}, sid=$sid, speed=$speed, len=${text.length}")
                val audio = tts.generate(text, sid, speed)

                val outputDir = File(context.cacheDir, "tts_audio")
                outputDir.mkdirs()
                val outputFile = File(outputDir, "local_${System.currentTimeMillis()}.wav")
                LocalTtsWavWriter.writePcmToWav(audio.samples, audio.sampleRate, outputFile)

                SecureLog.i(TAG, "本地 TTS 合成成功: ${outputFile.absolutePath}")
                outputFile.absolutePath
            } catch (e: Exception) {
                SecureLog.e(TAG, "本地 TTS 合成失败", e)
                null
            }
        }

    override fun getVoices(): List<TtsVoice> {

        return listOf(
            TtsVoice("speaker_0", "默认音色", "自定义", "zh-CN", "sid=0"),
            TtsVoice("__custom_sid__", "自定义 sid", "自定义", "zh-CN", "在设置页填入 sid")
        )
    }

    override suspend fun testConnection(context: Context): Boolean {
        return try {

            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getNumSpeakers(context: Context): Int {
        val modelId = LocalTtsPreferences(context).selectedModelId.first()
        return LocalTtsCatalog.findById(modelId).numSpeakers
    }

    private suspend fun getOrLoadOfflineTts(context: Context, model: LocalTtsModel): OfflineTts {
        if (offlineTts != null && loadedModelId == model.id) {
            return offlineTts!!
        }
        return mutex.withLock {
            if (offlineTts != null && loadedModelId == model.id) {
                offlineTts!!
            } else {

                try { offlineTts?.release() } catch (_: Exception) {}
                val tts = createOfflineTts(context, model)
                offlineTts = tts
                loadedModelId = model.id
                SecureLog.i(TAG, "OfflineTts 加载成功: ${model.displayName}, sampleRate=${tts.sampleRate()}")
                tts
            }
        }
    }

    private fun createOfflineTts(context: Context, model: LocalTtsModel): OfflineTts {
        val modelPath = model.file(context, model.modelFileName).absolutePath
        val tokensPath = model.file(context, model.tokensFileName).absolutePath
        val lexiconPath = model.lexiconFileName?.let { model.file(context, it).absolutePath } ?: ""

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = modelPath,
            lexicon = lexiconPath,
            tokens = tokensPath,
            dataDir = "",
            dictDir = "",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f.coerceIn(0.5f, 2.0f) / config.localTtsSpeed.coerceIn(0.5f, 2.0f)
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig
        )
        val ttsConfig = OfflineTtsConfig(
            model = modelConfig
        )

        return OfflineTts(assetManager = null, config = ttsConfig)
    }

    private fun parseSid(voiceId: String?, model: LocalTtsModel): Int {
        val raw = voiceId?.removePrefix("speaker_")?.trim()
        val parsed = raw?.toIntOrNull()
        val sid = parsed ?: config.localTtsSid
        return sid.coerceIn(0, (model.numSpeakers - 1).coerceAtLeast(0))
    }

    companion object {
        private const val TAG = "SherpaLocalTts"

        fun releaseEngine() {

        }
    }
}
