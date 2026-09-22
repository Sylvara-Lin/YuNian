package com.yunian.ai.feature.memory

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object MemoryRecallTools {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun registerAll(memoryProvider: MemoryProvider) {
        ToolRegistry.register(RecallMemoryTool(memoryProvider))
    }

    private class RecallMemoryTool(private val memoryProvider: MemoryProvider) : AiTool {
        override val name = "recall_memory"
        override val description = "当需要了解用户偏好、关系设定、过往事件或长期上下文时，按查询词主动召回相关记忆。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"query":{"type":"string","description":"要召回的记忆主题或当前问题关键词"},"limit":{"type":"integer","description":"最多返回的记忆数量，建议 3-8"}},"required":["query"]}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            val query = obj?.get("query")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val limit = obj?.get("limit")?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 5
            val companionId = obj?.get("companionId")?.jsonPrimitive?.longOrNull
            val groupId = obj?.get("groupId")?.jsonPrimitive?.longOrNull

            if (query.isBlank()) {
                return buildJsonObject {
                    put("ok", false)
                    put("error", "query 不能为空")
                }.toString()
            }

            val memoryContext = memoryProvider.recallMemory(companionId, groupId, query, limit)
            return buildJsonObject {
                put("ok", true)
                put("query", query)
                put("empty", memoryContext.isBlank())
                put("memoryContext", memoryContext)
            }.toString()
        }
    }
}
