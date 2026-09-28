package com.yunian.ai.domain.eval

/**
 * Agent 评估基础（Eval）。
 *
 * 设计：离线可断言的评估闭环——数据集（EvalCase）→ 真实回合（AgentFacade.runTurn，
 * 可选任意已配置模型）→ 纯函数断言（EvalAssertions）→ 报告（EvalReport）。
 * 轨迹留存：每例跑完自动经 AgentDispatchRecorder 入事件账本（agent.turn.completed，
 * 幂等键=dispatchId），可与事件账本/调度日志交叉审计。
 */

/** 评估用例（数据集条目）。 */
data class EvalCase(
    val name: String,
    /** 对话历史 JSON（OpenAI messages 数组）。 */
    val historyJson: String,
    val maxRounds: UInt = 3u,
    /** 断言：最终回复必须包含的子串（全部命中才通过）。 */
    val expectTextContains: List<String> = emptyList(),
    /** 断言：事件流中 bubble 文本必须包含的子串。 */
    val expectBubbleContains: List<String> = emptyList(),
    /** 断言：finishedReason 精确匹配（null 不校验）。 */
    val expectFinishedReason: String? = null,
)

/** 单例评估结果。 */
data class EvalCaseResult(
    val caseName: String,
    val passed: Boolean,
    val failures: List<String>,
    val finalText: String,
    val finishedReason: String,
    val bubbleTexts: List<String>,
)

/** 套件报告。 */
data class EvalReport(
    val total: Int,
    val passed: Int,
    val results: List<EvalCaseResult>,
) {
    val passRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total
}
