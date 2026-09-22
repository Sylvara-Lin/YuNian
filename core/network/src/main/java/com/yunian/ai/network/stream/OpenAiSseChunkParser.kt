package com.yunian.ai.network.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object OpenAiSseChunkParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Delta(
        val content: String? = null,
        val reasoning: String? = null,
        val finishReason: String? = null,
        val errorMessage: String? = null,
        val done: Boolean = false,
    )

    data class StreamUsage(
        val promptTokens: Long,
        val completionTokens: Long,
    )

    fun parseUsagePayload(dataPayload: String): StreamUsage? {
        val payload = dataPayload.trim()
        if (payload.isEmpty() || payload.equals("[DONE]", ignoreCase = true)) return null
        return runCatching {
            val root = json.parseToJsonElement(payload).asObjectOrNull() ?: return@runCatching null
            val usage = root["usage"].asObjectOrNull() ?: return@runCatching null
            val input = usage["prompt_tokens"].asLongOrNull() ?: 0L
            val output = usage["completion_tokens"].asLongOrNull() ?: 0L
            if (input <= 0 && output <= 0) null else StreamUsage(input, output)
        }.getOrNull()
    }

    fun parseDataPayload(
        dataPayload: String,
        reasoningFields: Collection<String> = DEFAULT_REASONING_FIELDS,
    ): Delta {
        val payload = dataPayload.trim()
        if (payload.isEmpty()) return Delta()
        if (payload.equals("[DONE]", ignoreCase = true)) {
            return Delta(done = true)
        }

        return runCatching {
            val root = json.parseToJsonElement(payload).asObjectOrNull() ?: return@runCatching Delta()
            val error = root["error"].asObjectOrNull()
            if (error != null) {
                val msg = error.stringField("message")
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "API返回错误"
                return@runCatching Delta(errorMessage = msg, done = true)
            }

            val choice = root["choices"].asArrayOrNull()?.firstOrNull().asObjectOrNull()
            val deltaObj = choice?.get("delta").asObjectOrNull()
            val messageObj = choice?.get("message").asObjectOrNull()
            val content = firstNonBlankString(
                deltaObj?.get("content"),
                messageObj?.get("content"),
            )
            val reasoning = extractReasoning(deltaObj, messageObj, reasoningFields)
            val finishReason = choice?.stringField("finish_reason")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

            Delta(
                content = content,
                reasoning = reasoning,
                finishReason = finishReason,
            )
        }.getOrElse {

            Delta()
        }
    }

    fun extractDataPayload(line: String): String? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:", ignoreCase = true)) return null
        return trimmed.substring(5).trimStart()
    }

    private fun extractReasoning(
        deltaObj: JsonObject?,
        messageObj: JsonObject?,
        reasoningFields: Collection<String>,
    ): String? {
        val fields = linkedSetOf<String>().apply {
            reasoningFields.forEach { if (it.isNotBlank()) add(it.trim()) }
            addAll(DEFAULT_REASONING_FIELDS)
        }
        for (field in fields) {
            firstNonBlankString(
                deltaObj?.get(field),
                messageObj?.get(field),
            )?.let { return it }
        }
        return null
    }

    private fun firstNonBlankString(vararg values: JsonElement?): String? {
        for (value in values) {
            when (value) {
                null, JsonNull -> continue
                is JsonPrimitive -> value.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
                else -> value.toString().trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        this as? JsonObject ?: runCatching { this?.jsonObject }.getOrNull()

    private fun JsonElement?.asArrayOrNull(): JsonArray? =
        this as? JsonArray ?: runCatching { this?.jsonArray }.getOrNull()

    private fun JsonObject.stringField(key: String): String? {
        val el = this[key] ?: return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull
            JsonNull -> null
            else -> null
        }
    }

    private fun JsonElement?.asLongOrNull(): Long? =
        (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    val DEFAULT_REASONING_FIELDS: List<String> = listOf(
        "reasoning_content",
        "reasoning",
        "thinking",
        "thought",
    )
}
