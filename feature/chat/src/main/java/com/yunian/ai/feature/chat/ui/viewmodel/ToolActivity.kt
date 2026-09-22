package com.yunian.ai.feature.chat.ui.viewmodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 工具调用状态（OpenMinis 风格过程可视化的数据源）。
 *
 * 原先定义在 `AiToolLoopRunner.kt`；该文件随 Cordis Agent 接管文本路径而退役
 * （本地工具循环已由 Rust 侧 `AgentFacade` 承担），但本模型仍被
 * [ToolActivityCodec]、`ToolActivityBar`、`ChatRow`、`ChatListItem` 等渲染与持久化链使用，
 * 故独立成文件保留。
 */
@Serializable
enum class ToolStatus {
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
}

/**
 * 单次工具调用的活动记录：RUNNING 先入列，完成后以相同 id 覆写状态。
 * 会话切换时被持久化为 TOOL_ACTIVITY 消息（见 [ToolActivityCodec]）。
 */
@Serializable
data class ToolActivity(
    val id: Long,
    val toolName: String,
    val argsSummary: String,
    val status: ToolStatus,
    val resultSummary: String? = null,
    val startedAtMs: Long,
)
