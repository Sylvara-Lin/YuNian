package com.yunian.ai.agent.host

/**
 * 单次工具执行的阶段（仅过程态，供上层实时渲染过程卡片；不承载任何决策）。
 *
 * 约定：RUNNING 先入列，完成后以同一 [ToolCallProgress.callId] 覆写为终态（DONE / FAILED）。
 */
enum class ToolCallPhase {
    /** 正在执行（对应 UI 的脉动/「执行中」态）。 */
    RUNNING,

    /** 执行完成（成功）。 */
    DONE,

    /** 执行失败（异常 / 超时 / 被取消）。 */
    FAILED,
}

/**
 * 工具执行进度快照：由 [AgentToolHost] 在每次工具执行「开始前」与「结束后」回调给上层。
 *
 * 归属 core:agent（不依赖任何 feature 模块）；feature 层负责映射为自己的展示模型
 * （如 feature:chat 的 `ToolActivity`），从而把「Rust Agent 路径的真实工具执行」接入
 * 消息流里的过程卡片（此前该链路缺失，导致卡片恒为空）。
 *
 * @property callId 本次执行的自增序号（跨回合唯一，用于 RUNNING→终态同 id 覆写）。
 * @property toolName 工具名（原始名；展示友好名由上层映射）。
 * @property argsSummary 参数摘要（单行、已截断，直接可展示）。
 * @property phase 执行阶段。
 * @property resultSummary 结果摘要（终态才有；RUNNING 为 null）。
 * @property startedAtMs 开始时间戳（毫秒）。
 * @property elapsedMs 已耗时（毫秒；RUNNING 为 0，终态为实际耗时）。
 */
data class ToolCallProgress(
    val callId: Long,
    val toolName: String,
    val argsSummary: String,
    val phase: ToolCallPhase,
    val resultSummary: String?,
    val startedAtMs: Long,
    val elapsedMs: Long,
)
