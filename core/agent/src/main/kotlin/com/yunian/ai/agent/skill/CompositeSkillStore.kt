package com.yunian.ai.agent.skill

import com.yunian.ai.agent.uniffi.SkillStore
import org.json.JSONArray

/**
 * SkillStore 组合器：把两套存储合成 Rust `SkillSelector` 的同一数据源。
 *
 * 迁移背景（Q6 双体系收敛）：App 启动时经
 * [com.yunian.ai.agent.AgentFacade.installSkillStoreProvider] 注入的
 * `SkillStoreAdapter`（assets/skills + external_skills + 技能市场）会**整体替换**
 * 默认的 [SkillStoreImpl]（Room `agent_skills` 索引 + `filesDir/agent_skills` 正文）。
 * 但 Agent 原生技能（内置 `builtin_chat_tool_protocol` 等）是由
 * [com.yunian.ai.agent.AgentFacade.seedBuiltinChatToolSkill] 写进 [SkillStoreImpl] 的，
 * 只注入适配器会让这些技能从 L1 目录与 `load_skill` 里彻底消失——模型看不到内置
 * 聊天工具协议，气泡/表情包工具调用随之退化。
 *
 * 优先级：本地资产体系（primary）优先；同名 `skill_id` 以 primary 为准，
 * 正文读取先 primary 后 fallback。任一存储返回非法 JSON 时降级为另一侧结果，
 * 不让单侧故障清空整个技能目录。
 */
class CompositeSkillStore(
    private val primary: SkillStore,
    private val fallback: SkillStore,
) : SkillStore {

    override fun listSkills(companionId: Long?): String =
        merge(primary.listSkills(companionId), fallback.listSkills(companionId))

    override fun getSkillContent(skillId: String): String? =
        primary.getSkillContent(skillId) ?: fallback.getSkillContent(skillId)

    override fun searchSkills(query: String, limit: UInt): String =
        merge(primary.searchSkills(query, limit), fallback.searchSkills(query, limit))

    override fun saveSkill(metaJson: String, content: String): Int {
        val written = primary.saveSkill(metaJson, content)
        return if (written > 0) written else fallback.saveSkill(metaJson, content)
    }

    override fun deleteSkill(skillId: String): Boolean =
        primary.deleteSkill(skillId) or fallback.deleteSkill(skillId)

    /** 按 `skill_id` 去重合并两个 JSON 数组；非法 JSON 视为空数组。 */
    private fun merge(first: String, second: String): String {
        val merged = JSONArray()
        val seen = HashSet<String>()
        for (raw in listOf(first, second)) {
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("skill_id").takeIf { it.isNotBlank() }
                if (id == null || seen.add(id)) merged.put(item)
            }
        }
        return merged.toString()
    }
}
