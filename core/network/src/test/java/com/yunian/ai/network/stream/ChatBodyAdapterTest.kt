package com.yunian.ai.network.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBodyAdapterTest {

    // ------------------------------------------------------------ 双向判定

    @Test
    fun `识别 SSE 响应体`() {
        assertTrue(ChatBodyAdapter.isSseBody("data: {\"a\":1}"))
        assertTrue(ChatBodyAdapter.isSseBody("\n\ndata: {\"a\":1}"))
        assertTrue(ChatBodyAdapter.isSseBody(": keep-alive\n\ndata: {}"))
        assertTrue(!ChatBodyAdapter.isSseBody("{\"choices\":[]}"))
    }

    // ------------------------------------------------------------ 非流式请求收到 SSE

    @Test
    fun `普通 JSON 响应不受影响`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}
        """.trimIndent()

        val parsed = ChatBodyAdapter.decodeCompletionBody(body)

        assertEquals("你好", parsed.choices?.first()?.message?.content)
        assertEquals("stop", parsed.choices?.first()?.finish_reason)
        assertNull(parsed.error)
    }

    @Test
    fun `SSE 分片被合并为完整内容`() {
        val body = sse(
            """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"delta":{"content":"今天"}}]}""",
            """{"choices":[{"delta":{"content":"天气不错"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        val parsed = ChatBodyAdapter.decodeCompletionBody(body)
        val choice = parsed.choices?.firstOrNull()

        assertNotNull(choice)
        assertEquals("今天天气不错", choice?.message?.content)
        assertEquals("stop", choice?.finish_reason)
    }

    @Test
    fun `SSE 心跳与 DONE 行被忽略`() {
        val body = ": ping\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n" +
            "data: [DONE]\n\n" +
            ": ping\n"

        val parsed = ChatBodyAdapter.decodeCompletionBody(body)

        assertEquals("A", parsed.choices?.first()?.message?.content)
    }

    @Test
    fun `SSE reasoning_content 被合并`() {
        val body = sse(
            """{"choices":[{"delta":{"reasoning_content":"先想一下。"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"然后作答。"}}]}""",
            """{"choices":[{"delta":{"content":"答案"}}]}""",
        )

        val message = ChatBodyAdapter.decodeCompletionBody(body).choices?.first()?.message

        assertEquals("答案", message?.content)
        assertEquals("先想一下。然后作答。", message?.reasoning_content)
    }

    @Test
    fun `SSE 以 message 形状返回也能兼容`() {
        // 少数接口把完整 message 塞进 data: 分片，而不是 delta
        val body = "data: {\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"整段内容\"}}]}\n\n"

        val parsed = ChatBodyAdapter.decodeCompletionBody(body)

        assertEquals("整段内容", parsed.choices?.first()?.message?.content)
    }

    @Test
    fun `SSE tool_calls 按 index 累积参数`() {
        val body = sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty\":\"北京\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"get_time","arguments":"{}"}}]}}]}""",
        )

        val calls = ChatBodyAdapter.decodeCompletionBody(body).choices?.first()?.message?.tool_calls

        assertEquals(2, calls?.size)
        assertEquals("call_a", calls?.get(0)?.id)
        assertEquals("get_weather", calls?.get(0)?.function?.name)
        assertEquals("""{"city":"北京"}""", calls?.get(0)?.function?.arguments)
        assertEquals("call_b", calls?.get(1)?.id)
        assertEquals("get_time", calls?.get(1)?.function?.name)
    }

    @Test
    fun `SSE 缺少 id 时生成占位 id`() {
        val body = sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"ping","arguments":"{}"}}]}}]}"""
        )

        val call = ChatBodyAdapter.decodeCompletionBody(body).choices?.first()?.message?.tool_calls?.first()

        assertNotNull(call?.id)
        assertTrue(call!!.id.isNotBlank())
    }

    @Test
    fun `SSE 错误分片返回 error`() {
        val body = "data: {\"error\":{\"message\":\"invalid api key\"}}\n\n"

        val parsed = ChatBodyAdapter.decodeCompletionBody(body)

        assertEquals("invalid api key", parsed.error?.message)
    }

    // ------------------------------------------------------------ 流式请求收到普通 JSON

    @Test
    fun `普通 JSON 被合成为单条 SSE 事件`() {
        val lines = ChatBodyAdapter.plainJsonToSseLines(
            """
            {
              "choices": [
                {"message": {"role": "assistant", "content": "一次性返回"}}
              ]
            }
            """.trimIndent()
        )

        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("data: "))
        assertEquals("data: [DONE]", lines[1])
        // 必须是单行，否则按行读取的流式适配器会把它切断
        assertTrue(!lines[0].contains("\n"))

        // 合成结果能被既有解析器原样消费
        val payload = OpenAiSseChunkParser.extractDataPayload(lines[0])
        assertEquals("一次性返回", OpenAiSseChunkParser.parseDataPayload(payload!!).content)
    }

    @Test
    fun `非法 JSON 走兜底压缩`() {
        val lines = ChatBodyAdapter.plainJsonToSseLines("{not json\n}")

        assertTrue(lines[0].startsWith("data: "))
        assertTrue(!lines[0].contains("\n"))
    }

    private fun sse(vararg payloads: String): String =
        payloads.joinToString(separator = "\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"
}
