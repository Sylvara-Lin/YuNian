package com.yunian.ai.feature.skills.repository

import com.yunian.ai.domain.Skill
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.SkillMetadata
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillStoreAdapter] 单测 —— 锁定 Rust `SkillStore` 回调的 JSON 契约。
 *
 * 该适配器是迁移新增的**跨语言边界**：字段名/语义写错不会编译报错，只会让 Rust
 * `SkillSelector` 静默拿不到技能。因此只测「最容易写错且影响功能」的几项：
 * `skill_id` 规范化、`tools` 空数组、`enabled`/`companion_id` 恒定、失败码回落、`limit` 语义。
 *
 * 纯 JVM：[SkillManager] 写方法带默认实现，手写替身即可，无需 Room / 文件系统。
 * `org.json` 已通过 `testImplementation(libs.org.json)` 挂真实实现（同 `:core:agent`）。
 */
class SkillStoreAdapterTest {

    private class FakeSkillManager(
        private val discovered: List<SkillMetadata> = emptyList(),
        private val contents: Map<String, String> = emptyMap(),
        private val searchHits: List<SkillMetadata> = emptyList(),
        private val installReturns: Boolean = true,
        private val uninstallReturns: Boolean = true,
    ) : SkillManager {
        val installed = mutableListOf<Pair<String, String>>()
        val uninstalled = mutableListOf<String>()

        override suspend fun discoverSkills() = discovered
        override suspend fun loadSkill(name: String): Skill? =
            contents[name]?.let { Skill(SkillMetadata(name, ""), it) }
        override suspend fun searchSkills(query: String) = searchHits
        override fun getLoadedSkills() = emptyList<Skill>()
        override suspend fun unloadSkill(name: String) = false
        override suspend fun installSkill(name: String, markdown: String): Boolean {
            installed += name to markdown
            return installReturns
        }
        override suspend fun uninstallSkill(name: String): Boolean {
            uninstalled += name
            return uninstallReturns
        }
        override suspend fun isExternalSkill(name: String) = false
    }

    private fun meta(name: String, tags: List<String> = emptyList()) =
        SkillMetadata(name = name, description = "d", tags = tags)

    private fun asObjects(json: String) = JSONArray(json).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    // ── listSkills：字段契约 ──

    @Test
    fun listSkills_emitsExpectedSkillMetaShape() {
        val adapter = SkillStoreAdapter(
            FakeSkillManager(discovered = listOf(meta("PDF 处理", tags = listOf("pdf", "文档")))),
        )

        val o = asObjects(adapter.listSkills(1L)).single()
        // 关键：skill_id 是规范化结果（Rust 会原样回传给 getSkillContent），name 保留原始名
        assertEquals("PDF_处理", o.getString("skill_id"))
        assertEquals("PDF 处理", o.getString("name"))
        assertEquals("CUSTOM", o.getString("category"))
        assertEquals("pdf,文档", o.getString("tags"))
        assertEquals(0, (o.get("tools") as JSONArray).length())
        assertTrue(o.getBoolean("enabled"))
        assertTrue(o.isNull("companion_id"))
        assertEquals(1, o.getInt("version"))
        assertEquals(0L, o.getLong("updated_at"))
    }

    @Test
    fun listSkills_emptyManagerYieldsEmptyArray() {
        assertEquals("[]", SkillStoreAdapter(FakeSkillManager()).listSkills(null))
    }

    @Test
    fun listSkills_fallsBackToRawNameWhenNormalizationRejectsIt() {
        val adapter = SkillStoreAdapter(FakeSkillManager(discovered = listOf(meta("a/b"))))

        assertEquals("a/b", asObjects(adapter.listSkills(null)).single().getString("skill_id"))
    }

    @Test
    fun listSkills_skillIdRoundTripsBackIntoGetSkillContent() {
        val adapter = SkillStoreAdapter(
            FakeSkillManager(
                discovered = listOf(meta("联网 搜索")),
                contents = mapOf("联网_搜索" to "# 正文"),
            ),
        )

        val id = asObjects(adapter.listSkills(null)).single().getString("skill_id")
        assertEquals("# 正文", adapter.getSkillContent(id))
    }

    // ── getSkillContent ──

    @Test
    fun getSkillContent_returnsNullForUnknownOrBlank() {
        val adapter = SkillStoreAdapter(
            FakeSkillManager(contents = mapOf("a" to "body", "empty" to "  ")),
        )

        assertEquals("body", adapter.getSkillContent("a"))
        assertNull(adapter.getSkillContent("missing"))
        assertNull(adapter.getSkillContent("empty"))
    }

    // ── searchSkills：limit 语义 ──

    @Test
    fun searchSkills_limitIsLowerBoundedToOne() {
        val adapter = SkillStoreAdapter(
            FakeSkillManager(searchHits = listOf(meta("a"), meta("b"), meta("c"))),
        )

        assertEquals(1, asObjects(adapter.searchSkills("q", 0u)).size)
        assertEquals(2, asObjects(adapter.searchSkills("q", 2u)).size)
        // UInt 上界不能溢出成负数后又被夹回 1
        assertEquals(3, asObjects(adapter.searchSkills("q", UInt.MAX_VALUE)).size)
    }

    // ── saveSkill：成功 / 失败回落 ──

    @Test
    fun saveSkill_installsUnderNormalizedNameAndReturnsOne() {
        val mgr = FakeSkillManager()
        val adapter = SkillStoreAdapter(mgr)

        assertEquals(1, adapter.saveSkill("""{"name":"web design"}""", "# body"))
        assertEquals("web_design" to "# body", mgr.installed.single())
    }

    @Test
    fun saveSkill_returnsMinusOneOnBadInputWithoutTouchingManager() {
        val mgr = FakeSkillManager()
        val adapter = SkillStoreAdapter(mgr)

        assertEquals(-1, adapter.saveSkill("""{"name":"ok"}""", "   "))            // 空正文
        assertEquals(-1, adapter.saveSkill("""{"name":"../etc/passwd"}""", "b"))    // 路径穿越
        assertEquals(-1, adapter.saveSkill("""{"name":"!!!"}""", "b"))              // 纯符号
        assertEquals(-1, adapter.saveSkill("not json", "b"))                        // 坏 JSON
        assertTrue("失败分支不得调用 installSkill", mgr.installed.isEmpty())
    }

    @Test
    fun saveSkill_returnsMinusOneWhenManagerFails() {
        val adapter = SkillStoreAdapter(FakeSkillManager(installReturns = false))

        assertEquals(-1, adapter.saveSkill("""{"name":"ok"}""", "body"))
    }

    // ── deleteSkill ──

    @Test
    fun deleteSkill_delegatesWithoutRenormalizing() {
        val mgr = FakeSkillManager()

        assertTrue(SkillStoreAdapter(mgr).deleteSkill("PDF_处理"))
        assertEquals(listOf("PDF_处理"), mgr.uninstalled)
        assertFalse(SkillStoreAdapter(FakeSkillManager(uninstallReturns = false)).deleteSkill("x"))
    }
}
