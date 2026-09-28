package com.yunian.ai.network.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MimoVoiceSampleFormat] 单元测试。
 *
 * 覆盖：MIME 别名归一、魔数嗅探、冲突处理（以魔数为准）、WAV 头解析（含畸形/截断/非 PCM）、
 * base64 体积换算边界。
 */
class MimoVoiceSampleFormatTest {

    // ------------------------------------------------------------ MIME 别名

    @Test
    fun mimeAlias_wavVariants_allResolveToWav() {
        val wavMimes = listOf(
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/vnd.wave",
            "audio/x-pn-wav",
            "AUDIO/WAV",
            "audio/x-wav;charset=utf-8",
            "  audio/wav  "
        )
        for (mime in wavMimes) {
            val detected = MimoVoiceSampleFormat.detect(ByteArray(0), mime, null)
            assertNotNull("$mime 应被识别为 wav", detected)
            assertEquals("$mime ext", MimoVoiceSampleFormat.WAV_EXT, detected!!.ext)
            assertEquals("$mime apiMime", MimoVoiceSampleFormat.MIME_WAV, detected.apiMime)
        }
    }

    @Test
    fun mimeAlias_mp3Variants_allResolveToMp3() {
        val mp3Mimes = listOf(
            "audio/mpeg",
            "audio/mp3",
            "audio/mpeg3",
            "audio/x-mpeg-3",
            "audio/x-mp3",
            "Audio/MPEG",
            "audio/mpeg; charset=UTF-8"
        )
        for (mime in mp3Mimes) {
            val detected = MimoVoiceSampleFormat.detect(ByteArray(0), mime, null)
            assertNotNull("$mime 应被识别为 mp3", detected)
            assertEquals("$mime ext", MimoVoiceSampleFormat.MP3_EXT, detected!!.ext)
            assertEquals("$mime apiMime", MimoVoiceSampleFormat.MIME_MP3, detected.apiMime)
        }
    }

