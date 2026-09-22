package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent Skills 技能类别（映射 Rust ToolCategory：CHAT/MEMORY/COMMERCE/CUSTOM）
 * 决策语义由 Rust 侧解释，此处仅作索引分类存储。
 */
enum class SkillCategory {
    CHAT, MEMORY, COMMERCE, CUSTOM
}

/**
 * Agent 技能索引实体（混合存储之"索引"侧）。
 *
 * 只存元数据索引，技能正文存文件系统（filesDir/agent_skills/<skillId>/content.md）。
 * - skillId = UUID，全局唯一，同时是文件目录名（Room 与 FS 的绑定锚点）。
 * - contentHash = 正文 SHA-256，读取时校验一致性。
 * - companionId = null 表示全局技能，否则按陪伴者归属。
 * - 文件系统为唯一事实源（meta.json + content.md），Room 索引可随时从 FS 重建。
 */
@Entity(
    tableName = "agent_skills",
    indices = [
        Index(value = ["skillId"], unique = true),
        Index(value = ["category"]),
        Index(value = ["companionId"]),
        Index(value = ["enabled"]),
        Index(value = ["updatedAt"]),
    ]
)
@Serializable
@SerialName("E7")
data class AgentSkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 业务唯一键（UUID），同时为 FS 目录名 */
    val skillId: String,
    /** 技能名（LLM 可见） */
    val name: String,
    /** 技能描述（SkillSelector 选择依据） */
    val description: String,
    /** 技能类别：CHAT / MEMORY / COMMERCE / CUSTOM */
    val category: SkillCategory = SkillCategory.CUSTOM,
    /** 逗号分隔标签，LIKE 检索 */
    val tags: String = "",
    /** 技能依赖/引用的工具名列表（逗号分隔，汇合点：索引按可用工具过滤）。
     *  空 = 无工具依赖，恒显示。对应 Hermes `conditions: tools: [...]`。 */
    val tools: String = "",
    /** 相对路径：agent_skills/<skillId>/content.md */
    val contentPath: String,
    /** 正文 SHA-256，一致性校验 */
    val contentHash: String,
    /** 正文字节数 */
    val contentLength: Long = 0,
    /** 软开关 */
    val enabled: Boolean = true,
    /** null = 全局技能，否则按陪伴者归属 */
    val companionId: Long? = null,
    /** 技能版本，更新时 +1 */
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
