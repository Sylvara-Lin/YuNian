package com.yunian.ai.feature.wechat.data

import com.yunian.ai.feature.wechat.data.model.M0
import com.yunian.ai.feature.wechat.data.model.M1
import com.yunian.ai.feature.wechat.data.model.M2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeChatInboundTextExtractionTest {

    @Test
    fun multipleTextItems_preservesAllNonBlankTextInOrder() {
        val message = M0(
            fromUserId = "wx_user",
            toUserId = "bot",
            itemList = listOf(
                M1(type = 1, textItem = M2(text = "第一段")),
                M1(type = 1, textItem = M2(text = "  ")),
                M1(type = 1, textItem = M2(text = "第二段")),
                M1(type = 1, textItem = M2(text = "第三段")),
            ),
        )

        assertEquals("第一段\n第二段\n第三段", extractInboundText(message))
    }

    @Test
    fun noMeaningfulText_returnsNull() {
        val message = M0(
            fromUserId = "wx_user",
            toUserId = "bot",
            itemList = listOf(M1(type = 1, textItem = M2(text = "  "))),
        )

        assertNull(extractInboundText(message))
    }
}