package com.yunian.ai.network.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Random

/**
 * 对抗性验证（QA 独立编写，非工程师交付物）。
 *
 * 目标：用畸形 / 恶意 / 随机输入证伪"安全降级"与"不发生死循环 / 越界"的承诺，
 * 并对 base64 上限数学做与 JDK 编码器的交叉核对。
 */
class MimoVoiceSampleFormatAdversarialTest {

    // ------------------------------------------------------------ WAV 构造工具

    private fun u32(v: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    private fun u16(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun chunk(id: String, declaredSize: Long, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + u32(declaredSize) + data

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, b -> acc + b }
        return "RIFF".toByteArray(Charsets.US_ASCII) +
            u32((4 + body.size).toLong()) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            body
    }

    private fun fmtBody(
        audioFormat: Int,
        channels: Int,
        sampleRate: Int,
        byteRate: Long,
        blockAlign: Int,
        bits: Int
    ): ByteArray = u16(audioFormat) + u16(channels) + u32(sampleRate.toLong()) +
        u32(byteRate) + u16(blockAlign) + u16(bits)

    // ============================================================ parseWav 健壮性

    @Test(timeout = 5_000)
    fun parseWav_thousandsOfZeroSizeChunks_terminates() {
        // 8000 个 size=0 的未知 chunk：若前进量依赖 size 递增则死循环——此处必须快速退出。
        val zeroChunk = "JUNK".toByteArray(Charsets.US_ASCII) + u32(0L)
        val body = ByteArray(8_000 * 8) { zeroChunk[it % 8] }
        val wav = "RIFF".toByteArray(Charsets.US_ASCII) +
            u32((4 + body.size).toLong()) + "WAVE".toByteArray(Charsets.US_ASCII) + body

        val t0 = System.nanoTime()
        val info = MimoVoiceSampleFormat.parseWav(wav)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertNull("无 fmt chunk 应返回 null", info)
        assertTrue("必须快速终止，实际 ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test(timeout = 5_000)
    fun parseWav_maxUInt32ChunkSize_terminatesWithoutThrow() {
        val wav = riff(chunk("JUNK", 0xFFFFFFFFL, ByteArray(4)))
        assertNull(MimoVoiceSampleFormat.parseWav(wav))
    }

    @Test(timeout = 5_000)
    fun parseWav_fmtChunkHugeDeclaredSize_safelyDegrades() {
        // fmt 声明 4GB，但实际只有 16 字节 fmt 体：实现读取可用字节后因前进越界而退出，
        // 属"安全降级"（不越界、不抛异常），返回可用的 fmt 信息。
        val wav = riff(chunk("fmt ", 0xFFFFFFFFL, fmtBody(1, 1, 16000, 32000, 2, 16)))
        val info = runCatching { MimoVoiceSampleFormat.parseWav(wav) }.getOrNull()
        assertNotNull(info)
        assertEquals(16_000, info!!.sampleRate)
        assertEquals(-1L, info.dataBytes)
    }

    @Test(timeout = 5_000)
    fun parseWav_fmtChunkShorterThan16_returnsNull() {
        val wav = riff(chunk("fmt ", 15L, ByteArray(15)))
        assertNull(MimoVoiceSampleFormat.parseWav(wav))
    }

    @Test(timeout = 5_000)
    fun parseWav_fmtDeclared16ButBodyTruncated_returnsNull() {
        val wav = "RIFF".toByteArray(Charsets.US_ASCII) + u32(100L) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            "fmt ".toByteArray(Charsets.US_ASCII) + u32(16L) + ByteArray(4)
        assertNull(MimoVoiceSampleFormat.parseWav(wav))
    }

    @Test(timeout = 5_000)
    fun parseWav_dataBeforeFmt_returnsNull() {
        val wav = riff(chunk("data", 100L, ByteArray(100)))
        assertNull("fmt 缺失时应返回 null", MimoVoiceSampleFormat.parseWav(wav))
    }

    @Test(timeout = 5_000)
    fun parseWav_fmtThenDataWithLyingDataSize_returnsDeclaredSize() {
        val wav = riff(
            chunk("fmt ", 16L, fmtBody(1, 1, 16000, 32000, 2, 16)),
            chunk("data", 3_200L, ByteArray(0))
        )
        val info = MimoVoiceSampleFormat.parseWav(wav)
        assertNotNull(info)
        assertEquals(3_200L, info!!.dataBytes)
        assertEquals(16_000, info.sampleRate)
    }

    @Test(timeout = 5_000)
    fun parseWav_chunkAdvanceLandsExactlyAtEnd_noThrow() {
        // LIST(size=0) 使下一个 chunk 头正好落在缓冲区末尾——不得越界读取。
        val wav = riff(chunk("LIST", 0L, ByteArray(0)), chunk("fmt ", 16L, fmtBody(1, 1, 8000, 16000, 2, 16)))
        val info = MimoVoiceSampleFormat.parseWav(wav)
        assertNotNull(info)
        assertEquals(8_000, info!!.sampleRate)
        assertEquals(-1L, info.dataBytes)
    }

    @Test(timeout = 15_000)
    fun fuzz_randomHeaders_neverThrowAndTerminate() {
        val rnd = Random(42)
        repeat(200_000) {
            val len = rnd.nextInt(0, 80)
            val b = ByteArray(len).also { rnd.nextBytes(it) }
            MimoVoiceSampleFormat.sniffMagic(b)
            MimoVoiceSampleFormat.detect(b, "application/octet-stream", "x.bin")
            MimoVoiceSampleFormat.detect(b, null, null)
            MimoVoiceSampleFormat.parseWav(b)
            MimoVoiceSampleFormat.extensionOf("a/b.$it")
            MimoVoiceSampleFormat.base64EncodedLength(rnd.nextLong(0, 20_000_000))
        }
    }

    // ============================================================ 魔数误判率

    @Test
    fun sniffMagic_frameSyncFalsePositiveRate_isAcceptable() {
        val rnd = Random(7)
        val n = 500_000
        var hits = 0
        repeat(n) {
            val b = byteArrayOf(rnd.nextInt(256).toByte(), rnd.nextInt(256).toByte())
            if (MimoVoiceSampleFormat.sniffMagic(b) == MimoVoiceSampleFormat.MP3) hits++
        }
        val rate = hits.toDouble() / n
        println("[adversarial] 随机 2 字节被判为 MP3 的命中率 = $hits/$n = $rate")
        assertTrue("误判率应低于 0.2%，实际 $rate", rate < 0.002)
    }

    @Test
    fun sniffMagic_frameSync_bitBoundary() {
        assertEquals(
            MimoVoiceSampleFormat.MP3,
            MimoVoiceSampleFormat.sniffMagic(byteArrayOf(0xFF.toByte(), 0xE0.toByte()))
        )
        assertNull(
            "0xFF 0xDF 高 3 位不全为 1，不应判为 MP3",
            MimoVoiceSampleFormat.sniffMagic(byteArrayOf(0xFF.toByte(), 0xDF.toByte()))
        )
    }

    // ============================================================ base64 数学交叉核对

    @Test
    fun base64EncodedLength_matchesJdkEncoder() {
        val lens = listOf(
            0L, 1, 2, 3, 4, 5, 6, 7, 100, 999, 1_000,
            7_864_319, 7_864_320, 7_864_321, 10_485_759, 10_485_760
        )
        for (len in lens) {
            val real = Base64.getEncoder().encodeToString(ByteArray(len.toInt())).length.toLong()
            assertEquals("raw=$len 与 JDK 编码器不一致", real, MimoVoiceSampleFormat.base64EncodedLength(len))
        }
    }

    @Test
    fun base64Limit_boundaryBehaviour() {
        val maxRaw = MimoVoiceSampleFormat.MAX_CLONE_RAW_BYTES
        // 原始上限处，编码后恰好等于 10MB —— 允许
        assertEquals(MimoVoiceSampleFormat.MAX_CLONE_BASE64_BYTES, MimoVoiceSampleFormat.base64EncodedLength(maxRaw))
        assertFalse(MimoVoiceSampleFormat.exceedsBase64Limit(maxRaw))
        assertFalse(MimoVoiceSampleFormat.exceedsBase64Limit(maxRaw - 1))
        // 超 1 字节 —— 拒绝
        assertTrue(MimoVoiceSampleFormat.exceedsBase64Limit(maxRaw + 1))
        // 余量核验：原始上限换算后的 base64 长度确实贴着 10MB（不越界）
        assertTrue(MimoVoiceSampleFormat.base64EncodedLength(maxRaw) <= MimoVoiceSampleFormat.MAX_CLONE_BASE64_BYTES)
    }

    // ============================================================ MIME 归一化细节

    @Test
    fun mimeAlias_extendedCaseAndParamVariants() {
        val empty = ByteArray(0)
        assertEquals(MimoVoiceSampleFormat.WAV, MimoVoiceSampleFormat.detect(empty, "AUDIO/X-WAV; charset=utf-8", null))
        assertEquals(MimoVoiceSampleFormat.WAV, MimoVoiceSampleFormat.detect(empty, "Audio/Vnd.Wave", null))
        assertEquals(MimoVoiceSampleFormat.WAV, MimoVoiceSampleFormat.detect(empty, "audio/x-pn-wav ;foo=bar", null))
        assertEquals(MimoVoiceSampleFormat.MP3, MimoVoiceSampleFormat.detect(empty, "audio/MPEG3", null))
        assertEquals(MimoVoiceSampleFormat.MP3, MimoVoiceSampleFormat.detect(empty, "audio/x-MP3", null))
        assertNull(MimoVoiceSampleFormat.detect(empty, "application/octet-stream", null))
    }

    @Test
    fun extensionOf_pathAndDotEdgeCases() {
        assertEquals("wav", MimoVoiceSampleFormat.extensionOf("/sdcard/a/b/sample.WAV"))
        assertEquals("mp3", MimoVoiceSampleFormat.extensionOf("C:\\Users\\x\\v.mp3"))
        assertNull(MimoVoiceSampleFormat.extensionOf("noext"))
        assertNull(MimoVoiceSampleFormat.extensionOf("trailing."))
        assertNull(MimoVoiceSampleFormat.extensionOf(null))
        assertEquals("gz", MimoVoiceSampleFormat.extensionOf("archive.tar.gz"))
    }

    /**
     * P2-1 回归护栏：曾因「audio/ 前缀且包含 wav」的通配启发式把真实存在的 WavPack 编码
     * （`audio/wavpack`、`audio/x-wavpack`，魔数 `wvpk` 不被 sniffMagic 命中）误判为 WAV。
     * 收紧为精确集合匹配后必须拒绝，且不得影响真实 wav 别名。
     */
    @Test
    fun regression_wavpackMustNotBeAcceptedAsWav() {
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "audio/wavpack", null))
        assertNull(MimoVoiceSampleFormat.detect(ByteArray(0), "audio/x-wavpack", null))
        assertEquals(
            MimoVoiceSampleFormat.WAV,
            MimoVoiceSampleFormat.detect(ByteArray(0), "audio/x-pn-wav", null)
        )
    }

    // ============================================================ EXTENSIBLE 边界（QA 第二轮追加）

    private fun extFmtBytes(actualBodyBytes: Int, subFormat: Long?): ByteArray {
        val b = ByteArray(actualBodyBytes)
        fun put16(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        fun put32(off: Int, v: Long) {
            for (i in 0 until 4) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }
        put16(0, 0xFFFE); put16(2, 1); put32(4, 16000L); put32(8, 32000L); put16(12, 2); put16(14, 16)
        if (subFormat != null && actualBodyBytes >= 28) put32(24, subFormat)
        return b
    }

    @Test(timeout = 5_000)
    fun parseWav_extensibleSize39_isUnknownNotNonPcm() {
        // 声明 size=39 < 40：无法安全取 SubFormat → 必须 UNKNOWN，绝不判 NOT_PCM。
        val info = MimoVoiceSampleFormat.parseWav(riff(chunk("fmt ", 39L, extFmtBytes(39, 1L))))
        assertNotNull(info)
        assertEquals(MimoVoiceSampleFormat.WavInfo.PcmStatus.UNKNOWN, info!!.pcmStatus)
        assertFalse(info.isPcm)
    }

    @Test(timeout = 5_000)
    fun parseWav_extensibleDeclared40ButBodyTruncated_isUnknown() {
        // 声明 size=40 但实际只有 20 字节体：不得越界读取 GUID → UNKNOWN。
        val info = MimoVoiceSampleFormat.parseWav(riff(chunk("fmt ", 40L, extFmtBytes(20, null))))
        assertNotNull(info)
        assertEquals(MimoVoiceSampleFormat.WavInfo.PcmStatus.UNKNOWN, info!!.pcmStatus)
        assertFalse(info.isPcm)
    }

    @Test(timeout = 5_000)
    fun parseWav_extensibleHugeDeclaredSize_withRealPcmGuid_noThrow() {
        // 声明 size 荒谬（4GB）但 40 字节体真实存在且 GUID=PCM：安全取到 SubFormat → PCM，不抛异常。
        val info = MimoVoiceSampleFormat.parseWav(riff(chunk("fmt ", 0xFFFFFFFFL, extFmtBytes(40, 1L))))
        assertNotNull(info)
        assertEquals(MimoVoiceSampleFormat.WavInfo.PcmStatus.PCM, info!!.pcmStatus)
        assertTrue(info.isPcm)
    }

    @Test(timeout = 20_000)
    fun exceedsCloneLimit_equivalentToBase64Limit_acrossLargeDomain() {
        // P2-3：两判据必须在全体正常取值上等价。数学上 base64(n)=4*ceil(n/3)，
        // 4*ceil(n/3) > 4*2621440 ⇔ n > 7864320 恰为同一条边界，故严格等价。
        fun agree(n: Long) =
            MimoVoiceSampleFormat.exceedsBase64Limit(n) == MimoVoiceSampleFormat.exceedsCloneLimit(n)
        val b = MimoVoiceSampleFormat.MAX_CLONE_RAW_BYTES
        var i = 0L
        while (i <= 100_000L) { assertTrue("raw=$i", agree(i)); i++ }
        i = b - 100_000L
        while (i <= b + 100_000L) { assertTrue("raw=$i", agree(i)); i++ }
        val rnd = Random(99)
        repeat(500_000) {
            val n = rnd.nextLong(0, 1L shl 62)
            assertTrue("raw=$n", agree(n))
        }
    }
}
