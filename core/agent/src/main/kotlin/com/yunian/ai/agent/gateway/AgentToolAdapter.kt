package com.yunian.ai.agent.gateway

import com.yunian.ai.domain.AiTool

/**
 * Rust `ToolDefinition` → 领域 `AiTool` 适配器。
 *
 * 作用：把 Rust 侧组装的工具定义（name / description / parameters_json）
 * 翻译成 `List<AiTool>`，仅用于请求序列化。
 *
 * 说明：Agent 回合中工具的**实际执行**由 Rust 循环经 `ToolHost` 回调到 Kotlin
 * （`AgentToolHost`），因此本适配器的 [execute] 不会被调用（序列化只读取
 * name/description/parametersJsonSchema 拼 request JSON）；这里返回空串作为防御。
 */
class AgentToolAdapter(
    override val name: String,
    override val description: String,
    override val parametersJsonSchema: String,
) : AiTool {

    override suspend fun execute(argumentsJson: String): String = ""
}
