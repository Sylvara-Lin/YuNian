package com.yunian.ai.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * QA 独立对抗性回归 —— 核心不变量验证（不采信工程师自述）。
 *
 * 被验不变量：**提示词里给 AI 的表情名 ⇒ 发送侧反查必命中同一个文件**（双向一致）。
 *
 * 测试能力边界（如实声明，不回避）：
 *  - [StickerManager] 依赖 `android.content.Context`（filesDir / assets / contentResolver），
 *    本仓库 **无 Robolectric**（`core/common/build.gradle.kts` 仅 `testImplementation(libs.junit)`），
 *    故无法在 JVM 实例化 StickerManager。
 *  - 因此本文件对**纯函数层**（[StickerRuleStore] / [StickerPromptNames]）一律用 **真实函数**；
 *    对 StickerManager 里那一句 `.associate { description -> ... }` 反查映射，
 *    用与 main **逐字等价的一行**复刻（[fileRuleMap]），并同时用 store 的真函数
 *    [StickerRuleStore.buildFileNameIndex] / [StickerRuleStore.buildAliasIndex] 交叉验证文件名/别名侧。
 *  - StickerManager 级（getAllStickers 产出）的结论以「静态走查 + 文件:行号」给出，见测试报告。
 */
class StickerInvariantQaAdversarialTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 复刻 StickerManager.getAllStickers 对「单条文件」的产出（喂给 StickerPromptNames.build） ----------
    private fun fileSticker(file: String, entry: StickerRuleStore.Entry?): StickerInfo {
        val desc = entry?.description?.takeIf { it.isNotBlank() }
        return StickerInfo(
            name = desc ?: file.substringBeforeLast("."),
            path = File(dir, file).absolutePath,
            category = "imported",
            isBuiltIn = false,
            description = desc,
            fileName = file,
        )
    }

    private fun builtin(name: String) = StickerInfo(
        name = name, path = "asset://stickers/$name.png",
        category = "default", isBuiltIn = true,
    )

    private lateinit var dir: File

    /** 与 StickerManager.stickerRules 的构造逐字等价：description 为 key，文件不存在则剔除。 */
    private fun fileRuleMap(entries: List<StickerRuleStore.Entry>): Map<String, StickerInfo> =
        entries
            .filter { File(dir, it.fileName).exists() }
            .associate { e ->
                e.description to StickerInfo(
                    name = e.description, path = File(dir, e.fileName).absolutePath,
                    category = "imported", isBuiltIn = false,
                    description = e.description, fileName = e.fileName,
                )
            }

    /**
     * 发送侧三级反查链的等价复刻（TextProcessor.processStickerTags 的真实调用顺序）：
     * exact → aliases → fuzzy。exact/aliases 用 store 真函数；fuzzy 的关键一跳
     * 「按 nameWithoutExtension 命中文件」直接读真实目录。
     */
    private fun reverseLookup(
        name: String,
        entries: List<StickerRuleStore.Entry>,
    ): StickerInfo? {
        val store = StickerRuleStore(File(dir, "custom_stickers.json"))
        val rules = fileRuleMap(entries)                       // exact 分支
        val aliasIndex = store.buildAliasIndex(entries)        // alias 分支（真函数）

        rules[name]?.let { return it }
        aliasIndex[name.trim()]?.let { d -> rules[d]?.let { return it } }
        // fuzzy：description 子串
        entries.firstOrNull { it.description.isNotBlank() && (it.description.contains(name) || name.contains(it.description)) }
            ?.let { e -> rules[e.description]?.let { return it } }
        // fuzzy：按文件名/去扩展名命中（对应 findStickerByDescription 的 listFiles 分支）
        val hit = dir.listFiles()?.firstOrNull {
            it.nameWithoutExtension == name || it.name == name
        } ?: return null
        val e = store.buildFileNameIndex(entries)[hit.name]
        return StickerInfo(
            name = e?.description ?: hit.nameWithoutExtension, path = hit.absolutePath,
            category = "imported", isBuiltIn = false,
            description = e?.description, fileName = hit.name,
        )
    }

    // ================================
    // A. 核心不变量：5 种孤儿场景
    // ================================

    private fun writeFile(name: String): File = File(dir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    /** (a) ZIP 导入无 custom_stickers.json：图片在、规则空。 */
    @Test
    fun orphan_a_zipNoRules_fileVisibleToAiAndSendable() {
        dir = tmp.root
        writeFile("仔细思考.png")
        val entries = emptyList<StickerRuleStore.Entry>()          // 无规则
        val names = StickerPromptNames.build(listOf(fileSticker("仔细思考.png", null)))
        assertEquals(listOf("仔细思考"), names)                     // 不再静默丢弃
        val hit = reverseLookup(names.single(), entries)
        assertNotNull("孤儿文件必须可被发送侧反查到", hit)
        assertEquals("仔细思考.png", hit!!.fileName)
    }

    /** (b) ZIP 规则 fileName 与实际解压名不一致：规则指向不存在的文件。 */
    @Test
    fun orphan_b_zipFileNameMismatch_realFileStillSendable() {
        dir = tmp.root
        writeFile("real.png")
        val entries = listOf(StickerRuleStore.Entry("开心", "ghost.png", createdAt = 1L)) // 指向不存在文件
        // real.png 无自己的规则 → getAllStickers 会以文件名退化展示
        val names = StickerPromptNames.build(listOf(fileSticker("real.png", null), builtin("无关内置")))
        assertTrue(names.contains("real"))
        val hit = reverseLookup("real", entries)
        assertNotNull(hit)
        assertEquals("real.png", hit!!.fileName)
    }

    /** (c) 重名 description 折叠（两个文件同名）——见 D 段专门断言。此处仅确认两文件都被列出。 */
    @Test
    fun orphan_c_duplicateDescription_bothFilesListed() {
        dir = tmp.root
        writeFile("a.png"); writeFile("b.png")
        val entries = listOf(
            StickerRuleStore.Entry("仔细思考", "a.png", createdAt = 1L),
            StickerRuleStore.Entry("仔细思考", "b.png", createdAt = 2L),
        )
        val names = StickerPromptNames.build(listOf(fileSticker("a.png", entries[0]), fileSticker("b.png", entries[1])))
        // 名字层去重后只剩一个（提示词里只有一个「仔细思考」）——这正是反查只能命中一个文件的根因
        assertEquals(listOf("仔细思考"), names)
        val index = StickerRuleStore(File(dir, "custom_stickers.json")).buildFileNameIndex(entries)
        assertEquals(2, index.size) // 但文件名索引确实保留了两条
    }

    /** (d) JSON 解析失败 → load 返回空表（真函数验证）。 */
    @Test
    fun orphan_d_brokenJson_loadsEmpty_thenFileStillListed() {
        dir = tmp.root
        writeFile("custom_1700000000000_123.png")
        val broken = File(dir, "custom_stickers.json")
        broken.writeText("""[{"description":"仔细思考","fileName":""")   // 截断 JSON
        val entries = StickerRuleStore(broken).load()
        assertEquals(emptyList<StickerRuleStore.Entry>(), entries)     // 真函数：解析失败 → 空

        val names = StickerPromptNames.build(listOf(fileSticker("custom_1700000000000_123.png", null)))
        assertEquals(listOf("custom_1700000000000_123"), names)        // 27 字符内部名不得被丢
        val hit = reverseLookup("custom_1700000000000_123", entries)
        assertNotNull(hit)
        assertEquals("custom_1700000000000_123.png", hit!!.fileName)
    }

    /** (e) 文件被外部改名/移动：规则 fileName 指向旧名。 */
    @Test
    fun orphan_e_fileRenamedExternally_newNameListedAndSendable() {
        dir = tmp.root
        writeFile("renamed.png")
        val entries = listOf(StickerRuleStore.Entry("仔细思考", "old_name.png", createdAt = 1L))
        val names = StickerPromptNames.build(listOf(fileSticker("renamed.png", null)))
        assertEquals(listOf("renamed"), names)
        assertNotNull(reverseLookup("renamed", entries))
    }

    // ================================
    // 2. 内部文件名（>20 字符）不再被丢（核心回归护栏）
    // ================================

    @Test
    fun longInternalName_kept_andResolvable() {
        dir = tmp.root
        writeFile("custom_1700000000000_123.png")
        val names = StickerPromptNames.build(listOf(fileSticker("custom_1700000000000_123.png", null)))
        assertFalse("长度 > 20 不得被丢弃", names.isEmpty())
        // 内部名 = nameWithoutExtension = "custom_(13位ts)_(1~3位随机)"，实测 22~24 字符（非注释所写的 27）
        assertEquals("custom_1700000000000_123", names.single())
        assertTrue(names.single().length > 20)
        assertNotNull(reverseLookup(names.single(), emptyList()))
    }

    // ================================
    // 3. 空名/日志
    // ================================

    @Test
    fun blankDisplayName_counted() {
        dir = tmp.root
        val blank = StickerInfo(name = "", path = "/x", description = "  ", fileName = "x.png", isBuiltIn = false)
        assertEquals(1, StickerPromptNames.blankNameCount(listOf(blank)))
        assertTrue(StickerPromptNames.build(listOf(blank)).isEmpty())
    }

    // ================================
    // D. 对抗：不变量仍可能被破坏的场景
    // ================================

    /**
     * 【FIX-5 回归 · 核心不变量】重名 description → 生产侧 [StickerRuleStore.dedupeDescriptions]
     * 消歧后，断言「每个文件都能被**它在提示词里的名字**反查到自身」（显式断言，不只查重）。
     */
    @Test
    fun duplicateDescription_deduped_everyFileReachableByItsPromptName() {
        dir = tmp.root
        writeFile("a.png"); writeFile("b.png")
        val store = StickerRuleStore(File(dir, "custom_stickers.json"))
        val raw = listOf(
            StickerRuleStore.Entry("仔细思考", "a.png", createdAt = 1L),
            StickerRuleStore.Entry("仔细思考", "b.png", createdAt = 2L),
        )
        val entries = store.dedupeDescriptions(raw) // 与生产同源消歧
        val names = StickerPromptNames.build(entries.map { fileSticker(it.fileName, it) })

        // ① 每个文件一个唯一展示名
        assertEquals(listOf("仔细思考", "仔细思考(2)"), names)
        assertEquals(names.size, names.toSet().size)

        // ② 核心不变量：每个文件都能被它在提示词里的名字反查到自身
        entries.forEach { e ->
            val shown = StickerPromptNames.displayName(fileSticker(e.fileName, e))!!
            assertTrue("展示名 $shown 必须在提示词列表里", shown in names)
            val hit = reverseLookup(shown, entries)
            assertNotNull("展示名 $shown 必须能反查到文件", hit)
            assertEquals("展示名 $shown 必须反查到自己的文件", e.fileName, hit!!.fileName)
        }

        // ③ 幂等：重复消歧结果稳定
        assertEquals(entries, store.dedupeDescriptions(entries))
    }

    /** 【FIX-5 回归】load 路径对**旧数据**同样消歧 → 磁盘重名 JSON 读入后每个文件可达。 */
    @Test
    fun load_dedupesLegacyDuplicates_everyFileReachable() {
        dir = tmp.root
        writeFile("a.png"); writeFile("b.png")
        val json = File(dir, "custom_stickers.json")
        json.writeText(
            """[{"description":"仔细思考","fileName":"a.png"},{"description":"仔细思考","fileName":"b.png"}]"""
        )
        val entries = StickerRuleStore(json).load() // 真函数
        val names = StickerPromptNames.build(entries.map { fileSticker(it.fileName, it) })
        assertEquals(2, names.size)
        entries.forEach { e ->
            val shown = StickerPromptNames.displayName(fileSticker(e.fileName, e))!!
            assertEquals(e.fileName, reverseLookup(shown, entries)?.fileName)
        }
    }

    /** 【FIX-3 回归】新的 ZIP 数据：mergeZipRules 统一清洗后，提示词名字与反查键**一致**（含 `[]` 也能发）。 */
    @Test
    fun zipSanitizedName_promptNameAndLookupKeyConsistent() {
        dir = tmp.root
        writeFile("a.png")
        val store = StickerRuleStore(File(dir, "custom_stickers.json"))
        val merged = store.mergeZipRules(
            incoming = listOf(StickerRuleStore.Entry("开心[笑]", "a.png", createdAt = 1L)),
            existing = emptyList(),
        ).merged
        assertEquals("开心笑", merged.single().description)
        val names = StickerPromptNames.build(merged.map { fileSticker(it.fileName, it) })
        assertEquals(listOf("开心笑"), names)
        assertEquals("a.png", reverseLookup(names.single(), merged)?.fileName)
    }

    /** 【回归】进入提示词的名字不含方括号（展示侧剥字符，防 `[名字]` 解析被破坏）。 */
    @Test
    fun promptNames_neverContainBrackets() {
        dir = tmp.root
        writeFile("a.png")
        val json = File(dir, "custom_stickers.json")
        json.writeText("""[{"description":"开心[笑]","fileName":"a.png"}]""")
        val entries = StickerRuleStore(json).load()
        val names = StickerPromptNames.build(entries.map { fileSticker(it.fileName, it) })
        assertTrue("进入提示词的名字不应含方括号", names.none { it.contains('[') || it.contains(']') })
    }

    /**
     * 【FINDING G6 · P2】遗留数据残留缺口：旧版 ZIP 导入可能已把**未清洗**的 description 落盘；
     * 本版本 [StickerRuleStore.load] 只做重名消歧、**不做字符清洗**，而展示侧 [StickerPromptNames.displayName]
     * 会剥方括号 → 提示词给 AI 的名字（"开心笑"）与反查键（"开心[笑]"）不一致 → 该表情「AI 可见却发不出」**复发**。
     * 断言「展示名必须反查到文件」→ 期望成立，实际失败（手工构造遗留磁盘状态）。
     */
    @Test
    fun FINDING_G6_legacyBracketedDescription_promptNameMustStillResolve() {
        dir = tmp.root
        writeFile("a.png")
        val json = File(dir, "custom_stickers.json")
        // 遗留：旧版 ZIP 写入的未清洗 description（方括号留在磁盘上）
        json.writeText("""[{"description":"开心[笑]","fileName":"a.png"}]""")
        val entries = StickerRuleStore(json).load()
        val names = StickerPromptNames.build(entries.map { fileSticker(it.fileName, it) })
        assertEquals(listOf("开心笑"), names) // 展示侧已剥方括号
        assertNotNull("遗留带括号 description 时，展示名也必须能反查到文件", reverseLookup(names.single(), entries))
    }

    /** 遗留超长 description：load 不清洗 → 提示词原样（不按长度丢弃不变量保住，但会撑大提示词）。 */
    @Test
    fun legacyLongDescription_notTruncated_reachesPrompt() {
        dir = tmp.root
        val longName = "超".repeat(80)
        val json = File(dir, "custom_stickers.json")
        json.writeText("""[{"description":"$longName","fileName":"a.png"}]""")
        val entries = StickerRuleStore(json).load()
        val names = StickerPromptNames.build(entries.map { fileSticker(it.fileName, it) })
        assertEquals(80, names.single().length)
    }

    // ================================
    // 6. 预算：自定义优先 / take(50)
    // ================================

    @Test
    fun budget_builtin60_custom5_keepsAllCustom() {
        dir = tmp.root
        val customs = (1..5).map { imported(file = "c$it.png", desc = "自定义$it") }
        val builtins = (1..60).map { builtin("内置$it") }
        val names = StickerPromptNames.build(customs + builtins, maxNames = 50)
        assertEquals(50, names.size)
        assertTrue(names.take(5).toSet() == (1..5).map { "自定义$it" }.toSet())
        assertTrue("内置超预算不得挤掉自定义", names.containsAll((1..5).map { "自定义$it" }))
    }

    @Test
    fun budget_custom60_onlyCustomKept_atPureLayer() {
        dir = tmp.root
        val customs = (1..60).map { imported(file = "c$it.png", desc = "自定义$it") }
        val names = StickerPromptNames.build(customs, maxNames = 50)
        assertEquals("纯函数层：自定义不受 maxNames 截断", 60, names.size)
    }

    @Test
    fun budget_dedupCustomOverBuiltin() {
        dir = tmp.root
        val list = listOf(
            builtin("开心"),
            imported(file = "a.png", desc = "开心"),   // 与内置同名
        )
        assertEquals(listOf("开心"), StickerPromptNames.build(list))
    }

    // ================================
    // 7. 保留名（FIX-2）对抗：变体不得「进了名单却发不出」
    // ================================

    /** 保留名变体（空格/换行/制表）经 ZIP 清洗后不得残留保留名。 */
    @Test
    fun reservedNameVariants_zipMerge_neverLeftReserved() {
        dir = tmp.root
        val store = StickerRuleStore(File(dir, "custom_stickers.json"))
        listOf("红包", "红包 ", "红包\n", "  红包  ", "图片", "转账\t", "文件").forEach { v ->
            val merged = store.mergeZipRules(
                incoming = listOf(StickerRuleStore.Entry(v, "a.png", createdAt = 1L)),
                existing = emptyList(),
            ).merged
            merged.forEach {
                assertFalse("ZIP 合并后不得残留保留名: '${it.description}'", StickerReservedNames.isReserved(it.description))
            }
        }
    }

    /** 单文件导入 / 重命名共用的校验：保留名（含变体）必须被拒。 */
    @Test
    fun reservedNameVariants_nameValidation_rejected() {
        listOf("红包", "红包 ", "红包\n", "  红包  ", "语音").forEach { v ->
            val sanitized = sanitizeStickerNameText(v)
            assertNotNull("保留名变体 '$v' 必须被拒", stickerNameValidationError(sanitized))
        }
        assertNull("普通名应通过", stickerNameValidationError(sanitizeStickerNameText("仔细思考")))
        assertNull("非保留的相似名应通过", stickerNameValidationError(sanitizeStickerNameText("红包表情")))
    }

    /** 【P3 记录】ZIP 保留名自动改名「红包→红包表情」若与既有真名撞车 → 该 ZIP 条目被跳过（图片成孤儿）。 */
    @Test
    fun zipReservedNameRename_collidingWithExisting_isSkipped_leavingOrphan() {
        dir = tmp.root
        val store = StickerRuleStore(File(dir, "custom_stickers.json"))
        val result = store.mergeZipRules(
            incoming = listOf(StickerRuleStore.Entry("红包", "hb.png", createdAt = 1L)),
            existing = listOf(StickerRuleStore.Entry("红包表情", "other.png", createdAt = 0L)),
        )
        assertEquals(0, result.added)
        assertEquals(1, result.skipped) // hb.png 已解压但无规则 → 孤儿（面板按文件名显示）
    }

    private fun imported(file: String, desc: String?) = StickerInfo(
        name = desc ?: file.substringBeforeLast("."),
        path = "/x/$file", category = "imported", isBuiltIn = false,
        description = desc, fileName = file,
    )
}
