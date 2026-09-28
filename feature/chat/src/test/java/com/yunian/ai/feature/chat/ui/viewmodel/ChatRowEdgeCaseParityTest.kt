package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 独立补测（QA / 严过关）：针对 T02「结构行」[ChatRow] vs 旧 `toChatListItems(metadata, bodies, showReasoning)`
 * 的**边界**逐项对拍。这些用例是对工程师自带 [ChatRowParityTest]（7 例）的独立补强，覆盖其未触及的角落：
 *
 * 1. 5 分钟阈值「恰好相等 / 差 1ms」两条边界同时断言（并锁定分隔线条数）；
 * 2. reasoning 折叠时**不推进 previousTimestamp**的强区分用例（正确=2 条分隔线，误推进=1 条）；
 * 3. 连续多条 reasoning（展开 / 折叠）；
 * 4. 空 metadata；
 * 5. 正文未就绪 BodyLoading 与 ChatRow.Message 的 stableId 契约（Loading→Ready 不换 key）；
 * 6. TOOL_ACTIVITY 与「表情包标签」的解析优先级（content 形如 `[xxx]` 时不得被误判为 Sticker）；
 * 7. 普通 TEXT 的 `[xxx]` 仍应解析为 Sticker（防止优先级修复过度）。
 *
 * 与自带用例一致：对拍以 `toChatListItems` 为金标准（改造后该函数仍被 `ChatRowRenderer.resolveItem` 复用）。
 */
class ChatRowEdgeCaseParityTest {

    private val interval: Long = 5 * 60 * 1000L

    private fun meta(
        id: Long,
        timestamp: Long,
        type: MessageType = MessageType.TEXT,
    ): Message = Message(
        id = id,
        conversationId = 1L,
        conversationType = "chat",
        isFromUser = type != MessageType.REASONING,
        timestamp = timestamp,
        type = type,
    )

    private fun ready(
        metadata: Message,
        content: String = "正文",
        type: MessageType = metadata.type,
    ): MessageBodyState<ChatMessage> = MessageBodyState.Ready(
        ChatMessage(
            id = metadata.id,
            companionId = 1L,
            content = content,
            isFromUser = metadata.isFromUser,
            timestamp = metadata.timestamp,
            type = type,
        )
    )

    /** 强对拍：行数一致 + 逐位「分隔线 / 正文」类型一致 + 非 0 id 的 stableId 一致 + 无错位。 */
    private fun assertParity(
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
                    assertTrue(
                        "第 $index 项应为分隔线，实际 ${item::class.simpleName}",
                        item is ChatListItem.TimeDivider,
                    )
                    assertEquals(row.timestamp, (item as ChatListItem.TimeDivider).timestamp)
                }

                is ChatRow.Message -> {
                    assertFalse(
                        "第 $index 项应为正文项，实际是分隔线（分隔线错位）",
                        item is ChatListItem.TimeDivider,
                    )
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
    fun dividerBoundary_exactlyEqual_adds_justUnder_doesNot() {
        // 注意：previousTimestamp **逐条消息推进**（而非「上一个分隔线」），所以边界的差
        // 是「相邻两条消息的时间戳之差」。据此构造：5min 恰好相等 → 加；5min-1ms → 不加。
        val t0 = 1_000_000_000_000L
        val metadata = listOf(
            meta(1L, t0),                                          // 首条 → 分隔线
            meta(2L, t0 + interval),                               // 差 = 5min（恰好相等）→ 加（>=）
            meta(3L, t0 + interval + (interval - 1L)),             // 差 = 5min - 1ms → 不加
            meta(4L, t0 + interval + (interval - 1L) + interval),  // 差 = 5min → 加（>=）
        )
        val bodies = metadata.associate { it.id to ready(it) }
        assertParity(metadata, bodies, showReasoning = true)

        val dividers = buildChatRows(metadata, true).count { it is ChatRow.TimeDivider }
        // 1(首) + 1(恰好 5min) + 1(恰好 5min) = 3；若把 >= 误改为 > 则只剩 1，会被本用例抓住。
        assertEquals("5min 阈值应使用 >=：恰好相等要加、差 1ms 不加", 3, dividers)
    }

    @Test
    fun reasoningCollapsed_betweenTexts_doesNotAdvanceTimestamp_strongDistinguish() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(
            meta(1L, t0, MessageType.TEXT),
            meta(-9L, t0 + 4 * 60 * 1000L, MessageType.REASONING), // 折叠
            meta(2L, t0 + 6 * 60 * 1000L, MessageType.TEXT),
        )
        val bodies = metadata.associate { it.id to ready(it) }
        assertParity(metadata, bodies, showReasoning = false)

        // 若折叠分支误把 prev 推进到 reasoning 的时间戳，则第二条文本差仅 2min → 只会剩 1 条分隔线。
        val dividers = buildChatRows(metadata, false).count { it is ChatRow.TimeDivider }
        assertEquals("折叠 reasoning 不得推进 previousTimestamp（否则分隔线被吞）", 2, dividers)

        val rows = buildChatRows(metadata, false)
        assertTrue(rows.none { it is ChatRow.Message && it.metadata.type == MessageType.REASONING })
        assertEquals(2, rows.count { it is ChatRow.Message })
    }

