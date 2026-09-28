package com.yunian.ai.feature.chat.ui.viewmodel

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [ToolActivity] 列表与 JSON 的编解码器。
 *
 * 用于把一轮生成内的工具调用活动持久化为 [com.yunian.ai.database.model.MessageType.TOOL_ACTIVITY]
 * 消息的 content（一个 JSON 数组），从而让过程卡片成为消息流的一员、随列表滚动并可持久回放。
 * 使用 kotlinx.serialization，字段至少含 id/toolName/argsSummary/status/resultSummary/startedAtMs。
 */
internal object ToolActivityCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val serializer = ListSerializer(ToolActivity.serializer())

    /** 编码为 JSON 数组；失败时回退空数组（不抛异常，避免影响消息落库）。 */
    fun encode(activities: List<ToolActivity>): String =
        runCatching { json.encodeToString(serializer, activities) }.getOrDefault("[]")

    /** 解码消息 content；失败或为空时返回空列表（渲染侧会安全跳过）。 */
    fun decode(content: String): List<ToolActivity> {
        if (content.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, content) }.getOrDefault(emptyList())
    }
}
