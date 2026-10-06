package com.yunian.ai.network

import com.yunian.ai.common.ReasoningEffort
import com.yunian.ai.database.model.ApiProvider
import org.json.JSONObject

/**
 * 模型思考程度（reasoning effort）适配助手（core:network 内部可见）。
 *
 * 背景（用户需求「可以调节模型思考程度」）：档位由 `AppSettingsStore` 持久化
 * （`ReasoningEffort`：off/low/medium/high，默认 off = 不注入参数 → 对既有用户
 * 零行为变化）。文本/群聊/主消息等生成路径已由 Rust Cordis Agent
 * （`native_gateway.rs` 的 reasoning effort 门控）承担；本助手只服务**尚未下沉
 * Rust 的 Kotlin 生成路径**（`AiService.callOpenAiCompatibleVision` 识图链路）。
 *
 * === provider 能力门控（与 Rust `native_gateway.rs` 同一证据集，红线一致）===
 * 只对「官方文档确认支持 OpenAI 语义 `reasoning_effort`（low/medium/high）」的
 * provider 注入；无法确认 / 明确报错 / 模型相关的一律**不注入**（宁可不发，
 * 也不破坏可用性）：
 *  - [ApiProvider.OPENAI]：仅推理模型（o 系 / GPT-5）支持，非推理模型传参 400
 *    → 模型名门控 [looksLikeReasoningModel]（默认 gpt-4o-mini 不注入）。
 *  - [ApiProvider.DEEPSEEK]：api-docs.deepseek.com/api/create-chat-completion —
 *    `reasoning_effort` 取值 none/low/high/max（默认 high，medium 兼容映射为 high）。
 *  - [ApiProvider.OPENROUTER]：openrouter.ai/docs/api-reference/parameters —
 *    `reasoning_effort` 为 OpenAI 语义透传，不支持时由其归一/忽略。
 *  - 其余（KIMI / GEMINI / ZHIPU / SILICONFLOW / DASHSCOPE / XIAOMI / GROQ /
 *    IFLYTEK / PARTNER / CUSTOM）：不支持 / 明确报错 / 模型相关 → 不注入。
 *    （ANTHROPIC 走独立 Messages API 与 extended thinking 字段，不经本助手。）
 *
 * 这是 Kotlin 侧**唯一**的思考程度写入点（单一门控原则）：禁止在请求构造点散落判断。
 */

internal fun JSONObject.applyReasoningEffort(
    provider: ApiProvider,
    model: String,
    effort: String,
): Boolean {
    val normalized = ReasoningEffort.fromWire(effort)
    if (normalized == ReasoningEffort.OFF) return false
    val value = when (provider) {
        ApiProvider.OPENAI ->
            if (looksLikeReasoningModel(model)) normalized.wire else return false
        ApiProvider.DEEPSEEK, ApiProvider.OPENROUTER -> normalized.wire
        else -> return false
    }
    put("reasoning_effort", value)
    return true
}

/**
 * 模型名是否像推理模型（o1/o3/o4 系 / GPT-5 系）。
 *
 * 仅 OPENAI 需要：OpenAI 对非推理模型传 `reasoning_effort` 返回 400
 * （unsupported_parameter），而门控拿不到模型推理能力 → 模型名启发式是
 * 「不破坏默认 gpt-4o-mini 可用性」的唯一低成本手段（与
 * `AiService.requiresFixedTemperature` 的模型名门控同构）。
 */
internal fun looksLikeReasoningModel(model: String): Boolean {
    val m = model.trim().lowercase()
    return m.startsWith("o1") ||
        m.startsWith("o3") ||
        m.startsWith("o4") ||
        m.startsWith("gpt-5") ||
        m.startsWith("gpt5")
}
