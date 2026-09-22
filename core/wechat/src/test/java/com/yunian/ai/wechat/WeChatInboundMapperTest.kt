package com.yunian.ai.wechat

import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.wechat.map.WeChatInboundMapper
import com.yunian.ai.wechat.map.WeChatLegacyM0Bridge
import com.yunian.ai.wechat.wire.WireCdnMedia
import com.yunian.ai.wechat.wire.WireImageItem
import com.yunian.ai.wechat.wire.WireMessageItem
import com.yunian.ai.wechat.wire.WireTextItem
import com.yunian.ai.wechat.wire.WireWeChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatInboundMapperTest {

    @Test
    fun textMessage_mapsPrimaryTextAndDedupeByMessageId() {
        val wire = WireWeChatMessage(
            seq = 10L,
            messageId = 42L,
            fromUserId = "wx_user_1",
            createTimeMs = 1_700_000_000_000L,
            messageType = 1,
            itemList = listOf(
                WireMessageItem(type = 1, textItem = WireTextItem(text = "你好")),
            ),
            contextToken = "ctx-1",
        )
        val domain = WeChatInboundMapper.toDomain(wire)
        assertNotNull(domain)
        assertEquals("mid:42", domain!!.dedupeKey)
        assertEquals("wx_user_1", domain.fromUserId)
        assertEquals("你好", domain.primaryText)
        assertEquals(WeChatContentKind.TEXT, domain.primaryKind)
        assertEquals("ctx-1", domain.contextToken)
        assertTrue(domain.isAiDialogueCandidate)
    }

    @Test
    fun missingFromUserId_returnsNull() {
        val wire = WireWeChatMessage(
            messageId = 1L,
            itemList = listOf(WireMessageItem(type = 1, textItem = WireTextItem(text = "x"))),
        )
        assertNull(WeChatInboundMapper.toDomain(wire))
    }

    @Test
    fun imageMessage_mapsCdnAndAiCandidate() {
        val wire = WireWeChatMessage(
            messageId = 7L,
            fromUserId = "u2",
            itemList = listOf(
                WireMessageItem(
                    type = 2,
                    imageItem = WireImageItem(
                        cdnImg = WireCdnMedia(
                            encryptQueryParam = "q",
                            aesKey = "k",
                        ),
                    ),
                ),
            ),
        )
        val domain = WeChatInboundMapper.toDomain(wire)!!
        assertEquals(WeChatContentKind.IMAGE, domain.primaryKind)
        assertTrue(domain.hasImage)
        assertTrue(domain.isAiDialogueCandidate)
        assertEquals("q", domain.parts.single().media?.cdn?.encryptQueryParam)
        assertEquals("k", domain.parts.single().media?.cdn?.aesKey)
    }

    @Test
    fun noMessageId_usesStableHashDedupe() {
        val wire = WireWeChatMessage(
            fromUserId = "u3",
            createTimeMs = 100L,
            itemList = listOf(
                WireMessageItem(type = 1, textItem = WireTextItem(text = "same")),
            ),
        )
        val a = WeChatInboundMapper.toDomain(wire)!!
        val b = WeChatInboundMapper.toDomain(wire)!!
        assertTrue(a.dedupeKey.startsWith("h:"))
        assertEquals(a.dedupeKey, b.dedupeKey)
    }

    @Test
    fun legacyM0Bridge_matchesWireShape() {
        val wire = WeChatLegacyM0Bridge.fromFields(
            seq = 1L,
            messageId = 99L,
            fromUserId = "legacy",
            createTimeMs = 50L,
            messageType = 1,
            contextToken = "t",
            items = listOf(
                WeChatLegacyM0Bridge.LegacyItem(type = 1, text = "from M0"),
            ),
        )
        val domain = WeChatInboundMapper.toDomain(wire)!!
        assertEquals("mid:99", domain.dedupeKey)
        assertEquals("from M0", domain.primaryText)
        assertEquals("legacy", domain.fromUserId)
    }
}
