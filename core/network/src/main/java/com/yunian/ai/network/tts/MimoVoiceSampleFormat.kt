package com.yunian.ai.network.tts

import java.util.Locale

/**
 * MiMo TTS 音色复刻（voiceclone）音频样本的格式判定与体积换算工具。
 *
 * 这是**唯一事实来源**：UI 选择样本、Provider 组装 data URI 均通过本类判定格式与校验体积，
 * 避免各处各写一份 MIME 映射导致行为不一致。
 *
 * 设计约束：
 * - 纯 Kotlin，**不依赖任何 Android 类**，可在 JVM 单元测试中直接运行。
 * - 判定优先级：① 文件头魔数嗅探 → ② 声明的 MIME 别名归一 → ③ 文件名扩展名兜底。
 * - 最终用于请求体的 MIME 只会是官方允许的 [MIME_WAV] / [MIME_MP3] 之一。
 *
 * 官方契约（mimo-v2.5-tts-voiceclone）：
 * ```
 * audio.voice = "data:{MIME_TYPE};base64,$BASE64_AUDIO"
 * ```
 * - `{MIME_TYPE}` 仅允许 `audio/mpeg`（或 `audio/mp3`）、`audio/wav`
 * - `$BASE64_AUDIO` 为纯 base64，**base64 编码后不超过 10MB**
 */
object MimoVoiceSampleFormat {

    /** 官方允许的 WAV MIME。 */
    const val MIME_WAV = "audio/wav"

    /** 官方允许的 MP3 MIME。 */
    const val MIME_MP3 = "audio/mpeg"

    const val WAV_EXT = "wav"
    const val MP3_EXT = "mp3"

    /** 官方限制：Base64 编码后的字符串不超过 10MB。 */
    const val MAX_CLONE_BASE64_BYTES: Long = 10L * 1024L * 1024L

    /**
     * 由 base64 上限直接推导的**原始文件**字节上限（`floor(limit * 3 / 4)`，约 7.5MB）。
     *
     * 由 [MAX_CLONE_BASE64_BYTES] 推导而来，二者不会漂移；在全部取值上与
     * `base64EncodedLength(raw) > MAX_CLONE_BASE64_BYTES` 恒等价（见单测边界扫掠）。
     * 生产侧统一通过 [exceedsCloneLimit] 使用本上限。
     */
    const val MAX_CLONE_RAW_BYTES: Long = MAX_CLONE_BASE64_BYTES * 3L / 4L

    /** 魔数嗅探所需的最少字节数（RIFF/WAVE 需 12 字节，ID3 需 3 字节，帧同步需 2 字节）。 */
    const val SNIFF_BYTES = 16

    /** 单个音频样本格式的判定结果。 */
    data class DetectedFormat(val ext: String, val apiMime: String) {
        val isWav: Boolean get() = ext == WAV_EXT
        val isMp3: Boolean get() = ext == MP3_EXT
    }

    val WAV = DetectedFormat(WAV_EXT, MIME_WAV)
    val MP3 = DetectedFormat(MP3_EXT, MIME_MP3)

    // ---------------------------------------------------------------- 探测

    /**
     * 判定音频样本格式。
     *
     * @param headerBytes 文件头部字节（建议前 [SNIFF_BYTES] 字节及以上），用于魔数嗅探。
     * @param declaredMime ContentResolver 等返回的声明 MIME，可为 null。
     * @param fileName     文件名（含或不含路径均可），可为 null。
     * @return 判定结果；无法识别时返回 null。
     */
    fun detect(headerBytes: ByteArray, declaredMime: String?, fileName: String?): DetectedFormat? {
        // ① 魔数优先：格式以真实字节为准，避免 provider 报错 MIME 造成误判。
        sniffMagic(headerBytes)?.let { return it }
        // ② MIME 别名归一（未知 MIME 交给下一步）。
        normalizeMime(declaredMime)?.let { mime ->
            if (!isUnknownMime(mime)) {
                mimeToFormat(mime)?.let { return it }
            }
        }
        // ③ 扩展名兜底。
        return extensionToFormat(extensionOf(fileName))
    }

    /** 仅做文件头魔数嗅探；无法识别返回 null。 */
    fun sniffMagic(bytes: ByteArray): DetectedFormat? {
        if (isRiffWave(bytes)) return WAV
        if (isMp3Header(bytes)) return MP3
        return null
    }

