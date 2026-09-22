package com.yunian.ai.wechat

import com.yunian.ai.wechat.map.WeChatContentCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatContentCleanerTest {

    @Test
    fun isStickerContent_matchesBracketOnly() {
        assertTrue(WeChatContentCleaner.isStickerContent("[开心]"))
        assertFalse(WeChatContentCleaner.isStickerContent("你好[开心]"))
    }

    @Test
    fun clean_stripsThinkAndRolePrefix() {
        val raw = "[角色1] <think>secret</think>你好呀。"
        val cleaned = WeChatContentCleaner.clean(raw)
        assertEquals("你好呀。", cleaned)
    }

    @Test
    fun clean_stripsUnclosedThink() {
        val raw = "你好<think>未闭合思考一直到结尾"
        val cleaned = WeChatContentCleaner.clean(raw)
        assertEquals("你好", cleaned)
        assertFalse(cleaned.contains("未闭合"))
    }

    @Test
    fun clean_dedupesRepeatedSentence() {
        val raw = "打算晚上吃什么呀。打算晚上吃什么呀。"
        val cleaned = WeChatContentCleaner.clean(raw)
        assertEquals("打算晚上吃什么呀。", cleaned)
    }
}
