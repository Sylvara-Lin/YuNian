package com.yunian.ai.database.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageType {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
    @SerialName("voice") VOICE,
    @SerialName("file") FILE,

    @SerialName("reasoning") REASONING,

    /**
     * 工具调用过程卡片（OpenMinis 风格）：一轮生成内 AI 调用工具的记录，
     * content 存活动列表 JSON。仅用于聊天消息流渲染，不参与 AI 上下文 / 会话摘要。
     *
     * 注意：Room 侧转换器 [com.yunian.ai.database.AppDatabase.Converters.fromMessageType]
     * 以枚举 name 存字符串，[com.yunian.ai.database.AppDatabase.Converters.toMessageType]
     * 有 runCatching 兜底，故新增枚举值不涉及数据库 schema 变更 / 迁移。
     */
    @SerialName("tool_activity") TOOL_ACTIVITY,
}
