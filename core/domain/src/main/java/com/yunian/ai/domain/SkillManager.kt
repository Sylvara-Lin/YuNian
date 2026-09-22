package com.yunian.ai.domain

data class SkillMetadata(
    val name: String,
    val description: String,
    val version: String = "1.0",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val parameters: Map<String, Any> = emptyMap()
)

data class Skill(
    val metadata: SkillMetadata,
    val content: String,
    val sourcePath: String? = null
)

interface SkillManager {

    suspend fun discoverSkills(): List<SkillMetadata>

    suspend fun loadSkill(name: String): Skill?

    suspend fun searchSkills(query: String): List<SkillMetadata>

    fun getLoadedSkills(): List<Skill>

    suspend fun unloadSkill(name: String): Boolean

    /**
     * 安装外部技能（AI 可自主联网安装）。
     * [markdown] 为技能文件全文（建议含 frontmatter），写入用户私有外部技能目录，
     * 安装后与内置技能同等可被 discover/load。返回是否成功。
     */
    suspend fun installSkill(name: String, markdown: String): Boolean = false

    /**
     * 卸载外部技能（内置技能不可卸载）。返回是否实际删除。
     */
    suspend fun uninstallSkill(name: String): Boolean = false

    /** 是否为外部安装的技能（内置技能返回 false） */
    suspend fun isExternalSkill(name: String): Boolean = false
}
