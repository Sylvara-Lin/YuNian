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
 * StickerRuleStore JVM 单测：JSON v2 兼容旧数据、zip 合并不丢旧规则、原子写、别名索引、v2 字段默认值。
 * 纯 Kotlin 实现（不依赖 android.*），可直接跑在 JVM 上。
 */
class StickerRuleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): StickerRuleStore = StickerRuleStore(File(tmp.root, "custom_stickers.json"))

    // ① 旧 JSON（仅 description+fileName）加载行为不变
    @Test
    fun load_v1Json_withoutNewFields_usesDefaults() {
        val file = File(tmp.root, "custom_stickers.json")
        file.writeText("""[{"description":"裂开","fileName":"a.png"},{"description":"mua","fileName":"b.jpg"}]""")
        val entries = StickerRuleStore(file).load()

        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals("裂开", first.description)
        assertEquals("a.png", first.fileName)
        assertEquals("", first.semantic)
        assertEquals(emptyList<String>(), first.aliases)
        assertEquals(0L, first.createdAt)
        assertEquals(StickerRuleStore.SOURCE_FILE, first.source)
    }

    // v2 字段默认值：个别字段缺失时按默认值解析
    @Test
    fun load_v2Json_partialFields_usesDefaultsForMissing() {
        val file = File(tmp.root, "custom_stickers.json")
        file.writeText(
            """[{"description":"泪目","fileName":"c.webp","semantic":"感动时用","createdAt":123}]"""
        )
        val entries = StickerRuleStore(file).load()

        assertEquals(1, entries.size)
        assertEquals("泪目", entries[0].description)
        assertEquals("感动时用", entries[0].semantic)
        assertEquals(emptyList<String>(), entries[0].aliases)
        assertEquals(123L, entries[0].createdAt)
        assertEquals(StickerRuleStore.SOURCE_FILE, entries[0].source)
    }

    @Test
    fun load_missingOrBrokenFile_returnsEmpty() {
        assertEquals(emptyList<StickerRuleStore.Entry>(), StickerRuleStore(File(tmp.root, "not_exist.json")).load())

        val broken = File(tmp.root, "broken.json")
        broken.writeText("""[{"description":""")
        assertEquals(emptyList<StickerRuleStore.Entry>(), StickerRuleStore(broken).load())
    }

    // ② ZIP 二次导入合并不丢旧规则（修 P2）
    @Test
    fun mergeZipRules_sameDescription_keepsOldRuleAndFile() {
        val old = listOf(
            StickerRuleStore.Entry("裂开", "old.png", semantic = "崩溃时用", aliases = listOf("绷不住了"), createdAt = 100L),
            StickerRuleStore.Entry("mua", "kiss.png", createdAt = 101L),
        )
        val incoming = listOf(
            // 同名：应被跳过，保留旧文件与旧规则
            StickerRuleStore.Entry("裂开", "new.png", semantic = "别的语义", createdAt = 999L),
            // 新名称：应被追加
            StickerRuleStore.Entry("无语", "wuyu.png", createdAt = 998L),
        )

        val result = StickerRuleStore(File(tmp.root, "rules.json")).mergeZipRules(incoming, old)

        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
        assertEquals(3, result.merged.size)
        val kept = result.merged.first { it.description == "裂开" }
        assertEquals("old.png", kept.fileName)          // 旧文件不被覆盖
        assertEquals("崩溃时用", kept.semantic)          // 旧语义保留
        assertEquals(listOf("绷不住了"), kept.aliases)   // 旧别名保留
        assertEquals(100L, kept.createdAt)
        // 新增条目标记为 zip 来源
        val added = result.merged.first { it.description == "无语" }
        assertEquals(StickerRuleStore.SOURCE_ZIP, added.source)
    }

    @Test
    fun mergeZipRules_blankEntries_skipped() {
        val result = StickerRuleStore(File(tmp.root, "rules.json")).mergeZipRules(
            incoming = listOf(
                StickerRuleStore.Entry("", "no_desc.png"),
                StickerRuleStore.Entry("有名字", ""),
            ),
            existing = emptyList(),
        )
        assertEquals(0, result.merged.size)
        assertEquals(0, result.added)
    }

    @Test
    fun mergeZipRules_zeroCreatedAt_normalizedToNowAndZipSource() {
        val result = StickerRuleStore(File(tmp.root, "rules.json")).mergeZipRules(
            incoming = listOf(StickerRuleStore.Entry("旧包表情", "x.png")),
            existing = emptyList(),
        )
        val entry = result.merged.single()
        assertTrue(entry.createdAt > 0L)
        assertEquals(StickerRuleStore.SOURCE_ZIP, entry.source)
    }

    // ③ 原子写：tmp + rename，写后无 .tmp 残留，二次写以新内容为准
    @Test
    fun save_atomicWrite_replacesFileAndCleansTmp() {
        val store = newStore()
        assertTrue(store.save(listOf(StickerRuleStore.Entry("第一版", "a.png", createdAt = 1L))))
        assertFalse(File(tmp.root, "custom_stickers.json.tmp").exists())

        assertTrue(store.save(listOf(StickerRuleStore.Entry("第二版", "b.png", createdAt = 2L))))
        val reloaded = store.load()
        assertEquals(1, reloaded.size)
        assertEquals("第二版", reloaded[0].description)
        assertNotEquals("第一版", reloaded[0].description)
        assertFalse(File(tmp.root, "custom_stickers.json.tmp").exists())
    }

    @Test
    fun save_roundtrip_preservesV2Fields() {
        val store = newStore()
        val entries = listOf(
            StickerRuleStore.Entry("裂开", "a.png", semantic = "委屈、崩溃", aliases = listOf("绷不住了", "泪目"), createdAt = 42L, source = StickerRuleStore.SOURCE_ZIP),
        )
        assertTrue(store.save(entries))
        assertEquals(entries, store.load())
    }

    // ④ 别名索引
    @Test
    fun buildAliasIndex_mapsAliasToDescription() {
        val store = newStore()
        val index = store.buildAliasIndex(
            listOf(
                StickerRuleStore.Entry("裂开", "a.png", aliases = listOf("绷不住了", "泪目")),
                StickerRuleStore.Entry("mua", "b.png", aliases = listOf("亲亲")),
            )
        )
        assertEquals("裂开", index["绷不住了"])
        assertEquals("裂开", index["泪目"])
        assertEquals("mua", index["亲亲"])
        assertNull(index["不存在"])
    }

    @Test
    fun buildAliasIndex_blankAliasIgnored() {
        val store = newStore()
        val index = store.buildAliasIndex(
            listOf(StickerRuleStore.Entry("裂开", "a.png", aliases = listOf("", "  ")))
        )
        assertTrue(index.isEmpty())
    }

    // E2 提示词预算：≤30 条、语义 ≤40 字、总长 ≤1200 字符，超限按传入顺序（新→旧）截断
    @Test
    fun buildLines_respectsCountAndCharBudget() {
        val many = (1..50).map { PromptSticker(name = "表情$it", semantic = "语义$it") }
        val lines = CustomStickerPrompt.buildLines(many)
        assertEquals(30, lines.size)
        assertEquals("[表情1]=语义1", lines.first())   // 传入顺序即新→旧
        assertEquals("[表情30]=语义30", lines.last())
    }

    @Test
    fun buildLines_truncatesSemanticAndKeepsBudget() {
        val one = listOf(PromptSticker(name = "裂开", semantic = "崩".repeat(100)))
        val lines = CustomStickerPrompt.buildLines(one)
        assertEquals("[裂开]=${"崩".repeat(40)}", lines.single())
    }

    @Test
    fun buildLines_emptyInput_returnsEmpty() {
        assertTrue(CustomStickerPrompt.buildLines(emptyList()).isEmpty())
    }

    @Test
    fun buildLines_withAliases_formatted() {
        val lines = CustomStickerPrompt.buildLines(
            listOf(PromptSticker(name = "裂开", semantic = "崩溃", aliases = listOf("绷不住了", "泪目")))
        )
        assertEquals("[裂开]=崩溃（别名：绷不住了、泪目）", lines.single())
    }

    // 【QA 补充】整段 1200 字符预算：单条放不下时整条丢弃（break），不得超预算输出
    @Test
    fun buildLines_overallCharBudget_stopsWithinLimit() {
        // 每条 ≈50 字符，30 条共 ≈1500 字符，超过 1200 上限（条数 30 不会先触发）
        val many = (1..30).map { PromptSticker(name = "超长名称表情包$it", semantic = "语".repeat(40)) }
        val lines = CustomStickerPrompt.buildLines(many)
        assertTrue(lines.size < 30)
        assertTrue(lines.size > 1)
        val total = lines.sumOf { it.length }
        assertTrue("total=$total should be <= ${CustomStickerPrompt.MAX_CHARS}", total <= CustomStickerPrompt.MAX_CHARS)
        // 贪心填充：第一条被丢弃的行必然超剩余预算（非部分截断）
        val nextIndex = lines.size + 1
        val nextLine = "[超长名称表情包$nextIndex]=${"语".repeat(40)}"
        assertTrue("total=$total + next=${nextLine.length} should exceed MAX_CHARS", total + nextLine.length > CustomStickerPrompt.MAX_CHARS)
    }

    // 【QA 补充】别名跨条目冲突：先出现的条目优先（putIfAbsent 语义）
    @Test
    fun buildAliasIndex_duplicateAliasFirstEntryWins() {
        val index = newStore().buildAliasIndex(
            listOf(
                StickerRuleStore.Entry("表情甲", "a.png", aliases = listOf("抱抱")),
                StickerRuleStore.Entry("表情乙", "b.png", aliases = listOf("抱抱")),
            )
        )
        assertEquals("表情甲", index["抱抱"])
        assertEquals(1, index.size)
    }

    // 【QA 补充】同一 ZIP 内部出现重复 description：只收第一条，不重复计数
    @Test
    fun mergeZipRules_duplicateDescriptionsWithinIncoming_onlyFirstAdded() {
        val result = newStore().mergeZipRules(
            incoming = listOf(
                StickerRuleStore.Entry("裂开", "first.png", createdAt = 1L),
                StickerRuleStore.Entry("裂开", "second.png", createdAt = 2L),
            ),
            existing = emptyList(),
        )
        assertEquals(1, result.added)
        // 同 ZIP 内部重复也计入 skipped（不重复落表，只保留第一条）
        assertEquals(1, result.skipped)
        assertEquals(1, result.merged.size)
        assertEquals("first.png", result.merged.single().fileName)
    }

    // 【回归】按 fileName 建索引：即使 description 重名，每个文件仍能定位到**自己的**条目
    @Test
    fun buildFileNameIndex_keyedByFileName_evenWithDuplicateDescriptions() {
        val index = newStore().buildFileNameIndex(
            listOf(
                StickerRuleStore.Entry("仔细思考", "custom_1_1.png", createdAt = 1L),
                StickerRuleStore.Entry("仔细思考", "custom_2_2.png", createdAt = 2L), // 重名
            )
        )
        assertEquals(2, index.size)
        assertEquals("custom_1_1.png", index["custom_1_1.png"]?.fileName)
        assertEquals("custom_2_2.png", index["custom_2_2.png"]?.fileName)
        assertEquals(2L, index["custom_2_2.png"]?.createdAt)
    }

    @Test
    fun buildFileNameIndex_blankFileNameIgnored() {
        val index = newStore().buildFileNameIndex(
            listOf(
                StickerRuleStore.Entry("有名字", ""),
                StickerRuleStore.Entry("正常", "a.png"),
            )
        )
        assertEquals(1, index.size)
        assertEquals("a.png", index.keys.single())
    }

    // 【回归】重名 description 检测（供日志留痕，避免「重名折叠」无感知）
    @Test
    fun duplicateDescriptions_reportsCollapsedKeys_once() {
        val store = newStore()
        val dup = store.duplicateDescriptions(
            listOf(
                StickerRuleStore.Entry("仔细思考", "a.png"),
                StickerRuleStore.Entry("仔细思考", "b.png"),
                StickerRuleStore.Entry("开心", "c.png"),
                StickerRuleStore.Entry("", "d.png"), // 空描述不计
            )
        )
        assertEquals(listOf("仔细思考"), dup)
        assertTrue(store.duplicateDescriptions(listOf(StickerRuleStore.Entry("唯一", "a.png"))).isEmpty())
    }
}
