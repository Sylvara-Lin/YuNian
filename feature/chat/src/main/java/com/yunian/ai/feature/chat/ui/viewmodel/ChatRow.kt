package com.yunian.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.yunian.ai.database.model.MessageType

/**
 * 聊天列表「结构行」模型。
 *
 * 设计意图：把「列表结构」与「行内正文内容」解耦。
 *
 * 旧实现中 [com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem] 把正文（`ChatMessage`）
 * 直接嵌进列表项，导致**任何一条正文变化（流式 delta）都必须重建整张表**（O(n)，
 * 每次约 50ms 一次）。本模型下，列表只依赖 `messageMetadata` + `showReasoning`；
 * 正文由行内读取 `messageBodies[row.metadata.id]` 得到，从而让流式 delta
 * **不再触发整表重建**（见 [buildChatRows]）。
 *
 * 契约（务必遵守）：
 * - [ChatRow.Message.stableId] 恒为 `"message-${metadata.id}"`，与旧 `ChatListItem`
 *   的 stableId 一致 —— 保证 `Loading → Ready` 不换 key、不闪跳。
 * - 同一 key 的 [contentType] 恒定（由 `metadata.type` 派生），满足 LazyColumn 复用池契约。
 */
@Immutable
sealed interface ChatRow {
    /** 列表稳定标识（等价于旧 [com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem.stableId]）。 */
    val stableId: String

    /** 供 LazyColumn 按类型复用 composition 槽位的内容类型。 */
    val contentType: String

    /**
     * 消息行：结构只持有 [metadata]，**不含正文**。正文由渲染期行内读取 `messageBodies[id]`。
     */
    @Immutable
    data class Message(
        val metadata: com.yunian.ai.database.model.Message,
    ) : ChatRow {
        override val stableId: String = "message-${metadata.id}"
        override val contentType: String = metadata.type.toContentType()
    }

    /** 时间分隔线行。 */
    @Immutable
    data class TimeDivider(val timestamp: Long) : ChatRow {
        override val stableId: String = "time-divider-$timestamp"
        override val contentType: String = "divider"
    }

    /** 当前轮进行中的工具调用卡片组（实时态）。 */
    @Immutable
    data class LiveToolGroup(val activities: List<ToolActivity>) : ChatRow {
        override val stableId: String = "tool-activity-live"
        override val contentType: String = "tool-live"
    }
}

/** 时间分隔线阈值：5 分钟（与 [ChatListItem] 结构实现保持一致）。 */
private const val CHAT_ROW_TIME_DIVIDER_INTERVAL_MILLIS: Long = 5 * 60 * 1000L

/**
 * 依据 `messageMetadata` 与 [showReasoning] 构建结构行列表。
 *
 * **逐行复刻** `toChatListItems(metadata, bodies, showReasoning)` 的结构语义：
 * - `REASONING && !showReasoning` 时 `continue`（**不产生分隔线、不推进 previousTimestamp**）；
 * - 时间分隔线阈值 [CHAT_ROW_TIME_DIVIDER_INTERVAL_MILLIS]（5 分钟）；
 * - 每条未被跳过的 metadata 恰好产出「1 条（可选）分隔线 + 1 条消息行」，与旧实现行数一致。
 *
 * 说明：与旧实现一致，metadata 版本**不做** `content.isBlank()` 过滤（该过滤只存在于
 * `List<ChatMessage>` 版本），以免行数与旧实现错位。
 */
fun buildChatRows(
    metadata: List<com.yunian.ai.database.model.Message>,
    showReasoning: Boolean,
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var previousTimestamp: Long? = null

    for (messageMetadata in metadata) {
        if (messageMetadata.type == MessageType.REASONING && !showReasoning) continue

        val previous = previousTimestamp
        if (previous == null || messageMetadata.timestamp - previous >= CHAT_ROW_TIME_DIVIDER_INTERVAL_MILLIS) {
            rows += ChatRow.TimeDivider(messageMetadata.timestamp)
        }

        rows += ChatRow.Message(messageMetadata)
        previousTimestamp = messageMetadata.timestamp
    }

    return rows
}

/**
 * 消息类型 → LazyColumn 内容类型。
 *
 * 粒度仅用于复用池划分（命中率优化），不影响正确性；同一 key 的 `metadata.type` 恒定，
 * 故同一 key 的 contentType 恒定。
 */
fun MessageType.toContentType(): String = when (this) {
    MessageType.TEXT -> "text"
    MessageType.IMAGE -> "image"
    MessageType.AUDIO -> "audio"
    MessageType.VIDEO -> "video"
    MessageType.VOICE -> "voice"
    MessageType.FILE -> "file"
    MessageType.REASONING -> "reasoning"
    MessageType.TOOL_ACTIVITY -> "tool"
}
