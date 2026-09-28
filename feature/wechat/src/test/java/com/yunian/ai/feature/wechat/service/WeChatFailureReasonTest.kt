package com.yunian.ai.feature.wechat.service

import com.yunian.ai.domain.wechat.WeChatFailureReason
import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatFailureReasonTest {

    @Test
    fun fromPollMessage_classifiesCommonCases() {
        assertEquals(
            WeChatFailureReason.POLL_TIMEOUT,
            WeChatFailureReason.fromPollMessage("socket timeout after 30s"),
        )
        assertEquals(
            WeChatFailureReason.POLL_CONNECTION,
            WeChatFailureReason.fromPollMessage("failed to connect to host"),
        )
        assertEquals(
            WeChatFailureReason.POLL_AUTH,
            WeChatFailureReason.fromPollMessage("HTTP 401 unauthorized"),
        )
        assertEquals(
            WeChatFailureReason.POLL_UNKNOWN,
            WeChatFailureReason.fromPollMessage("something else"),
        )
        assertEquals(
            WeChatFailureReason.POLL_UNKNOWN,
            WeChatFailureReason.fromPollMessage(null),
        )
    }
}
