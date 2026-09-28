package com.yunian.ai.network.stream

import com.yunian.ai.network.ChatCompletionResponse
import com.yunian.ai.network.Choice
import com.yunian.ai.network.ErrorDetail
import com.yunian.ai.network.Message
import com.yunian.ai.network.ToolCallFunction
import com.yunian.ai.network.ToolCallRaw
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 请求形态与响应形态不一致时做双向兜底，避免因上游“不按套路出牌”直接把错误抛给用户。
 *
 * 1. **非流式请求收到 SSE**：部分中转站/聚合站无视 `stream: false`，强行返回 `data: {...}` 流式分片。
 *    此时把分片合并成一份完整的 [ChatCompletionResponse]，聊天照常可用。
 * 2. **流式请求收到普通 JSON**：接口不支持流式时会把完整 JSON 一次性返回，
 *    此时用 [plainJsonToSseLines] 合成为单条 SSE 事件，流式适配器才不会收到零事件。
 *
 * 解析复用 [OpenAiSseChunkParser]（它本身同时兼容 `delta` 与 `message` 两种形状），
 * 仅额外补上它不处理的 `tool_calls` 分片累积。
 */
object ChatBodyAdapter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** 判断响应体是否为 SSE（`data:` 分片或注释行开头）。 */
    fun isSseBody(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("data:") || trimmed.startsWith(":")
    }

    /**
     * 解析非流式响应体。若上游实际返回的是 SSE，则把全部分片合并为一个完整响应。
     *
     * @throws kotlinx.serialization.SerializationException 仅当响应体既不是 SSE 也不是合法 JSON 时
     */
    fun decodeCompletionBody(
        body: String,
        reasoningFields: Collection<String> = OpenAiSseChunkParser.DEFAULT_REASONING_FIELDS
    ): ChatCompletionResponse {
        if (!isSseBody(body)) return json.decodeFromString(body)

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = LinkedHashMap<Int, RawToolCall>()
        var finishReason: String? = null
        var errorMessage: String? = null

        body.lineSequence().forEach { line ->
            val payload = OpenAiSseChunkParser.extractDataPayload(line) ?: return@forEach
            if (payload.isBlank() || payload.equals("[DONE]", ignoreCase = true)) return@forEach

            val delta = OpenAiSseChunkParser.parseDataPayload(payload, reasoningFields)
            // 注意：错误分片同样会带 done=true，必须先判错误再判 done
            delta.errorMessage?.let {
                if (errorMessage == null) errorMessage = it
                return@forEach
            }
            if (delta.done) return@forEach

            delta.content?.let(content::append)
            delta.reasoning?.let(reasoning::append)
            delta.finishReason?.let { finishReason = it }
            collectToolCalls(payload, toolCalls)
        }

        if (errorMessage != null) {
            return ChatCompletionResponse(error = ErrorDetail(errorMessage))
        }

        val mergedToolCalls = toolCalls.entries
            .sortedBy { it.key }
            .map { (index, acc) -> acc.toRaw(index) }
            .takeIf { it.isNotEmpty() }

        return ChatCompletionResponse(
            choices = listOf(
                Choice(
                    message = Message(
                        role = "assistant",
                        content = content.toString().ifEmpty { null },
                        reasoning_content = reasoning.toString().ifEmpty { null },
                        tool_calls = mergedToolCalls,
                    ),
                    finish_reason = finishReason,
                )
            ),
        )
    }

    /**
     * 把一份完整的（非流式）响应体合成为 SSE 行，供流式适配器消费。
     *
     * 之所以能直接复用原文：`OpenAiSseChunkParser.parseDataPayload` 同时读取 `delta` 与 `message`，
     * 所以 `{"choices":[{"message":{...}}]}` 这类结构无需改写。
     */
    fun plainJsonToSseLines(body: String): List<String> {
        val compact = runCatching { json.parseToJsonElement(body).toString() }.getOrNull()
            ?: body.replace("\r", " ").replace("\n", " ")
        return listOf("data: $compact", "data: [DONE]", "")
    }

    // ------------------------------------------------------------------ 内部

    private class RawToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun toRaw(index: Int) = ToolCallRaw(
            id = id.ifBlank { "call_$index" },
            type = "function",
            function = ToolCallFunction(name = name, arguments = arguments.toString()),
        )
    }

    /**
     * 累积 `delta.tool_calls` 分片。
     *
     * 流式协议用 `index` 区分同一个响应里的多个并行工具调用——不能用数组下标，
     * 因为每个分片通常只带一个元素（下标恒为 0）。
     */
    private fun collectToolCalls(payload: String, into: MutableMap<Int, RawToolCall>) {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val container = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject) ?: return
        val calls = container["tool_calls"] as? JsonArray ?: return

        calls.forEach { element ->
            val call = element as? JsonObject ?: return@forEach
            val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0
            val acc = into.getOrPut(index) { RawToolCall() }

            (call["id"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { acc.id = it }

            val function = call["function"] as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { acc.name = it }
            (function?.get("arguments") as? JsonPrimitive)?.contentOrNull
                ?.let { acc.arguments.append(it) }
        }
    }
}