    @Test
    fun mimeAlias_nullAndUnknown_deferAndReturnNullWithoutOtherHints() {
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), null, null))
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "", null))
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "application/octet-stream", null))
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "binary/octet-stream", null))
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "audio/ogg", null))
    }

    @Test
    fun extensionFallback_usedWhenMimeUnknown() {
        assertEquals(
            MimoVoiceSampleFormat.WAV_EXT,
            MimoVoiceSampleFormat.detect(ByteArray(0), "application/octet-stream", "sample.WAV")?.ext
        )
        assertEquals(
            MimoVoiceSampleFormat.MP3_EXT,
            MimoVoiceSampleFormat.detect(ByteArray(0), null, "/sdcard/voice/sample.mp3")?.ext
        )
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "application/octet-stream", "sample.ogg"))
    }

    @Test
    fun mimeAlias_wavpackMustNotBeTreatedAsWav() {
        // P2-1 回归：移除过宽通配后，WavPack 等含 "wav" 的 MIME 不得被误判为 WAV。
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "audio/wavpack", null))
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "audio/x-wavpack", null))
        assertNull(MimoVoiceSampleFormat.mimeToFormat("audio/wavpack"))
        assertNull(MimoVoiceSampleFormat.mimeToFormat("audio/x-wavpack"))
        // 收紧后真实 wav 别名不受影响。
        assertEquals(MimoVoiceSampleFormat.WAV, MimoVoiceSampleFormat.mimeToFormat("audio/x-pn-wav"))
    }

    // ------------------------------------------------------------ 魔数嗅探

    @Test
    fun magic_riffWave_isDetectedAsWav() {
        val header = buildPcmWav().copyOf(MimoVoiceSampleFormat.SNIFF_BYTES)
        assertEquals(MimoVoiceSampleFormat.WAV, MimoVoiceSampleFormat.sniffMagic(header))
    }

    @Test
    fun magic_id3_isDetectedAsMp3() {
        val header = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00)
        assertEquals(MimoVoiceSampleFormat.MP3, MimoVoiceSampleFormat.sniffMagic(header))
    }

    @Test
    fun magic_frameSync_isDetectedAsMp3() {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals(MimoVoiceSampleFormat.MP3, MimoVoiceSampleFormat.sniffMagic(header))
        val header2 = byteArrayOf(0xFF.toByte(), 0xFA.toByte(), 0x00, 0x00)
        assertEquals(MimoVoiceSampleFormat.MP3, MimoVoiceSampleFormat.sniffMagic(header2))
    }

    @Test
    fun magic_randomBytes_returnNull() {
        assertNull(MimoVoiceSampleFormat.sniffMagic(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)))
        assertNull(MimoVoiceSampleFormat.sniffMagic(ByteArray(0)))
    }

    // ------------------------------------------------------------ 冲突处理

    @Test
    fun conflict_declaredMp3ButWavBytes_magicWins() {
        val header = buildPcmWav().copyOf(MimoVoiceSampleFormat.SNIFF_BYTES)
        val detected = MimoVoiceSampleFormat.detect(header, "audio/mpeg", "fake.mp3")
        assertNotNull(detected)
        assertEquals(MimoVoiceSampleFormat.WAV_EXT, detected!!.ext)
        assertEquals(MimoVoiceSampleFormat.MIME_WAV, detected.apiMime)
    }

    @Test
    fun conflict_declaredWavButMp3Bytes_magicWins() {
        val header = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00)
        val detected = MimoVoiceSampleFormat.detect(header, "audio/wav", "fake.wav")
        assertNotNull(detected)
        assertEquals(MimoVoiceSampleFormat.MP3_EXT, detected!!.ext)
        assertEquals(MimoVoiceSampleFormat.MIME_MP3, detected.apiMime)
    }

    // ------------------------------------------------------------ WAV 头解析

    @Test
    fun parseWav_16bitPcmMono_parsesFields() {
        val info = MimoVoiceSampleFormat.parseWav(buildPcmWav(channels = 1, sampleRate = 16000, bitsPerSample = 16, dataSize = 3200))
        assertNotNull(info)
        info!!
        assertEquals(1, info.audioFormat)
        assertEquals(1, info.channels)
        assertEquals(16000, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals(32000L, info.byteRate)
        assertEquals(3200L, info.dataBytes)
        assertTrue(info.isPcm)
        assertEquals(0.1, info.durationSeconds, 1e-6)
        assertTrue(info.describe().contains("单声道"))
        assertTrue(info.describe().contains("16000Hz"))
    }

    @Test
    fun parseWav_oddSizedChunkIsPaddingAligned() {
        val info = MimoVoiceSampleFormat.parseWav(buildWavWithOddListChunk())
        assertNotNull(info)
        assertEquals(100L, info!!.dataBytes)
        assertEquals(16000, info.sampleRate)
        assertTrue(info.isPcm)
    }

    @Test
    fun parseWav_truncatedHeader_returnsNullOrNoThrow() {
        val truncated = buildPcmWav().copyOf(30)
        val result = runCatching { MimoVoiceSampleFormat.parseWav(truncated) }
        assertTrue("解析畸形头不得抛异常", result.isSuccess)
        assertNull(result.getOrNull())

        val random = runCatching { MimoVoiceSampleFormat.parseWav(byteArrayOf(1, 2, 3, 4, 5)) }
        assertTrue(random.isSuccess)
        assertNull(random.getOrNull())
    }

    @Test
    fun parseWav_missingDataChunk_yieldsUnknownDataSize() {
        // fmt 完整但 data chunk 不在提供的头部范围内。
        val header = buildPcmWav().copyOf(36)
        val info = MimoVoiceSampleFormat.parseWav(header)
        assertNotNull(info)
        assertEquals(-1L, info!!.dataBytes)
        assertTrue(info.describe().contains("时长未知"))
    }

    @Test
    fun parseWav_nonPcmEncoding_isFlagged() {
        val ieeeFloat = MimoVoiceSampleFormat.parseWav(buildPcmWav(audioFormat = 3))
        assertNotNull(ieeeFloat)
        assertFalse(ieeeFloat!!.isPcm)
        assertEquals("IEEE Float", ieeeFloat.formatName)

        val extensible = MimoVoiceSampleFormat.parseWav(buildPcmWav(audioFormat = 0xFFFE))
        assertNotNull(extensible)
        assertFalse(extensible!!.isPcm)
        assertEquals("WAVE_FORMAT_EXTENSIBLE", extensible.formatName)

        val adpcm = MimoVoiceSampleFormat.parseWav(buildPcmWav(audioFormat = 2))
        assertNotNull(adpcm)
        assertFalse(adpcm!!.isPcm)
    }

    @Test
    fun parseWav_extensibleWithPcmSubFormat_isPcm() {
        // P2-4：EXTENSIBLE 封装 16/24bit PCM 是合法 PCM，不应误报非 PCM。
        val info = MimoVoiceSampleFormat.parseWav(buildExtensibleWav(subFormat = 1L, bits = 24))
        assertNotNull(info)
        info!!
        assertEquals(0xFFFE, info.audioFormat)
        assertEquals(1, info.subFormat)
        assertEquals(MimoVoiceSampleFormat.WavInfo.PcmStatus.PCM, info.pcmStatus)
        assertTrue(info.isPcm)
        assertEquals("WAVE_FORMAT_EXTENSIBLE(PCM)", info.formatName)
        assertEquals(24, info.bitsPerSample)
    }

    @Test
    fun parseWav_extensibleWithFloatSubFormat_isNotPcm() {
        val info = MimoVoiceSampleFormat.parseWav(buildExtensibleWav(subFormat = 3L, bits = 32))
        assertNotNull(info)
        assertFalse(info!!.isPcm)
        assertEquals(MimoVoiceSampleFormat.WavInfo.PcmStatus.NOT_PCM, info.pcmStatus)
        assertEquals("WAVE_FORMAT_EXTENSIBLE(IEEE Float)", info.formatName)
    }

    @Test
    fun parseWav_extensibleWithoutSubFormat_isUnknownNotNonPcm() {
        // fmt 仅 16 字节、拿不到 SubFormat → 安全降级为 UNKNOWN（不得断言非 PCM）。
        val info = MimoVoiceSampleFormat.parseWav(buildPcmWav(audioFormat = 0xFFFE))
        assertNotNull(info)
        assertNull(info!!.subFormat)
        assertEquals(MimoVoiceSampleFormat.WavInfo.PcmStatus.UNKNOWN, info.pcmStatus)
        assertFalse(info.isPcm)
    }

    // ------------------------------------------------------------ 体积上限

    @Test
    fun base64EncodedLength_knownValues() {
        assertEquals(0L, MimoVoiceSampleFormat.base64EncodedLength(0))
        assertEquals(4L, MimoVoiceSampleFormat.base64EncodedLength(1))
        assertEquals(4L, MimoVoiceSampleFormat.base64EncodedLength(2))
        assertEquals(4L, MimoVoiceSampleFormat.base64EncodedLength(3))
        assertEquals(8L, MimoVoiceSampleFormat.base64EncodedLength(4))
    }

    @Test
    fun base64LimitBoundary_exactlyAtLimitIsAllowed() {
        val rawAtLimit = MimoVoiceSampleFormat.MAX_CLONE_RAW_BYTES
        assertEquals(
            MimoVoiceSampleFormat.MAX_CLONE_BASE64_BYTES,
            MimoVoiceSampleFormat.base64EncodedLength(rawAtLimit)
        )
        assertFalse(MimoVoiceSampleFormat.exceedsBase64Limit(rawAtLimit))
    }

    @Test
    fun base64LimitBoundary_oneByteOverIsRejected() {
        val rawOver = MimoVoiceSampleFormat.MAX_CLONE_RAW_BYTES + 1
        assertTrue(
            MimoVoiceSampleFormat.base64EncodedLength(rawOver) >
                MimoVoiceSampleFormat.MAX_CLONE_BASE64_BYTES
        )
        assertTrue(MimoVoiceSampleFormat.exceedsBase64Limit(rawOver))
    }

    @Test
    fun cloneLimit_isEquivalentToBase64LimitAcrossBoundary() {
        // P2-3：MAX_CLONE_RAW_BYTES 由 MAX_CLONE_BASE64_BYTES 推导，两判据须完全等价。
        val boundary = MimoVoiceSampleFormat.MAX_CLONE_RAW_BYTES
        assertEquals(7_864_320L, boundary) // 锁定推导值，防止 limit 变更时静默漂移

        val probes = mutableListOf(0L, 1L, 2L, 3L)
        for (d in -64L..64L) probes += boundary + d
        for (raw in probes) {
            assertEquals(
                "raw=$raw 两判据须等价",
                MimoVoiceSampleFormat.exceedsBase64Limit(raw),
                MimoVoiceSampleFormat.exceedsCloneLimit(raw)
            )
        }

        // 全域等距抽样
        var raw = 0L
        while (raw < boundary) {
            assertEquals(
                "raw=$raw",
                MimoVoiceSampleFormat.exceedsBase64Limit(raw),
                MimoVoiceSampleFormat.exceedsCloneLimit(raw)
            )
            raw += 100_003L
        }

        assertFalse(MimoVoiceSampleFormat.exceedsCloneLimit(boundary))
        assertTrue(MimoVoiceSampleFormat.exceedsCloneLimit(boundary + 1))
    }

    @Test
    fun tooLargeMessage_containsConcreteNumbers() {
        val raw = 8_600_000L
        val msg = MimoVoiceSampleFormat.tooLargeMessage(raw)
        assertTrue(msg.contains("上限 10MB"))
        assertTrue(msg.contains(MimoVoiceSampleFormat.formatMegabytes(raw)))
        assertTrue(msg.contains(MimoVoiceSampleFormat.formatMegabytes(MimoVoiceSampleFormat.base64EncodedLength(raw))))
    }

    // ------------------------------------------------------------ 提示文案

    @Test
    fun unsupportedMessage_distinguishesUnknownAndUnsupported() {
        val unknown = MimoVoiceSampleFormat.unsupportedMessage("application/octet-stream", "a.bin")
        assertTrue(unknown.contains("无法识别"))

        val unsupported = MimoVoiceSampleFormat.unsupportedMessage("audio/ogg", "a.ogg")
        assertTrue(unsupported.contains("audio/ogg"))
        assertTrue(unsupported.contains("仅支持 mp3 / wav"))
    }

    // ------------------------------------------------------------ 构造工具

    private fun buildPcmWav(
        channels: Int = 1,
        sampleRate: Int = 16000,
        bitsPerSample: Int = 16,
        dataSize: Int = 3200,
        audioFormat: Int = 1
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArray(44 + dataSize)
        writeAscii(out, 0, "RIFF")
        writeUInt32LE(out, 4, (36 + dataSize).toLong())
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeUInt32LE(out, 16, 16L)
        writeUInt16LE(out, 20, audioFormat)
        writeUInt16LE(out, 22, channels)
        writeUInt32LE(out, 24, sampleRate.toLong())
        writeUInt32LE(out, 28, byteRate.toLong())
        writeUInt16LE(out, 32, blockAlign)
        writeUInt16LE(out, 34, bitsPerSample)
        writeAscii(out, 36, "data")
        writeUInt32LE(out, 40, dataSize.toLong())
        return out
    }

    /** 含一个奇数长度 LIST chunk（需 1 字节 padding 对齐）的最小 WAV 头。 */
    private fun buildWavWithOddListChunk(): ByteArray {
        val out = ByteArray(58)
        writeAscii(out, 0, "RIFF")
        writeUInt32LE(out, 4, (58 - 8).toLong())
        writeAscii(out, 8, "WAVE")
        // LIST chunk，size = 5（奇数）→ 数据 5 字节 + 1 字节 padding。
        writeAscii(out, 12, "LIST")
        writeUInt32LE(out, 16, 5L)
        // fmt chunk 位于偏移 26。
        writeAscii(out, 26, "fmt ")
        writeUInt32LE(out, 30, 16L)
        writeUInt16LE(out, 34, 1)       // PCM
        writeUInt16LE(out, 36, 1)       // mono
        writeUInt32LE(out, 38, 16000L)  // sample rate
        writeUInt32LE(out, 42, 32000L)  // byte rate
        writeUInt16LE(out, 46, 2)       // block align
        writeUInt16LE(out, 48, 16)      // bits per sample
        // data chunk 位于偏移 50。
        writeAscii(out, 50, "data")
        writeUInt32LE(out, 54, 100L)
        return out
    }

    /** 构造 WAVE_FORMAT_EXTENSIBLE 样本（fmt 40 字节，含 16 字节 SubFormat GUID）。 */
    private fun buildExtensibleWav(subFormat: Long, bits: Int = 16): ByteArray {
        val fmt = ByteArray(40)
        writeUInt16LE(fmt, 0, 0xFFFE)   // wFormatTag = EXTENSIBLE
        writeUInt16LE(fmt, 2, 1)        // mono
        writeUInt32LE(fmt, 4, 16000L)   // sample rate
        writeUInt32LE(fmt, 8, 32000L)   // byte rate
        writeUInt16LE(fmt, 12, 2)       // block align
        writeUInt16LE(fmt, 14, bits)    // bits per sample
        writeUInt16LE(fmt, 16, 22)      // cbSize
        writeUInt16LE(fmt, 18, bits)    // wValidBitsPerSample
        writeUInt32LE(fmt, 20, 0L)      // dwChannelMask
        writeUInt32LE(fmt, 24, subFormat) // SubFormat GUID 前 4 字节（小端）
        val guidTail = byteArrayOf(
            0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00,
            0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
        )
        System.arraycopy(guidTail, 0, fmt, 28, guidTail.size)

        val dataSize = 3200
        val out = ByteArray(12 + 8 + 40 + 8 + dataSize)
        writeAscii(out, 0, "RIFF")
        writeUInt32LE(out, 4, (out.size - 8).toLong())
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeUInt32LE(out, 16, 40L)
        System.arraycopy(fmt, 0, out, 20, fmt.size)
        writeAscii(out, 60, "data")
        writeUInt32LE(out, 64, dataSize.toLong())
        return out
    }

    private fun writeAscii(target: ByteArray, offset: Int, text: String) {
        for (i in text.indices) {
            target[offset + i] = text[i].code.toByte()
        }
    }

    private fun writeUInt16LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeUInt32LE(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