    @Test
    fun multipleConsecutiveReasoning_shown_matchesOldImpl() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(
            meta(-9L, t0, MessageType.REASONING),
            meta(-10L, t0 + 60_000L, MessageType.REASONING),
            meta(-11L, t0 + 120_000L, MessageType.REASONING),
            meta(1L, t0 + 180_000L, MessageType.TEXT),
        )
        val bodies = metadata.associate { it.id to ready(it) }
        assertParity(metadata, bodies, showReasoning = true)

        val rows = buildChatRows(metadata, true)
        assertEquals(4, rows.count { it is ChatRow.Message })
        assertEquals(1, rows.count { it is ChatRow.TimeDivider })
    }

    @Test
    fun multipleConsecutiveReasoning_collapsed_matchesOldImpl() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(
            meta(-9L, t0, MessageType.REASONING),
            meta(-10L, t0 + 60_000L, MessageType.REASONING),
            meta(1L, t0 + 120_000L, MessageType.TEXT),
        )
        val bodies = metadata.associate { it.id to ready(it) }
        assertParity(metadata, bodies, showReasoning = false)

        val rows = buildChatRows(metadata, false)
        assertEquals(1, rows.count { it is ChatRow.Message })
        assertEquals(1, rows.count { it is ChatRow.TimeDivider })
    }

    @Test
    fun emptyMetadata_producesEmptyRowsAndMatchesOldImpl() {
        assertTrue(buildChatRows(emptyList(), showReasoning = true).isEmpty())
        assertTrue(buildChatRows(emptyList(), showReasoning = false).isEmpty())
        assertParity(emptyList(), emptyMap(), showReasoning = true)
        assertParity(emptyList(), emptyMap(), showReasoning = false)
    }

    @Test
    fun bodyLoading_sharesMessageStableIdWithReady_loadingToReadyNoKeyChange() {
        val t0 = 1_000_000_000_000L
        val metadata = listOf(meta(5L, t0))

        // 未就绪：旧实现产出 BodyLoading，stableId = "message-5"。
        val loading = toChatListItems(metadata, emptyMap(), true).last()
        assertTrue(loading is ChatListItem.BodyLoading)
        assertEquals("message-5", loading.stableId)
        assertEquals("message-5", buildChatRows(metadata, true).filterIsInstance<ChatRow.Message>().single().stableId)

        // 就绪：stableId 必须不变（Loading → Ready 不换 key、不闪跳）。
        val readyItems = toChatListItems(metadata, metadata.associate { it.id to ready(it) }, true)
        assertEquals("message-5", readyItems.last().stableId)
        assertNotEquals(loading::class, readyItems.last()::class)
    }

    @Test
    fun toolActivity_contentThatLooksLikeSticker_resolvesToToolGroup_notSticker() {
        val t0 = 1_000_000_000_000L
        // content 形如 [xxx]，天然是「表情包标签」的形态；但 type=TOOL_ACTIVITY 必须优先。
        val m = meta(7L, t0, MessageType.TOOL_ACTIVITY)
        val bodies = mapOf(m.id to ready(m, content = "[微笑]"))
        val resolved = toChatListItems(listOf(m), bodies, showReasoning = true).last()
        assertTrue(
            "TOOL_ACTIVITY 即使 content 形如 [xxx] 也必须解析为卡片，而非表情包",
            resolved is ChatListItem.ToolActivityGroup,
        )
    }

    @Test
    fun textBracketContent_stillResolvesToSticker() {
        val t0 = 1_000_000_000_000L
        val m = meta(7L, t0, MessageType.TEXT)
        val bodies = mapOf(m.id to ready(m, content = "[微笑]"))
        val resolved = toChatListItems(listOf(m), bodies, showReasoning = true).last()
        assertTrue(resolved is ChatListItem.StickerMessage)
    }
}
