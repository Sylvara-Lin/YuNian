package com.yunian.ai.network

import com.yunian.ai.database.model.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ModelContextRegistry 单元测试（QA/T04）。
 *
 * 重点验证：init 块按 pattern 长度降序排序后，长（更具体的）pattern
 * 永远先于其子串被 contains 命中，不再依赖手写声明顺序。
 */
class ModelContextRegistryTest {

    // ---------- 具体 pattern 优先于子串 pattern ----------

    @Test
    fun `qwen-max hits specific pattern not qwen substring`() {
        // "qwen-max"=32768，绝不能落进子串 "qwen"=131072
        assertEquals(32_768, ModelContextRegistry.getContextWindow("qwen-max"))
    }

    @Test
    fun `qwen-plus hits specific pattern`() {
        assertEquals(131_072, ModelContextRegistry.getContextWindow("qwen-plus"))
    }

    @Test
    fun `qwen-turbo hits specific pattern`() {
        assertEquals(1_000_000, ModelContextRegistry.getContextWindow("qwen-turbo"))
    }

    @Test
    fun `gpt-4o hits 128k not gpt-4 8k`() {
        // "gpt-4o".contains("gpt-4") 为 true，若排序失效会错误命中 8192
        assertEquals(128_000, ModelContextRegistry.getContextWindow("gpt-4o"))
    }

    @Test
    fun `gpt-4 still maps to 8192`() {
        assertEquals(8_192, ModelContextRegistry.getContextWindow("gpt-4"))
    }

    @Test
    fun `kimi-k2 maps to 131072`() {
        assertEquals(131_072, ModelContextRegistry.getContextWindow("kimi-k2"))
    }

    @Test
    fun `kimi bare name maps to 131072`() {
        assertEquals(131_072, ModelContextRegistry.getContextWindow("kimi"))
    }

    @Test
    fun `gemini-2-5-pro beats gemini substring`() {
        assertEquals(2_097_152, ModelContextRegistry.getContextWindow("gemini-2.5-pro"))
    }

    @Test
    fun `bare gemini falls back to 1M`() {
        assertEquals(1_048_576, ModelContextRegistry.getContextWindow("gemini"))
    }

    @Test
    fun `deepseek-v3 beats deepseek substring`() {
        assertEquals(64_000, ModelContextRegistry.getContextWindow("deepseek-v3"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(128_000, ModelContextRegistry.getContextWindow("GPT-4O"))
        assertEquals(2_097_152, ModelContextRegistry.getContextWindow("Gemini-2.5-Pro"))
    }

    // ---------- 未知模型 / 兜底 ----------

    @Test
    fun `unknown model uses default window`() {
        assertEquals(32_000, ModelContextRegistry.getContextWindow("totally-unknown-model-xyz"))
    }

    @Test
    fun `unknown model falls back to provider default`() {
        assertEquals(1_048_576, ModelContextRegistry.getContextWindow("unknown-xyz", ApiProvider.GEMINI))
        assertEquals(200_000, ModelContextRegistry.getContextWindow("unknown-xyz", ApiProvider.ANTHROPIC))
        assertEquals(64_000, ModelContextRegistry.getContextWindow("unknown-xyz", ApiProvider.DEEPSEEK))
    }

    @Test
    fun `blank model uses provider default when given`() {
        assertEquals(131_072, ModelContextRegistry.getContextWindow("", ApiProvider.KIMI))
    }

    @Test
    fun `blank model without provider uses default window`() {
        assertEquals(32_000, ModelContextRegistry.getContextWindow(""))
        assertEquals(32_000, ModelContextRegistry.getContextWindow("", null))
    }

    @Test
    fun `known model with provider still wins over provider default`() {
        // 模型 pattern 命中优先于 provider 兜底
        assertEquals(8_192, ModelContextRegistry.getContextWindow("gpt-4", ApiProvider.OPENAI))
    }
}
