package com.yunian.ai.feature.wechat.data

import com.yunian.ai.feature.wechat.data.model.M0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回声判定纯函数的回归测试。
 *
 * 关键回归断言：from == ilink_user_id 必须 NOT 被判为回声。
 * ilink_user_id 是扫码的微信用户（对话对端）的 ID，用户在微信里给 bot 发消息时
 * getUpdates 的 from_user_id 就等于它——这是正常入站消息，误判会导致 AI 收不到
 * 任何用户消息（P0 bug 回归）。
 */
class WeChatOutboundEchoTest {

    private fun account(
        ilinkBotId: String = "bot_id_abc",
        ilinkUserId: String = "user_id_xyz",
    ): A0 = A0(
        botToken = "token",
        ilinkBotId = ilinkBotId,
        ilinkUserId = ilinkUserId,
    )

    private fun message(fromUserId: String?): M0 = M0(fromUserId = fromUserId)

    @Test
    fun fromEqualsBotId_isEcho() {
        val msg = message("bot_id_abc")
        assertTrue(isOutboundEcho(account(), msg))
    }

    @Test
    fun fromEqualsIlUserId_isNormalInbound_notEcho() {
        // 回归关键断言：ilink_user_id 是对话对端（扫码的微信用户），不是自己
        val msg = message("user_id_xyz")
        assertFalse(isOutboundEcho(account(), msg))
    }

    @Test
    fun fromNull_isNotEcho() {
        assertFalse(isOutboundEcho(account(), message(null)))
    }

    @Test
    fun fromBlank_isNotEcho() {
        assertFalse(isOutboundEcho(account(), message("   ")))
    }

    @Test
    fun fromOtherUser_isNotEcho() {
        assertFalse(isOutboundEcho(account(), message("another_user_42")))
    }

    @Test
    fun blankBotId_isNotEcho() {
        assertFalse(isOutboundEcho(account(ilinkBotId = ""), message("bot_id_abc")))
    }

    @Test
    fun blankIlUserIdWithBotIdEcho_stillDetectsBotEcho() {
        // ilink_user_id 为空不应影响 bot 回声判定
        val acct = account(ilinkUserId = "")
        assertTrue(isOutboundEcho(acct, message("bot_id_abc")))
        assertFalse(isOutboundEcho(acct, message("user_id_xyz")))
    }

    @Test
    fun mask_normalUserId_keepsFirst3AndLast2() {
        assertEquals("abc***ij", maskWeChatUserId("abcdefghij"))
    }

    @Test
    fun mask_shortUserId_fullyMasked() {
        assertEquals("***", maskWeChatUserId("abc123"))
        assertEquals("***", maskWeChatUserId("ab"))
    }

    @Test
    fun mask_nullOrEmpty_fullyMasked() {
        assertEquals("***", maskWeChatUserId(null))
        assertEquals("***", maskWeChatUserId(""))
    }
}
