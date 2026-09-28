package com.yunian.ai.feature.settings.ui.screen

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [cleanupOldCloneSamples] 单元测试。
 *
 * 覆盖 FIX-1/FIX-2 的核心不变量：**keep 之外的旧样本（含崩溃残留 tmp）被删，keep 本身存活**，
 * 且无关文件不受影响。该函数仅依赖 `java.io.File`，可在 JVM 单测直接运行。
 */
class MimoCloneSampleCleanupTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("mimo-clone-cleanup").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(name: String): File = File(dir, name).apply { writeText("x") }

    @Test
    fun cleanup_removesOldSamples_keepsSignalledNames() {
        val keepNew = touch("mimo_clone_sample_2000.wav")
        val keepReferenced = touch("mimo_clone_sample_1000.wav")
        val oldOrphan = touch("mimo_clone_sample_500.wav")
        val staleTmp = touch("mimo_clone_sample.tmp")
        val unrelated = touch("unrelated.txt")

        cleanupOldCloneSamples(dir, keepNames = setOf(keepNew.name, keepReferenced.name))

        assertTrue("keep（新样本）必须存活", keepNew.exists())
        assertTrue("keep（清理时仍被引用的旧样本）必须存活", keepReferenced.exists())
        assertFalse("其余旧样本应被清理", oldOrphan.exists())
        assertFalse("崩溃残留 tmp 应被清理", staleTmp.exists())
        assertTrue("非样本文件不得被删", unrelated.exists())
    }

    @Test
    fun cleanup_emptyKeepNames_removesAllPrefixedSamplesButKeepsUnrelated() {
        val sample = touch("mimo_clone_sample_1.wav")
        val tmp = touch("mimo_clone_sample.tmp")
        val unrelated = touch("note.mp3")

        cleanupOldCloneSamples(dir, keepNames = emptySet())

        assertFalse(sample.exists())
        assertFalse(tmp.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun cleanup_missingDir_doesNotThrow() {
        val missing = File(dir, "does-not-exist")
        // 目录不存在时应安全降级，不抛异常。
        cleanupOldCloneSamples(missing, keepNames = emptySet())
        assertFalse(missing.exists())
    }
}
