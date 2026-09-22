package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 提示词编排审计实体（Room prompt_audit）。
 *
 * 用途（编排方案 v3 可观测性原则）：
 * - 每次 Agent 对话回合（runTurn）由 Rust PromptOrchestrator.dryRun 产出片段摘要，
 *   Kotlin 侧将摘要 JSON 与上下文快照落库，供排查"为什么这样回复"与片段效果分析。
 * - 只读审计数据：不做业务读取路径，仅保留最近 N 条，超期由 deleteOlderThan 清理。
 * - 纯追加，不参与 LLM 请求，不参与决策。
 */
@Entity(
    tableName = "prompt_audit",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["companionId"]),
        Index(value = ["groupId"]),
        Index(value = ["sessionId"]),
    ]
)
@Serializable
@SerialName("E8")
data class PromptAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 落库时间戳（ms） */
    val timestamp: Long = System.currentTimeMillis(),
    /** 单聊陪伴者 id；群聊时为空 */
    val companionId: Long? = null,
    /** 群聊 id；单聊时为空 */
    val groupId: Long? = null,
    /** 会话 id（conversationId），用于关联同一次对话的连续回合 */
    val sessionId: String? = null,
    /** 编排版本（PromptOrchestrator 方案版本号，如 3） */
    val promptVersion: Int = 3,
    /** dry_run 片段摘要 JSON（id/source/layer/lifetime/chars 列表） */
    val fragmentsJson: String = "[]",
    /** 实际使用回合数（TurnStateController 统计） */
    val roundsUsed: Int = 0,
    /** 最终 system prompt 的 SHA-256 指纹（内容级去重/变更检测） */
    val systemPromptHash: String = "",
    /** 已注册工具名，逗号分隔（如 recall_memory,save_memory,consolidate_memory） */
    val toolNames: String = "",
)
