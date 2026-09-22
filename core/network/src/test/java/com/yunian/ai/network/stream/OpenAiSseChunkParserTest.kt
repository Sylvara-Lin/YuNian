package com.yunian.ai.network.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiSseChunkParserTest {

    @Test
    fun extractDataPayload_parsesDataLine() {
        assertEquals("{\"a\":1}", OpenAiSseChunkParser.extractDataPayload("data: {\"a\":1}"))
        assertEquals("[DONE]", OpenAiSseChunkParser.extractDataPayload("data: [DONE]"))
        assertNull(OpenAiSseChunkParser.extractDataPayload(": keep-alive"))
        assertNull(OpenAiSseChunkParser.extractDataPayload(""))
        assertNull(OpenAiSseChunkParser.extractDataPayload("event: message"))
    }

    @Test
    fun parseDataPayload_done() {
        val d = OpenAiSseChunkParser.parseDataPayload("[DONE]")
        assertTrue(d.done)
    }

    @Test
    fun parseDataPayload_contentAndReasoning() {
        val json = """
            {"choices":[{"delta":{"content":"hi","reasoning_content":"think"},"finish_reason":null}]}
        """.trimIndent()
        val d = OpenAiSseChunkParser.parseDataPayload(json)
        assertEquals("hi", d.content)
        assertEquals("think", d.reasoning)
        assertNull(d.finishReason)
    }

    @Test
    fun parseDataPayload_customReasoningField() {
        val json = """{"choices":[{"delta":{"thinking":"abc"}}]}"""
        val d = OpenAiSseChunkParser.parseDataPayload(json, listOf("thinking"))
        assertEquals("abc", d.reasoning)
    }

    @Test
    fun parseDataPayload_error() {
        val json = """{"error":{"message":"quota exceeded"}}"""
        val d = OpenAiSseChunkParser.parseDataPayload(json)
        assertEquals("quota exceeded", d.errorMessage)
        assertTrue(d.done)
    }

    @Test
    fun parseUsagePayload_extractsRealTokens() {
        val json = """{"choices":[],"usage":{"prompt_tokens":1234,"completion_tokens":56,"total_tokens":1290}}"""
        val usage = OpenAiSseChunkParser.parseUsagePayload(json)
        assertEquals(1234L, usage?.promptTokens)
        assertEquals(56L, usage?.completionTokens)
    }

    @Test
    fun parseUsagePayload_returnsNullWhenAbsent() {
        assertNull(OpenAiSseChunkParser.parseUsagePayload("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertNull(OpenAiSseChunkParser.parseUsagePayload("[DONE]"))
        assertNull(OpenAiSseChunkParser.parseUsagePayload(""))
    }

    @Test
    fun parseUsagePayload_ignoresZeroTokens() {
        val json = """{"choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""
        assertNull(OpenAiSseChunkParser.parseUsagePayload(json))
    }
}