    /**
     * 归一化 MIME：大小写不敏感、剥离 `;charset=...` 等参数、去除首尾空白。
     * 空串返回 null。
     */
    fun normalizeMime(raw: String?): String? {
        if (raw == null) return null
        return raw.substringBefore(';').trim().lowercase(Locale.US).ifEmpty { null }
    }

    /** 从文件名（可含路径）提取小写扩展名；无扩展名返回 null。 */
    fun extensionOf(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).trim().lowercase(Locale.US).ifEmpty { null }
    }

    /**
     * 未携带有效格式信息的 MIME（交由魔数/扩展名兜底），不要一律当作"不支持"拒绝。
     */
    fun isUnknownMime(mime: String): Boolean = mime in UNKNOWN_MIMES

    /**
     * 已知 MIME 别名 → 格式；不认识返回 null。
     *
     * 采用**精确集合匹配**（不做子串通配）：避免把 WavPack（`audio/wavpack`、`audio/x-wavpack`）
     * 等其它编码误判为 WAV。真实 wav 文件即便别名不在集合中，仍由魔数/扩展名兜底识别。
     */
    fun mimeToFormat(mime: String): DetectedFormat? {
        return when {
            mime in WAV_MIMES -> WAV
            mime in MP3_MIMES -> MP3
            else -> null
        }
    }

    /** 生成"不支持的格式"中文提示，区分"无法识别"与"识别但不支持"。 */
    fun unsupportedMessage(declaredMime: String?, fileName: String?): String {
        val mime = normalizeMime(declaredMime)
        return if (mime == null || isUnknownMime(mime)) {
            "无法识别所选文件的音频格式（文件：${fileName ?: "未知"}），仅支持 mp3 / wav 样本，请先转换格式后重试"
        } else {
            "暂不支持该音频格式（$mime），仅支持 mp3 / wav 样本"
        }
    }

    // ---------------------------------------------------------------- 体积

    /** 原始字节数经标准 base64 编码后的字符长度（含 padding、无换行）。 */
    fun base64EncodedLength(rawLength: Long): Long {
        if (rawLength <= 0L) return 0L
        return ((rawLength + 2L) / 3L) * 4L
    }

    /**
     * 给定原始字节数，base64 编码后是否超出官方 10MB 上限。
     *
     * 这是权威数学判据，用作 [exceedsCloneLimit] 的等价性基准，当前**仅由单元测试引用**——
     * 非死代码，请勿因"生产未引用"而删除（生产判据统一走 [exceedsCloneLimit]，便于流式拷贝早退）。
     */
    fun exceedsBase64Limit(rawLength: Long): Boolean =
        base64EncodedLength(rawLength) > MAX_CLONE_BASE64_BYTES

    /**
     * 样本体积是否超限——**生产侧唯一判据**。
     *
     * 直接按原始字节数与 [MAX_CLONE_RAW_BYTES] 比较，便于流式拷贝时按原始长度**早退**，
     * 避免先把超大文件写满磁盘再拒绝。与 [exceedsBase64Limit] 恒等价。
     */
    fun exceedsCloneLimit(rawLength: Long): Boolean = rawLength > MAX_CLONE_RAW_BYTES

    /** 以 MB 展示字节数，保留一位小数（如 `8.2MB`）。 */
    fun formatMegabytes(bytes: Long): String =
        String.format(Locale.US, "%.1fMB", bytes.toDouble() / (1024.0 * 1024.0))

    /** 生成"样本过大"中文提示，含原始/编码后体积与上限。 */
    fun tooLargeMessage(rawLength: Long): String {
        val encoded = base64EncodedLength(rawLength)
        return "音频样本过大（原始 ${formatMegabytes(rawLength)} / 编码后 ${formatMegabytes(encoded)}，" +
            "上限 10MB），请裁剪到 30 秒以内"
    }

    // ---------------------------------------------------------------- WAV 信息

    /** WAV 样本的关键参数（用于展示与 PCM 校验）。 */
    data class WavInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val byteRate: Long,
        /** data chunk 的字节数；未在头部范围内找到时为 -1。 */
        val dataBytes: Long,
        /** EXTENSIBLE(0xFFFE) 的 SubFormat GUID 前 4 字节（小端 uint32）；非 EXTENSIBLE 或字段不可用时为 null。 */
        val subFormat: Int? = null
    ) {
        /** PCM 判定三态（用于区分"确认非 PCM"与"未知，安全降级"）。 */
        enum class PcmStatus { PCM, NOT_PCM, UNKNOWN }

        /**
         * 编码是否为 PCM：
         * - 标准 PCM(1) → PCM；
         * - EXTENSIBLE(0xFFFE) → 按 SubFormat 判定（PCM=1、IEEE Float=3）；字段缺失则 UNKNOWN（不臆断非 PCM）；
         * - 其它（IEEE_FLOAT=3 / ADPCM 等）→ NOT_PCM。
         */
        val pcmStatus: PcmStatus
            get() = when (audioFormat) {
                AUDIO_FORMAT_PCM -> PcmStatus.PCM
                AUDIO_FORMAT_EXTENSIBLE -> when (subFormat) {
                    AUDIO_FORMAT_PCM -> PcmStatus.PCM
                    null -> PcmStatus.UNKNOWN
                    else -> PcmStatus.NOT_PCM
                }
                else -> PcmStatus.NOT_PCM
            }

        val isPcm: Boolean get() = pcmStatus == PcmStatus.PCM

        val durationSeconds: Double
            get() = if (dataBytes >= 0L && byteRate > 0L) dataBytes.toDouble() / byteRate.toDouble() else 0.0

        val formatName: String
            get() = when (audioFormat) {
                AUDIO_FORMAT_PCM -> "PCM"
                AUDIO_FORMAT_IEEE_FLOAT -> "IEEE Float"
                AUDIO_FORMAT_EXTENSIBLE -> when (subFormat) {
                    AUDIO_FORMAT_PCM -> "WAVE_FORMAT_EXTENSIBLE(PCM)"
                    AUDIO_FORMAT_IEEE_FLOAT -> "WAVE_FORMAT_EXTENSIBLE(IEEE Float)"
                    else -> "WAVE_FORMAT_EXTENSIBLE"
                }
                else -> "0x" + Integer.toHexString(audioFormat).uppercase(Locale.US)
            }

        /** 供 UI 展示的一行摘要，如 `3.0s · 单声道 · 16000Hz · 16bit · PCM`。 */
        fun describe(): String {
            val duration = if (dataBytes >= 0L && byteRate > 0L) {
                String.format(Locale.US, "%.1fs", durationSeconds)
            } else {
                "时长未知"
            }
            val channelText = when (channels) {
                1 -> "单声道"
                2 -> "立体声"
                else -> "${channels} 声道"
            }
            return "$duration · $channelText · ${sampleRate}Hz · ${bitsPerSample}bit · $formatName"
        }

        companion object {
            const val AUDIO_FORMAT_PCM = 1
            const val AUDIO_FORMAT_IEEE_FLOAT = 3
            const val AUDIO_FORMAT_EXTENSIBLE = 0xFFFE
        }
    }

    /**
     * 轻量解析 WAV 头部（遍历 RIFF chunk，取 `fmt ` 与 `data`）。
     *
     * 防御性要求：对畸形/截断/长度越界的 chunk 一律安全降级（返回 null 或部分信息），**绝不抛异常**。
     * 只解析已提供的 `headerBytes`，不读取完整音频数据。
     *
     * @return 解析成功返回 [WavInfo]；非 WAV 或缺少 `fmt ` 时返回 null。
     */
    fun parseWav(headerBytes: ByteArray): WavInfo? {
        if (!isRiffWave(headerBytes)) return null

        var offset = 12
        var fmt: FmtChunk? = null
        var dataBytes = -1L

        while (offset + CHUNK_HEADER_BYTES <= headerBytes.size) {
            val id = readAscii(headerBytes, offset, 4) ?: break
            val size = readUInt32LE(headerBytes, offset + 4) ?: break
            val bodyStart = offset + CHUNK_HEADER_BYTES

            when (id) {
                CHUNK_ID_FMT -> {
                    if (size < FMT_MIN_BYTES) return null
                    if (bodyStart + FMT_MIN_BYTES > headerBytes.size) return null
                    val audioFormat = readUInt16LE(headerBytes, bodyStart) ?: return null
                    fmt = FmtChunk(
                        audioFormat = audioFormat,
                        channels = readUInt16LE(headerBytes, bodyStart + 2) ?: return null,
                        sampleRate = readUInt32LE(headerBytes, bodyStart + 4)?.toInt() ?: return null,
                        byteRate = readUInt32LE(headerBytes, bodyStart + 8) ?: return null,
                        bitsPerSample = readUInt16LE(headerBytes, bodyStart + 14) ?: return null,
                        // EXTENSIBLE 需更长的 fmt（≥40）才能取到 SubFormat；越界/缺失时降级为 null（未知）。
                        subFormat = if (
                            audioFormat == WavInfo.AUDIO_FORMAT_EXTENSIBLE &&
                            size >= FMT_EXTENSIBLE_MIN_BYTES &&
                            bodyStart + SUBFORMAT_OFFSET + 4 <= headerBytes.size
                        ) {
                            readUInt32LE(headerBytes, bodyStart + SUBFORMAT_OFFSET)?.toInt()
                        } else {
                            null
                        }
                    )
                }

                CHUNK_ID_DATA -> {
                    dataBytes = size
                    break
                }
            }

            // 前进：id(4) + size(4) + data(size，奇数需 1 字节 padding 对齐)。
            val advance = CHUNK_HEADER_BYTES.toLong() + size + (size and 1L)
            if (advance <= 0L) break
            val nextOffset = offset.toLong() + advance
            if (nextOffset <= offset.toLong() || nextOffset > headerBytes.size.toLong()) break
            offset = nextOffset.toInt()
        }

        val parsed = fmt ?: return null
        return WavInfo(
            audioFormat = parsed.audioFormat,
            channels = parsed.channels,
            sampleRate = parsed.sampleRate,
            bitsPerSample = parsed.bitsPerSample,
            byteRate = parsed.byteRate,
            dataBytes = dataBytes,
            subFormat = parsed.subFormat
        )
    }

    // ---------------------------------------------------------------- 内部实现

    private data class FmtChunk(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Long,
        val bitsPerSample: Int,
        val subFormat: Int?
    )

    private const val CHUNK_HEADER_BYTES = 8
    private const val CHUNK_ID_FMT = "fmt "
    private const val CHUNK_ID_DATA = "data"
    private const val FMT_MIN_BYTES = 16L

    /** WAVEFORMATEXTENSIBLE 的最小 fmt 长度（WAVEFORMATEX 18 + wValidBits(2) + dwChannelMask(4) + GUID(16)）。 */
    private const val FMT_EXTENSIBLE_MIN_BYTES = 40L

    /** fmt 体起始到 SubFormat GUID 的偏移：18(WAVEFORMATEX) + 2(wValidBits) + 4(dwChannelMask)。 */
    private const val SUBFORMAT_OFFSET = 24

    private val WAV_MIMES = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave",
        "audio/x-pn-wav"
    )

    private val MP3_MIMES = setOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/mpeg3",
        "audio/x-mpeg-3",
        "audio/x-mp3"
    )

    private val UNKNOWN_MIMES = setOf(
        "",
        "application/octet-stream",
        "binary/octet-stream",
        "application/unknown",
        "application/x-unknown",
        "application/binary",
        "application/x-binary"
    )

    private fun extensionToFormat(ext: String?): DetectedFormat? = when (ext) {
        WAV_EXT -> WAV
        MP3_EXT -> MP3
        else -> null
    }

    private fun isRiffWave(bytes: ByteArray): Boolean =
        matchesAscii(bytes, 0, "RIFF") && matchesAscii(bytes, 8, "WAVE")

    private fun isMp3Header(bytes: ByteArray): Boolean {
        if (matchesAscii(bytes, 0, "ID3")) return true
        if (bytes.size < 2) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // MPEG 帧同步：11 位全 1 → 0xFF 后高 3 位为 1（即 0xFFEx / 0xFFFx）。
        return b0 == 0xFF && (b1 and 0xE0) == 0xE0
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset < 0 || offset + ascii.length > bytes.size) return false
        for (i in ascii.indices) {
            if (bytes[offset + i].toInt() and 0xFF != ascii[i].code) return false
        }
        return true
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset < 0 || offset + length > bytes.size) return null
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append((bytes[offset + i].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
