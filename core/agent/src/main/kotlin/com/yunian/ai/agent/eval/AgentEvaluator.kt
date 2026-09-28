package com.yunian.ai.agent.eval

import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.domain.eval.EvalCase
import com.yunian.ai.domain.eval.EvalCaseResult
import com.yunian.ai.domain.eval.EvalReport

/**
 * 评估运行器：真实回合（任意已配置模型）+ 断言 + 报告。
 *
 * 每次运行独立 dispatch（AgentRuntime turn_lock 串行安全）；
 * 轨迹自动经 AgentDispatchRecorder 入事件账本，可在事件账本/调度日志中回溯。
 *
 * 注意：Eval 使用真实网络与当前 active 模型配置（离线 mock 传输为 Rust 内部设施，
 * 未跨 UniFFI 暴露）；跑套件前应锁定目标模型，避免配置漂移影响可比性。
 */
object AgentEvaluator {

    suspend fun runCase(application: android.app.Application, case: EvalCase, companionId: Long = 0L): EvalCaseResult {
        val result = AgentFacade.runTurn(
            request = com.yunian.ai.agent.uniffi.AgentTurnRequest(
                groupId = null,
                historyJson = case.historyJson,
                tools = emptyList(),
                maxRounds = case.maxRounds,
                toolChoice = "",
                stickerProbability = 0u,
                image = null,
                systemPrompt = null,
                companionNameMapJson = null,
            ),
            context = application,
            companionId = companionId,
            toolHost = com.yunian.ai.agent.host.AgentToolHost(application),
        )
        val bubbleTexts = result.events.filter { it.kind == "bubble" }.map { it.text }
        return EvalAssertions.toResult(case, result.finalText, result.finishedReason, bubbleTexts)
    }

    /**
     * 离线 mock 运行单例：注入脚本化响应（Rust ScriptedTransport），零网络零成本。
     * 响应与回合轮次一一对应；跑完自动清空恢复真实传输。
     */
    suspend fun runCaseOffline(
        application: android.app.Application,
        case: EvalCase,
        scriptedResponses: List<String>,
    ): EvalCaseResult {
        com.yunian.ai.agent.AgentFacade.setMockTransport(application, scriptedResponses)
        try {
            return runCase(application, case)
        } finally {
            com.yunian.ai.agent.AgentFacade.setMockTransport(application, emptyList())
        }
    }

    /** 顺序执行套件（同一 Application 串行跑；失败不中断）。 */
    suspend fun runSuite(application: android.app.Application, cases: List<EvalCase>): EvalReport {
        val results = cases.map { runCase(application, it) }
        return EvalReport(total = results.size, passed = results.count { it.passed }, results = results)
    }
}
