package com.yunian.ai.wechat

import com.yunian.ai.domain.wechat.AppContentType
import com.yunian.ai.domain.wechat.WeChatAppTypeAlignment
import com.yunian.ai.domain.wechat.WeChatContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatAppTypeAlignmentTest {

    @Test
    fun wireTypes_matchIlinkM1Type() {
        assertEquals(1, WeChatContentKind.TEXT.wireType)
        assertEquals(2, WeChatContentKind.IMAGE.wireType)
        assertEquals(3, WeChatContentKind.VOICE.wireType)
        assertEquals(4, WeChatContentKind.FILE.wireType)
        assertEquals(5, WeChatContentKind.VIDEO.wireType)
        assertEquals(WeChatContentKind.TEXT, WeChatContentKind.fromWireType(1))
        assertEquals(WeChatContentKind.UNKNOWN, WeChatContentKind.fromWireType(99))
    }

    @Test
    fun appWireNames_matchDatabaseMessageType() {
        assertEquals("text", AppContentType.TEXT.wireName)
        assertEquals("image", AppContentType.IMAGE.wireName)
        assertEquals("audio", AppContentType.AUDIO.wireName)
        assertEquals("video", AppContentType.VIDEO.wireName)
        assertEquals("voice", AppContentType.VOICE.wireName)
        assertEquals("file", AppContentType.FILE.wireName)
        assertEquals("reasoning", AppContentType.REASONING.wireName)
        assertEquals(AppContentType.REASONING, AppContentType.fromWireName("REASONING"))
    }

    @Test
    fun bidirectional_textImageFileVideo() {
        assertEquals(AppContentType.TEXT, WeChatAppTypeAlignment.toAppContentType(WeChatContentKind.TEXT))
        assertEquals(AppContentType.IMAGE, WeChatAppTypeAlignment.toAppContentType(WeChatContentKind.IMAGE))
        assertEquals(AppContentType.VOICE, WeChatAppTypeAlignment.toAppContentType(WeChatContentKind.VOICE))
        assertEquals(AppContentType.FILE, WeChatAppTypeAlignment.toAppContentType(WeChatContentKind.FILE))
        assertEquals(AppContentType.VIDEO, WeChatAppTypeAlignment.toAppContentType(WeChatContentKind.VIDEO))
        assertNull(WeChatAppTypeAlignment.toAppContentType(WeChatContentKind.UNKNOWN))

        assertEquals(WeChatContentKind.TEXT, WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.TEXT))
        assertEquals(WeChatContentKind.IMAGE, WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.IMAGE))
        assertEquals(WeChatContentKind.VOICE, WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.VOICE))
        assertEquals(WeChatContentKind.VOICE, WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.AUDIO))
        assertEquals(WeChatContentKind.FILE, WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.FILE))
        assertEquals(WeChatContentKind.VIDEO, WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.VIDEO))
    }

    @Test
    fun reasoning_neverSyncsToWeChat() {
        assertNull(WeChatAppTypeAlignment.toWeChatContentKind(AppContentType.REASONING))
        assertFalse(WeChatAppTypeAlignment.isSyncableToWeChat(AppContentType.REASONING))
        assertFalse(WeChatAppTypeAlignment.isSyncableToWeChat("reasoning"))
        assertNull(WeChatAppTypeAlignment.toWeChatContentKindFromAppName("reasoning"))
    }

    @Test
    fun aiDialogue_onlyTextAndImage() {
        assertTrue(WeChatAppTypeAlignment.isSupportedByAiDialogue(WeChatContentKind.TEXT))
        assertTrue(WeChatAppTypeAlignment.isSupportedByAiDialogue(WeChatContentKind.IMAGE))
        assertFalse(WeChatAppTypeAlignment.isSupportedByAiDialogue(WeChatContentKind.VOICE))
        assertFalse(WeChatAppTypeAlignment.isSupportedByAiDialogue(WeChatContentKind.FILE))
        assertFalse(WeChatAppTypeAlignment.isSupportedByAiDialogue(WeChatContentKind.VIDEO))
        assertEquals("text", WeChatAppTypeAlignment.toAiMessageTypeName(WeChatContentKind.TEXT))
        assertEquals("image", WeChatAppTypeAlignment.toAiMessageTypeName(WeChatContentKind.IMAGE))
        assertNull(WeChatAppTypeAlignment.toAiMessageTypeName(WeChatContentKind.VOICE))
        assertNull(WeChatAppTypeAlignment.toAiMessageTypeName(AppContentType.REASONING))
    }
}
