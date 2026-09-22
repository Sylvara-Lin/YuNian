package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildChatRows` 与旧 `toChatListItems(metadata, bodies, showReasoning)` 的逐项对拍。
 *
 * 目的：T02 用「结构行」[ChatRow] 替换了直接嵌入正文的 [ChatListItem] 列表，
 * 列表结构语义（时间分隔线阈值 / reasoning 折叠 / tool activity / stableId 契约）
 * 必须与旧实现**逐项一致**，否则会出现分隔线错位、行数错位或 Loading→Ready 闪跳。
 *
 * 覆盖：5min 分隔线边界、reasoning 折叠（不产生分隔线、不推进 previousTimestamp）、
 * reasoning 展开、tool activity、正文未就绪（BodyLoading）、local id=0 的 stableId 契约。
 */
class ChatRowParityTest {

    private val interval: Long = 5 * 60 * 1000L

    private fun meta(
        id: Long,
        timestamp: Long,
        type: MessageType = MessageType.TEXT,
    ): Message = Message(
        id = id,
        conversationId = 1L,
        conversationType = "chat",
        isFromUser = false,
        timestamp = timestamp,
        type = type,
    )

    private fun readyBody(
        metadata: Message,
        content: String = "正文",
    ): MessageBodyState<ChatMessage> = MessageBodyState.Ready(
        ChatMessage(
            id = metadata.id,
            companionId = 1L,
            content = content,
            isFromUser = false,
            timestamp = metadata.timestamp,
            type = metadata.type,
        )
    )

    /** 逐位对拍：行数与旧实现一致；分隔线时间戳一致；非 0 正/负 id 的正文项 stableId 一致。 */
    private fun assertStructuralParity(
        metadata: List<Message>,
        bodies: Map<Long, MessageBodyState<ChatMessage>>,
        showReasoning: Boolean,
    ) {
        val rows = buildChatRows(metadata, showReasoning)
        val items = toChatListItems(metadata, bodies, showReasoning)

        assertEquals("行数与旧实现不一致", items.size, rows.size)

        rows.forEachIndexed { index, row ->
            val item = items[index]
            when (row) {
                is ChatRow.TimeDivider -> {
                    assertTrue("第 $index 项应为时间分隔线", item is ChatListItem.TimeDivider)
                    assertEquals(row.timestamp, (item as ChatListItem.TimeDivider).timestamp)
                }

                is ChatRow.Message -> {
                    // id != 0 时旧实现的 stableId 恒为 "message-$id"（与 kind 无关），须逐位相等。
                    if (row.metadata.id != 0L) {
                        assertEquals(
                            "第 $index 项 stableId 与旧实现不一致",
                            item.stableId,
                            row.stableId,
                        )
                    }
                }

                is ChatRow.LiveToolGroup -> error("buildChatRows 不应产出 LiveToolGroup")
            }
        }
    }

    @Test
    fun fiveMinuteDividerBoundaries_matchOldImpl() {
        val t0 = 1_000_000_000_000L
        // previousTimestamp 逐条消息推进（而非「上一个分隔线」），阈值用 >=。
        val metadata = listOf(
            meta(1L, t0),                              // previous == null → 分隔线
            meta(2L, t0 + interval),                   // 差 == 5min → 分隔线
            meta(3L, t0 + interval + interval - 1),    // 差 == 5min-1 → 不加
            meta(4L, t0 + interval + interval - 1 + interval), // 差 == 5min → 加
        )
        val bodies = metadata.associate { it.id to readyBody(it) }
        assertStructuralParity(metadata, bodies, showReasoning = true)

        val rows = buildChatRows(metadata, showReasoning = true)
        assertEquals(3, rows.count { it is ChatRow.TimeDivider })
    }

    @Test
    fun reasoningCollapsed_skipsRowAndDoesNotAdvanceTimestamp() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(
            meta(1L, t0, MessageType.TEXT),
            meta(-9L, t0 + 1000L, MessageType.REASONING), // 折叠：跳过，且不推进 previousTimestamp
            meta(2L, t0 + interval - 1, MessageType.TEXT), // 相对首条仍 < 5min → 不加分隔线
        )
        val bodies = metadata.associate { it.id to readyBody(it) }
        assertStructuralParity(metadata, bodies, showReasoning = false)

        val rows = buildChatRows(metadata, showReasoning = false)
        assertEquals(1, rows.count { it is ChatRow.TimeDivider })
        assertTrue(rows.none { it is ChatRow.Message && it.metadata.type == MessageType.REASONING })
    }

    @Test
    fun reasoningShown_producesRowAndDividers_matchOldImpl() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(
            meta(-9L, t0, MessageType.REASONING),
            meta(1L, t0 + interval, MessageType.TEXT),
        )
        val bodies = metadata.associate { it.id to readyBody(it) }
        assertStructuralParity(metadata, bodies, showReasoning = true)

        val rows = buildChatRows(metadata, showReasoning = true)
        assertEquals(2, rows.count { it is ChatRow.TimeDivider })
    }

    @Test
    fun toolActivityRow_contentTypeAndResolvedItem() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(meta(7L, t0, MessageType.TOOL_ACTIVITY))
        val bodies = metadata.associate {
            it.id to MessageBodyState.Ready(
                ChatMessage(
                    id = it.id,
                    companionId = 1L,
                    content = "[]",
                    isFromUser = false,
                    timestamp = it.timestamp,
                    type = MessageType.TOOL_ACTIVITY,
                )
            )
        }
        assertStructuralParity(metadata, bodies, showReasoning = true)

        val messageRow = buildChatRows(metadata, showReasoning = true)
            .filterIsInstance<ChatRow.Message>()
            .single()
        assertEquals("tool", messageRow.contentType)
        assertTrue(toChatListItems(metadata, bodies, showReasoning = true).last() is ChatListItem.ToolActivityGroup)
    }

    @Test
    fun loadingBody_resolvesToBodyLoading_andSharesMessageStableId() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(meta(5L, t0))
        val bodies = emptyMap<Long, MessageBodyState<ChatMessage>>()
        assertStructuralParity(metadata, bodies, showReasoning = true)

        val lastRow = buildChatRows(metadata, showReasoning = true).last()
        assertTrue(lastRow is ChatRow.Message)
        assertEquals("message-5", (lastRow as ChatRow.Message).stableId)
    }

    @Test
    fun localIdZero_rowStableIdIsMessageZero() {
        val t0 = 1_000_000_000_000L
        val rows = buildChatRows(listOf(meta(0L, t0)), showReasoning = true)
        assertEquals("message-0", rows.filterIsInstance<ChatRow.Message>().single().stableId)
    }

    @Test
    fun contentType_isDerivedFromMessageType() {
        assertEquals("text", MessageType.TEXT.toContentType())
        assertEquals("image", MessageType.IMAGE.toContentType())
        assertEquals("audio", MessageType.AUDIO.toContentType())
        assertEquals("video", MessageType.VIDEO.toContentType())
        assertEquals("voice", MessageType.VOICE.toContentType())
        assertEquals("file", MessageType.FILE.toContentType())
        assertEquals("reasoning", MessageType.REASONING.toContentType())
        assertEquals("tool", MessageType.TOOL_ACTIVITY.toContentType())
    }
}
