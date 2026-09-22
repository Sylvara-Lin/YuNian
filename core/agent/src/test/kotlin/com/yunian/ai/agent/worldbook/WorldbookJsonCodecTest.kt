package com.yunian.ai.agent.worldbook

import com.yunian.ai.database.model.EntryRole
import com.yunian.ai.database.model.InjectionPosition
import com.yunian.ai.database.model.LorebookEntity
import com.yunian.ai.database.model.LorebookEntryEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书编解码器契约测试。
 *
 * ★ 最高风险假设：**`entries` 必须输出 map 格式**，且 5 值 `position` /
 * `depth` / `role` / `scan_depth` 全部无损往返。数组格式会被 Rust 侧
 * `chara_card` 校验拒绝（其 `EntryPosition` 仅 `before_char`/`after_char`）。
 */
class WorldbookJsonCodecTest {

    private fun entry(
        id: Long = 1L,
        keywords: List<String> = listOf("咖啡馆"),
        content: String = "街角老店，老板是只猫。",
        position: InjectionPosition = InjectionPosition.BEFORE_SYSTEM_PROMPT,
        priority: Int = 0,
        injectDepth: Int? = null,
        role: EntryRole = EntryRole.SYSTEM,
        caseSensitive: Boolean = false,
        useRegex: Boolean = false,
        sortOrder: Int = 0,
        scanDepth: Int = 10,
        constant: Boolean = false,
        enabled: Boolean = true,
    ) = LorebookEntryEntity(
        id = id,
        lorebookId = 7L,
        keywordsJson = WorldbookJsonCodec.encodeKeywords(keywords),
        content = content,
        injectionPosition = position,
        priority = priority,
        injectDepth = injectDepth,
        role = role,
        caseSensitive = if (caseSensitive) 1 else 0,
        useRegex = if (useRegex) 1 else 0,
        sortOrder = sortOrder,
        scanDepth = scanDepth,
        constantActive = if (constant) 1 else 0,
        enabled = if (enabled) 1 else 0,
    )

    // ───────────────────────── 容器格式 ─────────────────────────

    @Test
    fun `entries must be a JSON object not an array`() {
        val json = WorldbookJsonCodec.bookToJson(
            LorebookEntity(id = 7L, name = "旧书"),
            listOf(entry(id = 1L)),
        )
        val root = JSONObject(json)
        val entries = root.opt("entries")
        assertTrue("entries 必须是 JSONObject(map) 否则 chara_card 会拒绝 5 值 position", entries is JSONObject)
        assertFalse("entries 不能是数组", entries is org.json.JSONArray)
        assertEquals(1, JSONObject(json).optJSONObject("entries")!!.length())
    }

    // ───────────────────────── 5 值 position 往返 ─────────────────────────

    @Test
    fun `all five injection positions survive round trip`() {
        val positions = listOf(
            InjectionPosition.BEFORE_SYSTEM_PROMPT to "before_char",
            InjectionPosition.AFTER_SYSTEM_PROMPT to "after_char",
            InjectionPosition.TOP_OF_CHAT to "top_of_chat",
            InjectionPosition.BOTTOM_OF_CHAT to "bottom_of_chat",
            InjectionPosition.AT_DEPTH to "at_depth",
        )
        val entries = positions.mapIndexed { i, (local, _) ->
            entry(id = (i + 1).toLong(), content = "c$i", position = local, injectDepth = i + 1)
        }
        val json = WorldbookJsonCodec.bookToJson(LorebookEntity(id = 7L, name = "书"), entries)

        // ① JSON 内文本值必须是 ST 规范值
        val map = JSONObject(json).optJSONObject("entries")!!
        for (i in positions.indices) {
            val obj = map.optJSONObject((i + 1).toString())!!
            assertEquals(positions[i].second, obj.optString("position"))
        }

        // ② 解码回本地枚举必须无损
        val back = WorldbookJsonCodec.entries(json)
        assertEquals(5, back.size)
        for (i in positions.indices) {
            assertEquals(positions[i].first, back[i].position)
        }
    }

    // ───────────────────────── 排序取反 ─────────────────────────

