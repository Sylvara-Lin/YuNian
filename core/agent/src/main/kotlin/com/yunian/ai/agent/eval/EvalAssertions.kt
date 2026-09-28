package com.yunian.ai.agent.eval

import com.yunian.ai.domain.eval.EvalCase
import com.yunian.ai.domain.eval.EvalCaseResult

/**
 * 评估断言引擎（纯函数，JVM 可测）。
 *
 * 规则：
 * - expectTextContains：finalText 必须包含每个子串；
 * - expectBubbleContains：事件流 bubble 文本（整体拼接）必须包含每个子串；
 * - expectFinishedReason：非空时精确匹配。
 */
object EvalAssertions {

    fun failures(
        case: EvalCase,
        finalText: String,
        finishedReason: String,
        bubbleTexts: List<String>,
    ): List<String> {
        val failures = mutableListOf<String>()
        val joinedBubbles = bubbleTexts.joinToString("\n")
        case.expectTextContains.forEach { expected ->
            if (!finalText.contains(expected)) {
                failures.add("finalText 缺少片段「$expected」（实际：${finalText.take(120)}…）")
            }
        }
        case.expectBubbleContains.forEach { expected ->
            if (!joinedBubbles.contains(expected)) {
                failures.add("bubble 流缺少片段「$expected」（实际：${joinedBubbles.take(120)}…）")
            }
        }
        case.expectFinishedReason?.let { expected ->
            if (finishedReason != expected) {
                failures.add("finishedReason 期望「$expected」实际「$finishedReason」")
            }
        }
        return failures
    }

    fun toResult(case: EvalCase, finalText: String, finishedReason: String, bubbleTexts: List<String>): EvalCaseResult {
        val failures = failures(case, finalText, finishedReason, bubbleTexts)
        return EvalCaseResult(
            caseName = case.name,
            passed = failures.isEmpty(),
            failures = failures,
            finalText = finalText,
            finishedReason = finishedReason,
            bubbleTexts = bubbleTexts,
        )
    }
}
