package com.yunian.ai.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FIX-2 / FIX-3 / FIX-5 回归单测（纯函数层，JVM 可测）。
 *
 *  - FIX-2：系统保留名（[StickerReservedNames]）可被识别、被拒（[stickerNameValidationError]）
 *    或被 ZIP 合并自动改名，杜绝「AI 可见却永远发不出」。
 *  - FIX-3：ZIP 入口统一清洗 description/semantic/aliases（剥方括号、换行、压缩空白、限长），
 *    保证名字里的 `[]` 不破坏 `[名字]` 解析；E2 行 `[name]=semantic（别名：…）` 不被破坏。
 *  - FIX-5：重名 description 消歧（[StickerRuleStore.dedupeDescriptions]），每个文件都可达。
 */
class StickerZipSanitizeAndReservedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): StickerRuleStore = StickerRuleStore(File(tmp.root, "rules.json"))

    // -------- FIX-2：保留名 --------

    @Test
    fun reservedNames_coverSystemTags() {
        listOf("语音", "图片", "视频", "文件", "位置", "红包", "转账").forEach {
            assertTrue("$it 应为保留名", StickerReservedNames.isReserved(it))
        }
        // trim 后匹配
        assertTrue(StickerReservedNames.isReserved("  红包  "))
        assertFalse(StickerReservedNames.isReserved("红包表情"))
        assertFalse(StickerReservedNames.isReserved("开心"))
    }

    @Test
    fun nameValidation_rejectsBlankAndReserved() {
        assertEquals("表情名称不能为空", stickerNameValidationError(""))
        assertEquals("表情名称不能为空", stickerNameValidationError("   "))
        val reservedErr = stickerNameValidationError("红包")
        assertTrue("保留名必须给出明确中文提示", reservedErr != null && reservedErr.contains("保留"))
        assertNull("普通名字应通过", stickerNameValidationError("仔细思考"))
    }

    @Test
    fun mergeZipRules_reservedName_autoRenamed() {
        val result = newStore().mergeZipRules(
            incoming = listOf(StickerRuleStore.Entry("红包", "hb.png", createdAt = 1L)),
            existing = emptyList(),
        )
        val entry = result.merged.single()
        assertNotEquals("红包", entry.description)
        assertFalse(StickerReservedNames.isReserved(entry.description))
        assertEquals("红包表情", entry.description)
        assertEquals("hb.png", entry.fileName) // 文件不受影响
        assertEquals(1, result.added)
    }

    // -------- FIX-3：ZIP 清洗 --------

    @Test
    fun mergeZipRules_stripsBracketsFromDescription() {
        val result = newStore().mergeZipRules(
            incoming = listOf(StickerRuleStore.Entry("开心[笑]", "a.png", createdAt = 1L)),
            existing = emptyList(),
        )
        val desc = result.merged.single().description
        assertFalse("方括号不得进入存储/提示词", desc.contains('[') || desc.contains(']'))
        assertEquals("开心笑", desc)
    }

    @Test
    fun mergeZipRules_sanitizesSemanticAndAliases() {
        val result = newStore().mergeZipRules(
            incoming = listOf(
                StickerRuleStore.Entry(
                    description = "裂开",
                    fileName = "a.png",
                    semantic = "崩\n溃[了]",
                    aliases = listOf("绷不住[了]", "泪\n目"),
                    createdAt = 1L,
                )
            ),
            existing = emptyList(),
        )
        val entry = result.merged.single()
        assertFalse(entry.semantic.contains('[') || entry.semantic.contains(']'))
        assertFalse(entry.semantic.contains('\n'))
        assertTrue(entry.aliases.all { !it.contains('[') && !it.contains(']') && !it.contains('\n') })
        // E2 行拼装不被破坏：`[name]=semantic（别名：…）` 无内嵌方括号/换行
        val line = CustomStickerPrompt.buildLines(
            listOf(PromptSticker(entry.description, entry.semantic, entry.aliases))
        ).single()
        assertFalse(line.contains('\n'))
        // 行首的 `[name]` 必须可被发送侧正则完整解析
        val parsed = Regex("\\[([^\\[\\]]+?)\\]").find(line)?.groupValues?.get(1)
        assertEquals("裂开", parsed)
    }

    @Test
    fun mergeZipRules_truncatesOverlongDescription() {
        val long = "超".repeat(80)
        val result = newStore().mergeZipRules(
            incoming = listOf(StickerRuleStore.Entry(long, "a.png", createdAt = 1L)),
            existing = emptyList(),
        )
        assertEquals(20, result.merged.single().description.length)
    }

    @Test
    fun buildLines_stripsUnsafeCharsFromName() {
        val lines = CustomStickerPrompt.buildLines(
            listOf(PromptSticker(name = "开心[笑]", semantic = "笑]了"))
        )
        val line = lines.single()
        assertEquals("[开心笑]=笑 了", line)
        // 恰好一对包围方括号（行首），无内嵌方括号
        assertEquals(1, line.count { it == '[' })
        assertEquals(1, line.count { it == ']' })
    }

    // -------- FIX-5：重名消歧 --------

    @Test
    fun dedupeDescriptions_makesEveryEntryReachable() {
        val entries = listOf(
            StickerRuleStore.Entry("仔细思考", "a.png", createdAt = 1L),
            StickerRuleStore.Entry("仔细思考", "b.png", createdAt = 2L),
            StickerRuleStore.Entry("仔细思考", "c.png", createdAt = 3L),
        )
        val deduped = newStore().dedupeDescriptions(entries)
        assertEquals(listOf("仔细思考", "仔细思考(2)", "仔细思考(3)"), deduped.map { it.description })
        // 文件名 / 其它字段不受影响
        assertEquals(listOf("a.png", "b.png", "c.png"), deduped.map { it.fileName })
        // 展示名两两不同 → 发送侧可按 description 反查到各自文件
        val names = StickerPromptNames.build(
            deduped.map {
                StickerInfo(
                    name = it.description, path = "/x/${it.fileName}", category = "imported",
                    isBuiltIn = false, description = it.description, fileName = it.fileName,
                )
            }
        )
        assertEquals(3, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun dedupeDescriptions_idempotent() {
        val entries = listOf(
            StickerRuleStore.Entry("开心", "a.png"),
            StickerRuleStore.Entry("开心", "b.png"),
        )
        val once = newStore().dedupeDescriptions(entries)
        val twice = newStore().dedupeDescriptions(once)
        assertEquals(once, twice)
    }

    /** load 路径对旧数据也消歧：重名 JSON 读入后每个 description 唯一。 */
    @Test
    fun load_dedupesLegacyDuplicateDescriptions() {
        val file = File(tmp.root, "custom_stickers.json")
        file.writeText(
            """[{"description":"仔细思考","fileName":"a.png"},{"description":"仔细思考","fileName":"b.png"}]"""
        )
        val loaded = StickerRuleStore(file).load()
        assertEquals(2, loaded.size)
        assertEquals(2, loaded.map { it.description }.toSet().size)
    }
}
