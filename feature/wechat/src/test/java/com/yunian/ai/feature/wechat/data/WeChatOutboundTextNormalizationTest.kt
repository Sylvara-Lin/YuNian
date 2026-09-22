package com.yunian.ai.feature.wechat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatOutboundTextNormalizationTest {
    @Test
    fun normalize_preservesParagraphBoundaries() {
        val normalized = normalizeOutboundText("  第一段  \n\n  第二段  ")

        assertEquals("第一段\n\n第二段", normalized)
    }

    @Test
    fun normalize_collapsesHorizontalWhitespace() {
        assertEquals("你好 世界", normalizeOutboundText("  你好\t  世界  "))
    }
}
