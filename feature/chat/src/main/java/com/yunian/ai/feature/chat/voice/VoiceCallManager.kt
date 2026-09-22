package com.yunian.ai.feature.chat.voice

import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class VoiceCallManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCallManager"
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80

        private const val RULE1_TRAILING_SILENCE = 2.4f
        private const val RULE2_UTTERANCE_LENGTH = 1.2f
        private const val RULE3_MAX_UTTERANCE = 20.0f

        /**
         * ASR 解码线程数：按硬件档位分档，替代原硬编码 4。
         *
         * 理由：sherpa-onnx 解码为 CPU 密集型，线程过多会相互抢占大核、放大上下文切换；
         * 线程过少则低端机解码跟不上实时率。分档后 LOW 降到 2、ULTRA 提到 6，
         * HIGH/MEDIUM 基本保持原 4/3 的体感，并以物理核数为硬上限兜底（避免超订阅）。
         */
        private fun resolveAsrThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val desired = when (HardwareInfo.tier) {
                HardwareInfo.Tier.LOW -> 2
                HardwareInfo.Tier.MEDIUM -> 3
                HardwareInfo.Tier.HIGH -> 4
                HardwareInfo.Tier.ULTRA -> 6
            }
            return desired.coerceIn(1, cores)
        }

        private val MODEL_FILES = listOf(
            "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-20-avg-1-chunk-16-left-128.onnx",
            "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx",
            "tokens.txt"
        )
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingJob: Job? = null

    /**
     * ASR 录音/解码专用受限调度：CPU 密集的 `rec.decode` 不再挤在 IO 池（默认 64 线程）里，
     * 用 [AppDispatchers.asr]（串行 view），与网络/文件阻塞任务隔离、避免抢占大核。
     */
    private val asrDispatcher = AppDispatchers.asr
    private var scope = CoroutineScope(SupervisorJob() + asrDispatcher)
    private val initialized = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val initLock = Any()

    @Volatile var onPartialResult: ((String) -> Unit)? = null

    @Volatile var onFinalResult: ((String) -> Unit)? = null

    @Volatile var lastError: String? = null
        private set

    val isListening: Boolean get() = recordingJob?.isActive == true

    val isReady: Boolean get() = initialized.get() && !destroyed.get() && recognizer != null

    fun init(): Boolean {
        if (destroyed.get()) {
            lastError = "管理器已销毁"
            return false
        }
        if (initialized.get() && recognizer != null) return true

        synchronized(initLock) {
            if (destroyed.get()) {
                lastError = "管理器已销毁"
                return false
            }
            if (initialized.get() && recognizer != null) return true

            return try {
                if (!isSupportedAbi()) {
                    lastError = "当前 ABI 不支持离线语音识别（需要 arm64-v8a）"
                    SecureLog.e(TAG, lastError!!)
                    return false
                }

                val encoderPath = copyAssetVerified(MODEL_FILES[0])
                val decoderPath = copyAssetVerified(MODEL_FILES[1])
                val joinerPath = copyAssetVerified(MODEL_FILES[2])
                val tokensPath = copyAssetVerified(MODEL_FILES[3])

                val featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = FEATURE_DIM
                )
                val transducerConfig = OnlineTransducerModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    joiner = joinerPath
                )
                val modelConfig = OnlineModelConfig(
                    transducer = transducerConfig,
                    tokens = tokensPath,
                    numThreads = resolveAsrThreads()
                )
                val endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, RULE1_TRAILING_SILENCE, 0.0f),
                    rule2 = EndpointRule(true, RULE2_UTTERANCE_LENGTH, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, RULE3_MAX_UTTERANCE)
                )
                val config = OnlineRecognizerConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig,
                    endpointConfig = endpointConfig,
                    enableEndpoint = true
                )

                val created = OnlineRecognizer(assetManager = null, config = config)
                if (destroyed.get()) {
                    releaseRecognizerQuietly(created)
                    lastError = "管理器已销毁"
                    return false
                }
                recognizer = created
                initialized.set(true)
                lastError = null
                SecureLog.i(TAG, "sherpa-onnx OnlineRecognizer 初始化成功 (v1.13.3)")
                true
            } catch (e: UnsatisfiedLinkError) {
                lastError = "原生库加载失败，请确认 sherpa-onnx AAR 已打包"
                SecureLog.e(TAG, lastError!!, e)
                recognizer = null
                initialized.set(false)
                false
            } catch (t: Throwable) {
                lastError = t.message?.takeIf { it.isNotBlank() } ?: "语音识别引擎初始化失败"
                SecureLog.e(TAG, "sherpa-onnx 模型初始化失败", t)
                recognizer = null
                initialized.set(false)
                false
            }
        }
    }

    fun startListening() {
        if (destroyed.get()) {
            SecureLog.w(TAG, "已销毁，忽略 startListening")
            return
        }
        if (recordingJob?.isActive == true) {
            SecureLog.d(TAG, "录音已在进行中，忽略重复启动")
            return
        }

        val rec = recognizer
        if (rec == null || !initialized.get()) {
            SecureLog.e(TAG, "识别器未初始化，请先调用 init()")
            return
        }

        try {
            releaseStreamQuietly(stream)
            stream = rec.createStream()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBuf > 0) minBuf else 1024

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                SecureLog.e(TAG, "AudioRecord 初始化失败")
                record.release()
                return
            }
            audioRecord = record
            record.startRecording()

            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.also {
                        it.enabled = true
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.also {
                        it.enabled = true
                    }
                }
            } catch (e: Exception) {
                SecureLog.e(TAG, "音频增强失败: ${e.message}")
            }

            recordingJob = scope.launch {
                recordLoop(record, bufferSize)
            }
            SecureLog.i(TAG, "开始流式录音 (${SAMPLE_RATE}Hz)")
        } catch (e: SecurityException) {
            SecureLog.e(TAG, "录音权限被拒绝", e)
            lastError = "录音权限被拒绝"
        } catch (t: Throwable) {
            SecureLog.e(TAG, "startListening 失败", t)
            lastError = t.message ?: "启动录音失败"
            stopListening()
        }
    }

    private suspend fun recordLoop(record: AudioRecord, bufferSize: Int) {
        val shorts = ShortArray(bufferSize / 2)

        while (
            !destroyed.get() &&
            scope.isActive &&
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        ) {
            val n = try {
                record.read(shorts, 0, shorts.size)
            } catch (t: Throwable) {
                SecureLog.e(TAG, "AudioRecord.read 失败", t)
                break
            }
            if (n <= 0) continue

            val floats = FloatArray(n) { i -> shorts[i] / 32768.0f }
            val s = stream ?: continue
            val rec = recognizer ?: continue

            try {
                s.acceptWaveform(floats, SAMPLE_RATE)
                while (!destroyed.get() && rec.isReady(s)) {
                    rec.decode(s)
                }

                val result = rec.getResult(s)
                if (result != null && result.text.isNotEmpty()) {
                    emitPartial(result.text)
                }

                if (rec.isEndpoint(s)) {
                    val finalText = rec.getResult(s)?.text.orEmpty()
                    if (finalText.isNotEmpty()) {
                        emitFinal(finalText)
                    }
                    releaseStreamQuietly(stream)
                    if (!destroyed.get()) {
                        stream = rec.createStream()
                    }
                }
            } catch (t: Throwable) {
                SecureLog.e(TAG, "识别循环异常，停止录音", t)
                lastError = t.message ?: "识别异常"
                break
            }
        }
    }

    private suspend fun emitPartial(text: String) {
        if (destroyed.get()) return
        withContext(Dispatchers.Main) {
            if (!destroyed.get()) onPartialResult?.invoke(text)
        }
    }

    private suspend fun emitFinal(text: String) {
        if (destroyed.get()) return
        withContext(Dispatchers.Main) {
            if (!destroyed.get()) onFinalResult?.invoke(text)
        }
    }

    fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        releaseStreamQuietly(stream)
        stream = null
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        SecureLog.i(TAG, "停止录音")
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            stopListening()
            return
        }
        onPartialResult = null
        onFinalResult = null
        stopListening()
        synchronized(initLock) {
            releaseRecognizerQuietly(recognizer)
            recognizer = null
            initialized.set(false)
        }
        scope.cancel()

        SecureLog.i(TAG, "已销毁")
    }

    private fun copyAssetVerified(name: String): String {
        val target = File(context.filesDir, name)
        if (target.exists() && target.length() > 0L) {
            return target.absolutePath
        }
        context.assets.open(name).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
        if (!target.exists() || target.length() <= 0L) {
            throw IllegalStateException("模型文件复制失败或为空: $name")
        }
        return target.absolutePath
    }

    private fun isSupportedAbi(): Boolean {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()

        return abis.any { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" || it == "x86" }
    }

    private fun releaseStreamQuietly(s: OnlineStream?) {
        if (s == null) return
        try {

            s.release()
        } catch (_: Throwable) {
        }
    }

    private fun releaseRecognizerQuietly(r: OnlineRecognizer?) {
        if (r == null) return
        try {
            r.release()
        } catch (_: Throwable) {
        }
    }
}
