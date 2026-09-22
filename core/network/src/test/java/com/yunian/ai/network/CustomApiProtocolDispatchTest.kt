package com.yunian.ai.network

import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomApiProtocolDispatchTest {

    @Test
    fun usesAnthropicProtocol_isTrueOnlyForCustomAnthropicHintOrAnthropicProvider() {
        val customAnthropic = ApiConfig(
            provider = ApiProvider.CUSTOM,
            apiKey = "sk-test",
            baseUrl = "https://relay.example.com/v1",
            model = "claude-3-5-sonnet",
            formatHint = "anthropic"
        )
        val customOpenAi = customAnthropic.copy(formatHint = "openai")
        val anthropic = customAnthropic.copy(provider = ApiProvider.ANTHROPIC)

        assertTrue(AiService.usesAnthropicProtocol(customAnthropic))
        assertTrue(AiService.usesAnthropicProtocol(anthropic))
        assertFalse(AiService.usesAnthropicProtocol(customOpenAi))
    }

    @Test
    fun supportsOpenAiModelList_isFalseForCustomAnthropicHint() {
        val customAnthropic = ApiConfig(
            provider = ApiProvider.CUSTOM,
            apiKey = "sk-test",
            baseUrl = "https://relay.example.com/v1",
            model = "claude-3-5-sonnet",
            formatHint = "anthropic"
        )
        val customOpenAi = customAnthropic.copy(formatHint = "openai")
        val openAi = customAnthropic.copy(provider = ApiProvider.OPENAI, formatHint = "openai")

        assertFalse(AiService.supportsOpenAiModelList(customAnthropic))
        assertTrue(AiService.supportsOpenAiModelList(customOpenAi))
        assertTrue(AiService.supportsOpenAiModelList(openAi))
    }
}
