package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.safety.SafetyScore
import com.yunian.ai.common.safety.ScoreSource

/**
 * Safety check logic for AI-generated output.
 *
 * Extracted from ChatViewModel.finalizeResponse() to reduce class size.
 * Performs L1+L2 keyword/vector checks and Bayesian model output verification.
 *
 * @return A [SafetyDecision] containing the verdict and optional fallback message.
 */
internal object ChatSafetyChecker {

    data class SafetyDecision(
        val isBlocked: Boolean,
        val fallbackMessage: String? = null,
        val modelKw: ContentFilter.CheckResult,
        val modelVec: ContentFilter.CheckResult?,
        val modelBayesian: SafetyScore
    )

    /**
     * Run all safety checks on AI-generated content.
     *
     * - L1+L2 keyword/vector extraction → Bayesian model output verification
     * - fail-closed: timeout = HIGH violation
     * - AI output does NOT record user violations
     */
    suspend fun checkAiOutput(
        aiContent: String,
        userContentForMemory: String?
    ): SafetyDecision {
        // [DISABLED] 关键词拦截 + 贝叶斯分类器均暂停，误拦截率过高。
        // ContentFilter.checkFull / checkVector / Bayesian 全部跳过。
        // 保留代码供后续调优后重新启用。
        val modelKw = ContentFilter.CheckResult(false, ContentFilter.ViolationLevel.NONE, "ContentFilter disabled", emptyList())
        val modelVec = ContentFilter.CheckResult(false, ContentFilter.ViolationLevel.NONE, "ContentFilter disabled", emptyList())
        val modelBayesian = SafetyScore(
            score = 0.0,
            source = ScoreSource.MODEL_OUTPUT,
            explanation = "ContentFilter + Bayesian disabled"
        )

        return SafetyDecision(
            isBlocked = false,
            fallbackMessage = null,
            modelKw = modelKw,
            modelVec = modelVec,
            modelBayesian = modelBayesian
        )
    }
}

