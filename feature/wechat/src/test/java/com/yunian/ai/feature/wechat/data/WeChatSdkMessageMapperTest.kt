package com.yunian.ai.feature.wechat.data

import com.yunian.ai.wechat.wire.WireMessageItem
import com.yunian.ai.wechat.wire.WireTextItem
import com.yunian.ai.wechat.wire.WireWeChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatSdkMessageMapperTest {

    @Test
    fun mapsWireTextMessageToAppMessageModel() {
        val wireMessage = WireWeChatMessage(
            messageId = 42L,
            messageType = 1,
            fromUserId = "user@im.wechat",
            toUserId = "bot@im.bot",
            createTimeMs = 123456789L,
            contextToken = "context-token",
            itemList = listOf(
                WireMessageItem(type = 1, textItem = WireTextItem("hello from sdk")),
            ),
        )

        val message = WeChatSdkMessageMapper.toAppMessage(wireMessage)

        assertEquals(42L, message.messageId)
        assertEquals(1, message.messageType)
        assertEquals("user@im.wechat", message.fromUserId)
        assertEquals("bot@im.bot", message.toUserId)
        assertEquals(123456789L, message.createTimeMs)
        assertEquals("context-token", message.contextToken)
        assertEquals("hello from sdk", message.itemList?.single()?.textItem?.text)
    }
}