    @Test
    fun `priority inverts into insertion_order so larger priority injects first`() {
        val entries = listOf(
            entry(id = 1L, content = "低", priority = 0),
            entry(id = 2L, content = "高", priority = 100),
            entry(id = 3L, content = "中", priority = 50),
        )
        val json = WorldbookJsonCodec.bookToJson(LorebookEntity(id = 7L, name = "书"), entries)
        val map = JSONObject(json).optJSONObject("entries")!!

        val orderLow = map.optJSONObject("1")!!.optLong("insertion_order")
        val orderHigh = map.optJSONObject("2")!!.optLong("insertion_order")
        val orderMid = map.optJSONObject("3")!!.optLong("insertion_order")
        assertTrue("priority 高者 insertion_order 必须更小", orderHigh < orderMid)
        assertTrue("priority 中者应小于低者", orderMid < orderLow)

        // Rust scan() 按 insertion_order 升序 → 高优先级先注入
        val back = WorldbookJsonCodec.entries(json)
        assertEquals(listOf("高", "中", "低"), back.map { it.content })
        assertEquals(listOf(100, 50, 0), back.map { it.toEntity(0L).priority })
    }

    @Test
    fun `insertion order and priority are exact inverses`() {
        for (p in listOf(-1000, -1, 0, 1, 999, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val o = WorldbookJsonCodec.priorityToInsertionOrder(p)
            assertEquals("priority=$p", p, WorldbookJsonCodec.insertionOrderToPriority(o))
        }
    }

    // ───────────────────────── enabled 显式写出 ─────────────────────────

    @Test
    fun `disabled entry is written explicitly as false`() {
        val json = WorldbookJsonCodec.bookToJson(
            LorebookEntity(id = 7L, name = "书"),
            listOf(entry(id = 1L, enabled = false), entry(id = 2L, enabled = true)),
        )
        val map = JSONObject(json).optJSONObject("entries")!!
        // 必须显式写出：Rust 侧缺省为 true，省略会让停用条目意外生效
        assertTrue(map.optJSONObject("1")!!.has("enabled"))
        assertFalse(map.optJSONObject("1")!!.optBoolean("enabled"))
        assertTrue(map.optJSONObject("2")!!.optBoolean("enabled"))
    }

    // ───────────────────────── 全字段往返 ─────────────────────────

    @Test
    fun `all flags and depth and role round trip losslessly`() {
        val src = entry(
            id = 42L,
            keywords = listOf("a", "b"),
            content = "内容",
            position = InjectionPosition.AT_DEPTH,
            priority = 77,
            injectDepth = 3,
            role = EntryRole.ASSISTANT,
            caseSensitive = true,
            useRegex = true,
            sortOrder = 5,
            scanDepth = 20,
            constant = true,
            enabled = true,
        )
        val json = WorldbookJsonCodec.bookToJson(LorebookEntity(id = 7L, name = "书"), listOf(src))
        val obj = JSONObject(json).optJSONObject("entries")!!.optJSONObject("42")!!

        assertEquals(listOf("a", "b"), WorldbookJsonCodec.parseKeywords(obj.optJSONArray("keys").toString()))
        assertEquals("内容", obj.optString("content"))
        assertTrue(obj.optBoolean("case_sensitive"))
        assertTrue(obj.optBoolean("use_regex"))
        assertTrue(obj.optBoolean("constant"))
        assertEquals("at_depth", obj.optString("position"))
        assertEquals(3L, obj.optLong("depth"))
        assertEquals("assistant", obj.optString("role"))
        assertEquals(20L, obj.optLong("scan_depth"))
        assertEquals(0, obj.optJSONArray("secondary_keys")!!.length())

        // extensions 回写来源书本
        val ext = obj.optJSONObject("extensions")!!
        assertEquals(7L, ext.optLong("_bookId"))
        assertEquals("书", ext.optString("_bookName"))

        // 解码无损（sortOrder 无 ST 对应字段，有意丢弃）
        val back = WorldbookJsonCodec.entries(json).single()
        assertEquals(listOf("a", "b"), back.keywords)
        assertEquals("内容", back.content)
        assertEquals(InjectionPosition.AT_DEPTH, back.position)
        assertEquals(3L, back.depth)
        assertEquals(EntryRole.ASSISTANT, back.role)
        assertTrue(back.caseSensitive)
        assertTrue(back.useRegex)
        assertTrue(back.constant)
        assertEquals(20L, back.scanDepth)
        assertEquals(7L, back.bookId)
        assertEquals("书", back.bookName)
        assertEquals(77, back.toEntity(0L).priority)
    }

    @Test
    fun `depth is omitted for non at-depth positions`() {
        val json = WorldbookJsonCodec.bookToJson(
            LorebookEntity(id = 7L, name = "书"),
            listOf(entry(id = 1L, position = InjectionPosition.TOP_OF_CHAT, injectDepth = 9)),
        )
        val obj = JSONObject(json).optJSONObject("entries")!!.optJSONObject("1")!!
        assertFalse("非 at_depth 不应写 depth，避免干扰 Rust clamp", obj.has("depth"))
    }

    // ───────────────────────── 顶层元信息 ─────────────────────────

    @Test
    fun `book meta carries scan depth mode and token budget`() {
        val entries = listOf(
            entry(id = 1L, content = "a", scanDepth = 10),
            entry(id = 2L, content = "b", scanDepth = 10),
            entry(id = 3L, content = "c", scanDepth = 15),
        )
        val json = WorldbookJsonCodec.bookToJson(
            LorebookEntity(id = 7L, name = "我的世界", description = "说明"),
            entries,
        )
        val root = JSONObject(json)
        assertEquals("我的世界", root.optString("name"))
        assertEquals("说明", root.optString("description"))
        assertEquals(10L, root.optLong("scan_depth"))
        assertEquals(WorldbookJsonCodec.DEFAULT_TOKEN_BUDGET, root.optLong("token_budget"))
        assertFalse("recursive_scanning 应省略（默认 false）", root.has("recursive_scanning"))

        val meta = WorldbookJsonCodec.bookMeta(json)
        assertEquals("我的世界", meta.name)
        assertEquals(10L, meta.scanDepth)
    }

    @Test
    fun `dominant scan depth prefers higher value on tie`() {
        assertEquals(10L, WorldbookJsonCodec.dominantScanDepth(emptyList()))
        assertEquals(15L, WorldbookJsonCodec.dominantScanDepth(listOf(entry(id = 1L, scanDepth = 10), entry(id = 2L, scanDepth = 15))))
        assertEquals(20L, WorldbookJsonCodec.dominantScanDepth(listOf(entry(id = 1L, scanDepth = 20), entry(id = 2L, scanDepth = 20), entry(id = 3L, scanDepth = 5))))
    }

    // ───────────────────────── 容错 ─────────────────────────

    @Test
    fun `malformed json degrades gracefully`() {
        assertTrue(WorldbookJsonCodec.entries("{not json").isEmpty())
        assertTrue(WorldbookJsonCodec.entries("{}").isEmpty())
        assertEquals(0, WorldbookJsonCodec.entryCount("{not json"))
        assertTrue(WorldbookJsonCodec.parseKeywords("{not json").isEmpty())
        assertFalse(WorldbookJsonCodec.parseKeywords("{not json").isNotEmpty())
        assertNotNull(WorldbookJsonCodec.bookMeta("{not json"))
        assertEquals(WorldbookJsonCodec.DEFAULT_SCAN_DEPTH, WorldbookJsonCodec.bookMeta("{not json").scanDepth)
    }

    @Test
    fun `unknown position and role fall back to safe defaults`() {
        assertEquals(InjectionPosition.AT_DEPTH, WorldbookJsonCodec.stToPosition("weird"))
        assertEquals(InjectionPosition.AT_DEPTH, WorldbookJsonCodec.stToPosition(null))
        assertEquals(EntryRole.SYSTEM, WorldbookJsonCodec.stToRole("weird"))
        assertEquals(EntryRole.SYSTEM, WorldbookJsonCodec.stToRole(null))
    }

    @Test
    fun `array container is still decodable for import compatibility`() {
        val json = """{"name":"外来的","entries":[{"id":1,"keys":["k"],"content":"c","enabled":true,"insertion_order":5,"position":"after_char"}]}"""
        val back = WorldbookJsonCodec.entries(json)
        assertEquals(1, back.size)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, back[0].position)
        assertEquals(1, WorldbookJsonCodec.entryCount(json))
    }

    @Test
    fun `missing enabled defaults to true when decoding`() {
        val json = """{"entries":{"1":{"keys":["k"],"content":"c","insertion_order":1}}}"""
        assertTrue(WorldbookJsonCodec.entries(json).single().enabled)
    }
}
