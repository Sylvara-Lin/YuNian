package com.yunian.ai.network

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ApiProvider

object ModelContextRegistry {

    private const val DEFAULT_CONTEXT_WINDOW = 32_000

    private val modelPatterns = listOf(

        "gemini-1.5-pro" to 2_097_152,
        "gemini-2.5-pro" to 2_097_152,
        "gemini-1.5-flash" to 1_048_576,
        "gemini-2.5-flash" to 1_048_576,
        "gemini-2.0-flash" to 1_048_576,
        "gemini" to 1_048_576,

        "claude-3-5-sonnet" to 200_000,
        "claude-3-5-haiku" to 200_000,
        "claude-3-7-sonnet" to 200_000,
        "claude-3-opus" to 200_000,
        "claude-sonnet-4" to 200_000,
        "claude-opus-4" to 200_000,
        "claude" to 200_000,

        "gpt-4o-mini" to 128_000,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4.1" to 1_048_576,
        "o1" to 200_000,
        "o3" to 200_000,
        "o4-mini" to 200_000,
        "gpt-4" to 8_192,
        "gpt-3.5" to 16_385,

        "deepseek-v3" to 64_000,
        "deepseek-v4" to 128_000,
        "deepseek-r1" to 64_000,
        "deepseek" to 64_000,

        "qwen-max" to 32_768,
        "qwen-plus" to 131_072,
        "qwen-turbo" to 1_000_000,
        "qwen2.5" to 131_072,
        "qwen3" to 131_072,
        "qwen" to 131_072,

        "kimi-k2" to 131_072,
        "kimi" to 131_072,
        "moonshot" to 131_072,

        "glm-4" to 128_000,
        "glm" to 128_000,

        "mimo" to 131_072,

        "llama-3.1" to 131_072,
        "llama-3.3" to 131_072,
        "llama" to 8_192,

        "Qwen/Qwen2.5-7B" to 32_768,
        "Qwen/Qwen2.5-14B" to 32_768,
        "Qwen/Qwen2.5-72B" to 131_072,
        "deepseek-ai/DeepSeek-V3" to 64_000,
        "deepseek-ai/DeepSeek-R1" to 64_000,
    )

    /**
     * 匹配用 pattern 列表：见下方 [init] 块的排序说明。
     */
    private val orderedPatterns: List<Pair<String, Int>>

    init {
        // 按 pattern 长度降序做一次稳定排序：长 pattern（更具体，如 "gemini-2.5-pro"）
        // 永远先于其子串（如 "gemini"）被 contains 命中。
        // 一次性消除手写顺序对 contains 子串匹配的顺序敏感性，避免后续维护插入顺序出错。
        orderedPatterns = modelPatterns
            .asSequence()
            .map { (pattern, window) -> pattern.lowercase() to window }
            .sortedByDescending { it.first.length }
            .toList()
    }

    private val providerDefaults = mapOf(
        ApiProvider.GEMINI to 1_048_576,
        ApiProvider.ANTHROPIC to 200_000,
        ApiProvider.OPENAI to 128_000,
        ApiProvider.DEEPSEEK to 64_000,
        ApiProvider.DASHSCOPE to 131_072,
        ApiProvider.KIMI to 131_072,
        ApiProvider.ZHIPU to 128_000,
        ApiProvider.XIAOMI to 131_072,
        ApiProvider.SILICONFLOW to 32_768,
        ApiProvider.OPENROUTER to 128_000,
        ApiProvider.GROQ to 131_072,
        ApiProvider.PARTNER to 128_000,
        ApiProvider.IFLYTEK to 8_192,
        ApiProvider.CUSTOM to DEFAULT_CONTEXT_WINDOW,
    )

    fun getContextWindow(model: String): Int {
        if (model.isBlank()) return DEFAULT_CONTEXT_WINDOW

        val modelLower = model.lowercase()
        for ((pattern, window) in orderedPatterns) {
            if (modelLower.contains(pattern)) {
                return window
            }
        }
        SecureLog.d("ModelContextRegistry", "Unknown model '$model', using default $DEFAULT_CONTEXT_WINDOW")
        return DEFAULT_CONTEXT_WINDOW
    }

    fun getContextWindow(model: String, provider: ApiProvider?): Int {
        if (model.isBlank()) {
            return providerDefaults[provider] ?: DEFAULT_CONTEXT_WINDOW
        }

        val modelLower = model.lowercase()
        for ((pattern, window) in orderedPatterns) {
            if (modelLower.contains(pattern)) {
                return window
            }
        }

        return providerDefaults[provider] ?: DEFAULT_CONTEXT_WINDOW
    }
}
