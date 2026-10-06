package com.yunian.ai.feature.notification

import com.yunian.ai.agent.uniffi.StreamSink

/**
 * 主动消息的流式 reasoning 收集器（[StreamSink] 实现）。
 *
 * ## 它做什么
 *
 * 主动消息（CompanionMessageWorker）从非流式 [AgentFacade.runTurn] 改用流式
 * [AgentFacade.runTurnStream] 后，reasoning 经 [onReasoningDelta] 实时回调。
 * 本类收集全部 reasoning 增量、记录思考时长（第一个 delta → 最后一个 delta），
 * 供调用方落库为 REASONING 消息（像正式回复的「已思考 X 秒」）。
 *
 * ## 时长语义（对齐正式回复）
 *
 * - 第一个 reasoning delta 的时间 = 思考开始；
 * - 最后一个 reasoning delta 的时间 = 思考结束；
 * - durationMs = 结束 − 开始（≥0）。
 *
 * 正文增量（[onTextDelta]）也收集，供调用方取最终正文（备用，通常用
 * [com.yunian.ai.agent.uniffi.AgentTurnResult.finalText]）。
 */
class ProactiveReasoningSink : StreamSink {

    private val reasoningBuilder = StringBuilder()
    private val textBuilder = StringBuilder()

    /** 第一个 reasoning delta 的时间（思考开始）。 */
    var reasoningStartedAtMs: Long? = null
        private set

    /** 最后一个 reasoning delta 的时间（思考结束）。 */
    var reasoningEndedAtMs: Long? = null
        private set

    /** 思考是否出错（[onError] 被调）。 */
    var isError: Boolean = false
        private set

    override fun onTextDelta(text: String) {
        textBuilder.append(text)
    }

    override fun onReasoningDelta(text: String) {
        val now = System.currentTimeMillis()
        if (reasoningStartedAtMs == null) reasoningStartedAtMs = now
        reasoningEndedAtMs = now
        reasoningBuilder.append(text)
    }

    override fun onDone(fullText: String, finishReason: String) {
        // 完成：fullText 是完整正文，通常与 AgentTurnResult.finalText 一致，不重复收集。
    }

    override fun onError(error: String) {
        isError = true
    }

    /** 收集到的完整 reasoning 文本（空串 = 无思考）。 */
    fun reasoningText(): String = reasoningBuilder.toString().trim()

    /** 思考时长（毫秒）；无 reasoning 时 null。 */
    fun reasoningDurationMs(): Long? =
        reasoningStartedAtMs?.let { start ->
            reasoningEndedAtMs?.let { end -> (end - start).coerceAtLeast(0L) }
        }
}
