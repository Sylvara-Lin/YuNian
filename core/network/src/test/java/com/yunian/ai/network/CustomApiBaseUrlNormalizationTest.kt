package com.yunian.ai.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomApiBaseUrlNormalizationTest {

    @Test
    fun normalizeOpenAiBaseUrl_preservesNonV1Path() {
        val input = "https://relay.example.com/api"
        val expected = "https://relay.example.com/api"

        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun normalizeOpenAiBaseUrl_preservesV1Path() {
        val input = "https://api.openai.com/v1"
        val expected = "https://api.openai.com/v1"
        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun normalizeOpenAiBaseUrl_trimsTrailingSlash() {
        val input = "https://relay.example.com/api/"
        val expected = "https://relay.example.com/api"
        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun normalizeOpenAiBaseUrl_preservesRootPath() {
        val input = "https://relay.example.com/"
        val expected = "https://relay.example.com"
        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun normalizeOpenAiBaseUrl_preservesNonV1VersionedPath() {
        val input = "https://gateway.example.com/v2"
        val expected = "https://gateway.example.com/v2"
        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun normalizeOpenAiBaseUrl_preservesVolcengineApiV3Path() {
        val input = "https://ark.cn-beijing.volces.com/api/v3"
        val expected = "https://ark.cn-beijing.volces.com/api/v3"
        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun normalizeOpenAiBaseUrl_trimsWhitespace() {
        val input = "  https://relay.example.com/api  "
        val expected = "https://relay.example.com/api"
        val actual = input.trim().trimEnd('/')
        assertEquals(expected, actual)
    }

    @Test
    fun fetchModels_url_doesNotAutoAppendV1() {
        val input = "https://relay.example.com/api"
        val modelsUrl = "${input.trim().trimEnd('/')}/models"
        assertEquals("https://relay.example.com/api/models", modelsUrl)
    }

    @Test
    fun fetchModels_url_supportsVolcengineApiV3() {
        val input = "https://ark.cn-beijing.volces.com/api/v3"
        val modelsUrl = "${input.trim().trimEnd('/')}/models"
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/models", modelsUrl)
    }

    @Test
    fun chatUrl_doesNotAutoAppendV1() {
        val input = "https://gateway.example.com/v2"
        val chatUrl = "${input.trim().trimEnd('/')}/chat/completions"
        assertEquals("https://gateway.example.com/v2/chat/completions", chatUrl)
    }
}
