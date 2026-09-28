package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent 调度日志实体（Room agent_dispatch_log）。
 *
 * 用途（对齐可观测性原则，与 prompt_audit 互补）：
 * - prompt_audit 记录「编排内容」（dry_run 片段摘要 / 规则指纹 / 回合数），
 *   回答「为什么这样回复」；
 * - 本表记录「调度过程」——每次 Agent 回合的完整时间线：provider/model、
 *   起止时间、实际回合数、完成原因、错误、注册工具快照、工具调用明细
 *   （名称/参数/结果/耗时/成败）、事件流（bubble/sticker）——回答「AI 是怎么跑的」。
 *
 * 采集方式：feature 层在回合结束后调用 AgentFacade.recordDispatchLog，
 * 工具调用明细由 AgentToolHost 在 execute 回调内线程安全收集后一并传入。
 * 只读审计数据：不做业务读取路径，超期由 deleteOlderThan 清理。
 */
@Entity(
    tableName = "agent_dispatch_log",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["companionId"]),
        Index(value = ["groupId"]),
        Index(value = ["sessionId"]),
    ]
)
@Serializable
@SerialName("E9")
data class AgentDispatchLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 落库时间戳（ms） */
    val timestamp: Long = System.currentTimeMillis(),
    /** 单聊陪伴者 id；群聊时为空 */
    val companionId: Long? = null,
    /** 群聊 id；单聊时为空 */
    val groupId: Long? = null,
    /** 会话 id（conversationId），关联同一对话的连续回合 */
    val sessionId: String? = null,
    /** 调度 id（一次完整 Agent 回合的唯一标识，与落库 timestamp 对齐） */
    val dispatchId: String = "",
    /** API 提供商名（ApiProvider.name，如 DEEPSEEK / OPENAI） */
    val provider: String = "",
    /** 模型名（ApiConfig.model） */
    val model: String = "",
    /** 回合开始时间戳（ms） */
    val startedAtMs: Long = 0,
    /** 回合结束时间戳（ms） */
    val completedAtMs: Long = 0,
    /** 实际使用回合数（TurnStateController 统计） */
    val roundsUsed: Int = 0,
    /** 完成原因：completed / max_rounds / confirm_pending / state_stop / error */
    val finishedReason: String = "",
    /** 错误信息（无则空串） */
    val error: String = "",
    /** 注册工具名快照，逗号分隔（本次回合实际注入会话级工具） */
    val toolNames: String = "",
    /** 工具调用明细 JSON 数组：[{name,args,result,elapsedMs,ok}] */
    val toolCallsJson: String = "[]",
    /** 事件流 JSON 数组：[{kind,text,extra}]（bubble/sticker/status/confirm_request） */
    val eventsJson: String = "[]",
    /** 用户消息摘要（前 200 字，便于检索定位） */
    val querySummary: String = "",
)
