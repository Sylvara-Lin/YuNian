package com.yunian.ai.common

/**
 * 模型思考程度（reasoning effort）——用户可调的全局设置。
 *
 * 档位随每回合 settings_json 热更新下发到 Rust Cordis Agent（键 `reasoning_effort`，
 * 见 `AgentFacade.buildSettingsJson`），由 Rust `native_gateway.rs` 按 provider 能力
 * 门控翻译成真实请求字段（OpenAI 语义 `reasoning_effort` / Anthropic extended
 * thinking `budget_tokens`），无法确认支持的 provider 一律不注入（宁可不发，也不 400）。
 *
 * 默认 [OFF] = 完全不注入参数 → 对既有用户零行为变化（与修复前逐字节一致）。
 */
enum class ReasoningEffort(val wire: String, val displayName: String) {
    /** 关闭：不注入任何思考参数，使用各 provider 服务端默认 */
    OFF("off", "默认"),

    /** 低：轻量思考（响应更快） */
    LOW("low", "低"),

    /** 中：均衡思考 */
    MEDIUM("medium", "中"),

    /** 高：深度思考（token 消耗更高、耗时更长） */
    HIGH("high", "高"),
    ;

    companion object {
        val DEFAULT = OFF

        /** 非法/缺省值回落 [DEFAULT]（防御：Settings UI 只产出合法 wire 值） */
        fun fromWire(raw: String?): ReasoningEffort =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
