package com.yunian.ai.domain.timeline

/**
 * 助手时间线架构说明（切片 1：仅契约，无运行时接线）。
 *
 * ## 原子边界
 * - domain/timeline：事件模型、策略、codec 注册、存储端口
 * - domain/stream：AssistantStreamEvent 传输增量
 * - database：TimelineStore 实现 + Message 投影（后续切片）
 * - network：Provider → Flow of AssistantStreamEvent（后续切片）
 * - feature/chat：PendingTurn / CommitRule / UiProjector（后续切片）
 *
 * ## 硬约束
 * 1. feature 互不依赖；扩展靠注册 Codec / Policy / Rule / Projector
 * 2. STREAMING 不落库；ReasoningCompleted 后再 appendComplete
 * 3. 模型历史默认排除 REASONING（[DefaultModelContextPolicy]）
 * 4. 不把时间线语义下沉 Native；Native 仅安全/密钥
 * 5. 禁止 ChatMessage.reasoningContent 旁路字段作为主模型
 * 6. 思考过程作为独立消息复用消息链路（MessageCache / metadata / ChatListItem）；
 *    禁止 ChatScreen ephemeral `reasoning_indicator` 旁路渲染
 *
 * ## 展示顺序
 * 同一 turnId：eventIndex 升序 → 思考过程行 → 消息回复行
 *
 * ## 流式思考
 * L1 临时负 id REASONING 消息（StreamingReasoningMessagePipeline）→ 列表；
 * 终态 appendComplete 后移除临时行，由 Room 正 id 接管。
 *
 * ## 收起文案
 * [ReasoningDurationFormatter.collapsedLabel] →「已思考{n}秒」
 *
 * ## 切片进度
 * 1 domain 契约 — 完成
 * 2 DB MessageType.REASONING + turnId/eventIndex/durationMs + RoomTimelineStore — 完成
 * 3 ChatListItem.ReasoningMessage + Projector +「已思考{n}秒」— 完成
 * 4 PendingTurn + CommitRules + TimelineStore 绑定 + 终态落库 — 完成
 * 5 Network 非流式适配 + PendingTurnStreamApplier + 节流投影 — 完成
 * 6 真实 SSE stream=true（OpenAI 兼容）+ streamMessage 管道 — 完成
 *    tools / vision / local / Anthropic 仍降级非流式终态事件
 * 7 流式 REASONING 复用消息链路 + 移除旧版 ephemeral UI — 完成
 */
object TimelineArchitecture {
    const val SLICE = 7
    const val DOC_VERSION = "2026-07-20"
}
