package com.yunian.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable
import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageType

@Stable
sealed interface ChatListItem {
    val stableId: String
    val messageOrNull: ChatMessage?

    data class BodyLoading(val metadata: Message) : ChatListItem {
        override val messageOrNull: ChatMessage? = null
        override val stableId: String = metadata.stableMessageKey()
    }

    data class BodyError(val metadata: Message, val error: String) : ChatListItem {
        override val messageOrNull: ChatMessage? = null
        override val stableId: String = metadata.stableMessageKey()
    }

    data class TextMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("text")
    }

    data class ImageMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("image")
    }

    data class VoiceMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("voice")
    }

    data class VideoMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("video")
    }

    data class FileMessage(val message: ChatMessage) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("file")
    }

    data class StickerMessage(val message: ChatMessage, val stickerName: String) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("sticker")
    }

    data class TimeDivider(val timestamp: Long) : ChatListItem {
        override val messageOrNull: ChatMessage? = null
        override val stableId: String = "time-divider-$timestamp"
    }

    data class SystemTip(
        override val stableId: String,
        val content: String,
        val timestamp: Long? = null
    ) : ChatListItem {
        override val messageOrNull: ChatMessage? = null
    }

    data class ReasoningMessage(
        val message: ChatMessage,
        val durationMs: Long? = message.durationMs,
        val isStreaming: Boolean = message.id < 0L,
    ) : ChatListItem {
        override val messageOrNull: ChatMessage = message
        override val stableId: String = message.stableMessageKey("reasoning")
        val text: String get() = message.content
    }

    /**
     * 一轮生成内的工具调用卡片组（OpenMinis 风格过程可视化）。
     *
     * - 持久态：对应库里一条 [MessageType.TOOL_ACTIVITY] 消息，[message] 非空、
     *   [stableId] 由消息 id 派生，随消息流滚动、可回放历史。
     * - 实时态（[isLive]）：当前轮尚在进行中、还没落库时的临时项，[message] 为 null，
     *   [stableId] 固定为 [LIVE_STABLE_ID]，随活动更新实时刷新。
     */
    data class ToolActivityGroup(
        override val stableId: String,
        val activities: List<ToolActivity>,
        val message: ChatMessage? = null,
        val isLive: Boolean = false,
    ) : ChatListItem {
        override val messageOrNull: ChatMessage? = message

        companion object {
            const val LIVE_STABLE_ID = "tool-activity-live"
        }
    }
}

private const val TIME_DIVIDER_INTERVAL_MILLIS = 5 * 60 * 1000L

internal fun List<ChatMessage>.toChatListItems(
    showReasoning: Boolean = false,
): List<ChatListItem> {
    val items = mutableListOf<ChatListItem>()
    var previousVisibleTimestamp: Long? = null

    for (message in this) {
        if (message.type == MessageType.REASONING) {
            if (!showReasoning) continue
            val projected = ReasoningUiProjector.project(message) ?: continue
            val previousTimestamp = previousVisibleTimestamp
            if (previousTimestamp == null || message.timestamp - previousTimestamp >= TIME_DIVIDER_INTERVAL_MILLIS) {
                items += ChatListItem.TimeDivider(message.timestamp)
            }
            items += projected
            previousVisibleTimestamp = message.timestamp
            continue
        }
        if (message.content.isBlank() && message.type != MessageType.IMAGE) continue

        val previousTimestamp = previousVisibleTimestamp
        if (previousTimestamp == null || message.timestamp - previousTimestamp >= TIME_DIVIDER_INTERVAL_MILLIS) {
            items += ChatListItem.TimeDivider(message.timestamp)
        }

        items += message.toSystemTipOrNull() ?: message.toChatListItem()
        previousVisibleTimestamp = message.timestamp
    }

    return items
}

