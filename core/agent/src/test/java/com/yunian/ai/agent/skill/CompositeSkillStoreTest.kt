package com.yunian.ai.agent.skill

import com.yunian.ai.agent.uniffi.SkillStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeSkillStoreTest {

    private class FakeStore(
        private val list: String,
        private val content: String? = null,
        private val saveResult: Int = 1,
    ) : SkillStore {
        override fun listSkills(companionId: Long?): String = list
        override fun getSkillContent(skillId: String): String? = content
        override fun searchSkills(query: String, limit: UInt): String = list
        override fun saveSkill(metaJson: String, content: String): Int = saveResult
        override fun deleteSkill(skillId: String): Boolean = saveResult > 0
    }

    private fun meta(id: String) =
        org.json.JSONObject().apply { put("skill_id", id); put("name", id) }

    @Test
    fun mergesBothStoresAndDeduplicatesById() {
        val local = FakeStore(JSONArray().put(meta("assets_skill")).toString())
        val builtin = FakeStore(JSONArray().put(meta("builtin_chat_tool_protocol")).toString())
        val merged = JSONArray(CompositeSkillStore(local, builtin).listSkills(null))
        assertEquals(2, merged.length())
        assertEquals("assets_skill", merged.getJSONObject(0).optString("skill_id"))
        assertEquals("builtin_chat_tool_protocol", merged.getJSONObject(1).optString("skill_id"))

        val duplicated = JSONArray(
            CompositeSkillStore(local, FakeStore(JSONArray().put(meta("assets_skill")).toString()))
                .listSkills(null)
        )
        assertEquals(1, duplicated.length())
    }

    @Test
    fun malformedJsonFallsBackToOtherStore() {
        val broken = FakeStore("not-json")
        val builtin = FakeStore(JSONArray().put(meta("builtin_chat_tool_protocol")).toString())
        val merged = JSONArray(CompositeSkillStore(broken, builtin).listSkills(null))
        assertEquals(1, merged.length())
        assertEquals("builtin_chat_tool_protocol", merged.getJSONObject(0).optString("skill_id"))
    }

    @Test
    fun contentAndSaveFallThroughToBuiltinStore() {
        val local = FakeStore("[]", content = null, saveResult = -1)
        val builtin = FakeStore("[]", content = "正文", saveResult = 3)
        val store = CompositeSkillStore(local, builtin)
        assertEquals("正文", store.getSkillContent("builtin_chat_tool_protocol"))
        assertEquals(3, store.saveSkill("{}", "正文"))
        assertNull(FakeStore("[]").getSkillContent("missing"))
        assertTrue(store.deleteSkill("builtin_chat_tool_protocol"))
    }
}
