package com.yunian.ai.domain.delegation

/**
 * 多 Agent 编排：委派模型（角色 / 记录 / 协调器端口）。
 *
 * 闭环：主回合调用 delegate_task 工具 → 创建 PENDING 委派记录 → 回合结束后
 * 协调器异步执行子 Agent 回合（独立 dispatch，串行于 turn_lock 之外）→ COMPLETED；
 * 主回合后续经 fetch_delegation_result 工具汇聚结果。
 */

/** 委派角色（system prompt 模板 + 工具白名单约束）。 */
data class DelegationRole(
    val name: String,
    /** 子 Agent system prompt（角色设定）。 */
    val systemPrompt: String,
    /** 允许的工具名（空 = 不注入工具，纯文本分析）。 */
    val toolWhitelist: List<String> = emptyList(),
)

/** 委派状态。 */
enum class DelegationStatus { PENDING, RUNNING, COMPLETED, FAILED }

/** 委派记录（一次子 Agent 回合）。 */
data class DelegationRecord(
    val id: Long = 0L,
    val role: String,
    val prompt: String,
    val status: DelegationStatus = DelegationStatus.PENDING,
    /** 子回合最终结果（COMPLETED）。 */
    val result: String = "",
    /** 失败原因（FAILED）。 */
    val error: String = "",
    /** 发起方 companion（单聊）或 null（群聊）。 */
    val companionId: Long? = null,
    /** 子回合 dispatchId（事件账本关联键）。 */
    val dispatchId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
)

/** 委派协调器端口（实现在 core:agent，经 ServiceRegistry 注入）。 */
interface DelegationCoordinator {
    /** 创建委派（PENDING）。 */
    suspend fun create(role: String, prompt: String, companionId: Long?): DelegationRecord

    /** 按 id 查询。 */
    suspend fun get(id: Long): DelegationRecord?

    /** 待执行委派（PENDING，按创建时间升序）。 */
    suspend fun pending(): List<DelegationRecord>

    /** 标记执行中。 */
    suspend fun markRunning(id: Long): Boolean

    /** 完成（写入结果 + 子回合 dispatchId）。 */
    suspend fun complete(id: Long, result: String, dispatchId: String?)

    /** 失败。 */
    suspend fun fail(id: Long, error: String)

    /** 最近记录（时间倒序，含全部状态；设置页/诊断用）。 */
    suspend fun recent(limit: Int): List<DelegationRecord>
}