internal fun toChatListItems(
    metadata: List<Message>,
    bodies: Map<Long, MessageBodyState<ChatMessage>>,
    showReasoning: Boolean = false,
): List<ChatListItem> {
    val items = mutableListOf<ChatListItem>()
    var previousTimestamp: Long? = null

    for (messageMetadata in metadata) {
        if (messageMetadata.type == MessageType.REASONING && !showReasoning) continue
        if (previousTimestamp == null || messageMetadata.timestamp - previousTimestamp >= TIME_DIVIDER_INTERVAL_MILLIS) {
            items += ChatListItem.TimeDivider(messageMetadata.timestamp)
        }
        items += when (val state = bodies[messageMetadata.id]) {
            is MessageBodyState.Ready -> {
                if (state.value.type == MessageType.REASONING) {
                    ReasoningUiProjector.project(state.value)
                        ?: state.value.toChatListItem()
                } else {
                    state.value.toSystemTipOrNull() ?: state.value.toChatListItem()
                }
            }
            is MessageBodyState.Error -> ChatListItem.BodyError(messageMetadata, state.message)
            MessageBodyState.Loading, null -> ChatListItem.BodyLoading(messageMetadata)
        }
        previousTimestamp = messageMetadata.timestamp
    }
    return items
}

private fun ChatMessage.toChatListItem(): ChatListItem {
    // 工具调用卡片必须先于 stickerNameOrNull 判断：其 content 是 JSON 数组（以 [ 开头、] 结尾），
    // 否则会被误判成表情包标签。see ChatListItem.stickerNameOrNull。
    if (type == MessageType.TOOL_ACTIVITY) {
        return ChatListItem.ToolActivityGroup(
            stableId = stableMessageKey("tool-activity"),
            activities = ToolActivityCodec.decode(content),
            message = this,
        )
    }
    val stickerName = stickerNameOrNull()
    return when {
        type == MessageType.REASONING ->
            ReasoningUiProjector.project(this)
                ?: ChatListItem.ReasoningMessage(this, durationMs)
        stickerName != null -> ChatListItem.StickerMessage(this, stickerName)
        type == MessageType.IMAGE -> ChatListItem.ImageMessage(this)

        type == MessageType.VOICE ||
            type == MessageType.AUDIO ||
            content.startsWith("[语音]") ||
            isAssistantVoiceBarMessage() -> ChatListItem.VoiceMessage(this)
        type == MessageType.VIDEO -> ChatListItem.VideoMessage(this)
        type == MessageType.FILE -> ChatListItem.FileMessage(this)
        else -> ChatListItem.TextMessage(this)
    }
}

internal fun ChatMessage.isAssistantVoiceBarMessage(): Boolean {
    if (isFromUser) return false
    if (linkString.isBlank()) return false
    if (content.startsWith("[语音]")) return false
    return type == MessageType.VOICE ||
        type == MessageType.AUDIO ||
        type == MessageType.TEXT
}

private fun ChatMessage.toSystemTipOrNull(): ChatListItem.SystemTip? {
    if (type != MessageType.TEXT) return null
    val tipContent = content.trim()
    if (!tipContent.isSystemTipContent()) return null

    return ChatListItem.SystemTip(
        stableId = stableMessageKey("system-tip"),
        content = tipContent,
        timestamp = timestamp
    )
}

private fun ChatMessage.stableMessageKey(kind: String): String {

    return if (id != 0L) "message-$id" else "$kind-local-$timestamp-${content.hashCode()}"
}

private fun Message.stableMessageKey(): String = "message-$id"

private fun ChatMessage.stickerNameOrNull(): String? {
    val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
    if (!content.startsWith("[") || !content.endsWith("]")) return null
    val name = content.removeSurrounding("[", "]")
    return name.takeIf { it.isNotBlank() && it !in systemTags }
}

private fun String.isSystemTipContent(): Boolean {
    if (isBlank() || length > 80) return false

    return contains("加入群聊") ||
        contains("退出群聊") ||
        contains("移出群聊") ||
        contains("已被撤回") ||
        contains("消息已撤回") ||
        contains("撤回了一条消息")
}
