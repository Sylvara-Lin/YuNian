package com.yunian.ai.wechat

import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatMediaRef
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.wechat.map.WeChatOutboundSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatOutboundSegmenterTest {

    @Test
    fun splitSimple_paragraphsPreferred() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("第一段\n\n第二段")
        assertEquals(listOf("第一段", "第二段"), parts)
    }

    @Test
    fun splitSimple_completeSentencesBecomeSeparateBubbles() {

        val parts = WeChatOutboundSegmenter.splitTextSimple("你好。世界！")
        assertEquals(listOf("你好。", "世界！"), parts)
    }

    @Test
    fun splitSimple_blankLineParagraphsStaySplit() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("嗯。\n\n咋了，加班了？")
        assertEquals(listOf("嗯。", "咋了，加班了？"), parts)
    }

    @Test
    fun splitSimple_shortAffirmationsStayIndependent() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("嗯。好。")
        assertEquals(listOf("嗯。", "好。"), parts)
    }

    @Test
    fun splitSimple_complexShortSentenceStaysIndependent() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("行。那你先忙。")
        assertEquals(listOf("行。", "那你先忙。"), parts)
    }

    @Test
    fun splitSimple_behaviorShiftRespectsSoftSegmentCap() {

        val text = "你今天看起来有点累。要不先休息一下吧。我有点担心你。晚安。"
        val parts = WeChatOutboundSegmenter.splitTextSimple(text)
        assertTrue(parts.size in 2..3)
        assertTrue(parts.none { it.isBlank() })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun splitSimple_singleChunk() {
        val parts = WeChatOutboundSegmenter.splitTextSimple("一整句没有标点")
        assertEquals(listOf("一整句没有标点"), parts)
    }

    @Test
    fun expand_textProducesIndexedSegments() {

        val request = WeChatOutboundRequest(
            companionId = 1L,
            text = "嗯。好。",
            contextToken = "c",
        )
        val segments = WeChatOutboundSegmenter.expand(
            request = request,
            wechatUserId = "wx1",
            rootId = "root",
        )
        assertEquals(2, segments.size)
        assertEquals(0, segments[0].segmentIndex)
        assertEquals(1, segments[1].segmentIndex)
        assertEquals(2, segments[0].segmentCount)
        assertEquals("root#0", segments[0].outboxId)
        assertEquals("root#1", segments[1].outboxId)
        assertEquals(WeChatContentKind.TEXT, segments[0].kind)
        assertEquals("c", segments[0].contextToken)
        assertEquals("wx1", segments[0].wechatUserId)
    }

    @Test
    fun expand_mediaIsSingleSegment() {
        val request = WeChatOutboundRequest(
            companionId = 1L,
            text = "caption",
            media = WeChatMediaRef(kind = WeChatContentKind.IMAGE, localPath = "/tmp/a.jpg"),
        )
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId = "wx2", rootId = "m")
        assertEquals(1, segments.size)
        assertEquals(WeChatContentKind.IMAGE, segments.single().kind)
        assertEquals("/tmp/a.jpg", segments.single().media?.localPath)
        assertEquals("caption", segments.single().text)
    }

    @Test
    fun expand_blankText_empty() {
        val request = WeChatOutboundRequest(companionId = 1L, text = "   ")
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId = "wx3", rootId = "e")
        assertTrue(segments.isEmpty())
    }
}
