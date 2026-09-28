package com.yunian.ai.domain

/**
 * 统一 AI 对话中间层（通道桥接层 ↔ Agent 核心）。
 *
 * 微信 / QQ 等外部通道的桥接层只负责消息收发（监听、映射、分段发送等通道特有逻辑）；
 * AI 回合、安全检查、落库、记忆提取、亲密度更新等全部收敛到此接口，
 * 由 core:agent 实现（[com.yunian.ai.agent.AgentDialogueCoordinator]）并经
 * [ServiceRegistry] 注入，避免各 feature 通道模块依赖 AI 管线。
 *
 * 落库语义：本接口负责「用户消息 + AI 回复」的入库（通道消息来源外部，
 * 必须落库形成对话上下文）；app 内对话（UI / 通知 / 语音条）已由调用方落库，
 * 不走本接口。
 */
interface DialogueCoordinator {

    /**
     * 生成一轮对话回复（文本或视觉）。
     *
     * 内部流程（对齐「决策在 Rust」）：封禁检查 → 输入安全过滤 → 落库用户消息
     * → 读历史 → AgentRuntime.run_turn（含 syncRuntimeConfig）→ 输出安全过滤
     * → 落库 AI 回复 → 记忆提取 / 亲密度更新。
     *
     * @return [DialogueResult.replyText] 为可直接发送的回复文本（blocked 时亦可能
     *         携带安全话术），由桥接层负责通道发送。
     */
    suspend fun generateReply(request: DialogueRequest): DialogueResult
}

/** 中间层对话请求。[text] 与 [imagePath] 至少一个非空。 */
data class DialogueRequest(
    /** 目标伴侣 ID */
    val companionId: Long,
    /** 文本消息内容 */
    val text: String? = null,
    /** 图片本地路径（视觉链路，走 run_turn 的 image 输入） */
    val imagePath: String? = null,
)

/** 中间层对话结果。 */
data class DialogueResult(
    /** 可直接发送的回复文本 */
    val replyText: String,
    /** 是否被安全机制拦截（违规/封禁），桥接层可据此决定发送策略 */
    val blocked: Boolean = false,
    /** 已落库的 AI 回复消息 ID（未落库时为 null） */
    val assistantMessageId: Long? = null,
)
