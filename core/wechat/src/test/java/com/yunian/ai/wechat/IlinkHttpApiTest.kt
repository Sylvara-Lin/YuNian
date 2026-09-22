package com.yunian.ai.wechat

import com.yunian.ai.wechat.ilink.IlinkClientManager
import com.yunian.ai.wechat.ilink.IlinkHttpApi
import com.yunian.ai.wechat.ilink.IlinkSessionExpiredException
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class IlinkHttpApiTest {

    private val api = IlinkHttpApi()

    // ------------------------------------------------------------------
    // 请求头（对标官方 buildHeaders，禁止 1.x 遗留头）
    // ------------------------------------------------------------------

    @Test
    fun headers_matchOfficialProtocol_withToken() {
        val headers = api.buildHeaders(" bot-token-1 ")

        assertEquals("application/json", headers["Content-Type"])
        assertEquals("ilink_bot_token", headers["AuthorizationType"])
        assertEquals("Bearer bot-token-1", headers["Authorization"])
        assertNotNull(headers["X-WECHAT-UIN"])
    }

    @Test
    fun headers_omitAuthorizationWhenTokenBlank() {
        assertFalse(api.buildHeaders(null).containsKey("Authorization"))
        assertFalse(api.buildHeaders("   ").containsKey("Authorization"))
    }

    @Test
    fun headers_neverContainForbiddenIlinkAppHeaders() {
        val headers = api.buildHeaders("tok")
        assertFalse(headers.containsKey("iLink-App-Id"))
        assertFalse(headers.containsKey("iLink-App-ClientVersion"))
    }

    @Test
    fun xWechatUin_isBase64OfDecimalUint32() {
        repeat(20) {
            val uin = api.buildHeaders(null)["X-WECHAT-UIN"]!!
            val decoded = String(Base64.getDecoder().decode(uin), Charsets.UTF_8)
            val value = decoded.toLong()
            assertTrue("uint32 range: $decoded", value in 0..0xFFFF_FFFFL)
        }
    }

    // ------------------------------------------------------------------
    // base_info（仅 channel_version，禁止 bot_agent）
    // ------------------------------------------------------------------

    @Test
    fun baseInfo_containsOnlyChannelVersion() {
        val baseInfo = api.buildBaseInfo()
        assertEquals(setOf("channel_version"), baseInfo.jsonObject.keys)
        val version = baseInfo.jsonObject["channel_version"]!!.toString().trim('"')
        assertTrue(version.isNotBlank())
        assertEquals(IlinkClientManager.currentChannelVersion(), version)
        assertFalse(baseInfo.toString().contains("bot_agent"))
    }

    // ------------------------------------------------------------------
    // 官方判错与 -14 会话过期
    // ------------------------------------------------------------------

    @Test
    fun checkApiError_passesWhenNoError() {
        api.checkApiError(null, null, null)
        api.checkApiError(0, null, null)
        api.checkApiError(null, 0, null)
        api.checkApiError(0, 0, "ignored")
    }

    @Test
    fun checkApiError_sessionExpiredNegative14() {
        assertThrows(IlinkSessionExpiredException::class.java) {
            api.checkApiError(null, -14, "session expired")
        }
        assertThrows(IlinkSessionExpiredException::class.java) {
            api.checkApiError(-14, null, "session expired")
        }
        assertThrows(IlinkSessionExpiredException::class.java) {
            api.checkApiError(1, -14, "session expired")
        }
    }

    @Test
    fun checkApiError_otherNonZeroCodesThrowIllegalState() {
        assertThrows(IllegalStateException::class.java) {
            api.checkApiError(5, null, "boom")
        }
        assertThrows(IllegalStateException::class.java) {
            api.checkApiError(null, 1001, "boom")
        }
    }

    @Test
    fun checkSendResponse_passesEmptyAndOkBodies() {
        // 官方 sendmessage 成功响应常为空对象，直接通过
        api.checkSendResponse("")
        api.checkSendResponse("{}")
        api.checkSendResponse("""{"ret":0}""")
        api.checkSendResponse("""{"errcode":0,"errmsg":"ok"}""")
    }

    @Test
    fun checkSendResponse_sessionExpiredNegative14() {
        assertThrows(IlinkSessionExpiredException::class.java) {
            api.checkSendResponse("""{"errcode":-14,"errmsg":"session expired"}""")
        }
        assertThrows(IlinkSessionExpiredException::class.java) {
            api.checkSendResponse("""{"ret":-14}""")
        }
    }

    @Test
    fun checkSendResponse_otherErrorsThrowIllegalState() {
        assertThrows(IllegalStateException::class.java) {
            api.checkSendResponse("""{"ret":5,"errmsg":"boom"}""")
        }
    }

    @Test
    fun sessionExpiredException_isIllegalStateForLegacyHandlers() {
        val e = IlinkSessionExpiredException()
        assertTrue(e is IllegalStateException)
        assertTrue(e.message.orEmpty().contains("-14"))
    }

    // ------------------------------------------------------------------
    // 协议常量
    // ------------------------------------------------------------------

    @Test
    fun protocolConstants_matchOfficial() {
        assertEquals(5_000L, IlinkHttpApi.GET_QRCODE_TIMEOUT_MS)
        assertEquals(35_000L, IlinkHttpApi.QR_LONG_POLL_TIMEOUT_MS)
        assertEquals(35_000L, IlinkHttpApi.DEFAULT_LONG_POLL_TIMEOUT_MS)
        assertEquals(15_000L, IlinkHttpApi.API_TIMEOUT_MS)
        assertEquals("ilink/bot/getupdates", IlinkHttpApi.GET_UPDATES_ENDPOINT)
        assertEquals("ilink/bot/sendmessage", IlinkHttpApi.SEND_MESSAGE_ENDPOINT)
        assertEquals("ilink/bot/getuploadurl", IlinkHttpApi.GET_UPLOAD_URL_ENDPOINT)
        assertTrue(IlinkHttpApi.GET_BOT_QRCODE_ENDPOINT.contains("bot_type=3"))
        assertEquals(-14, IlinkHttpApi.SESSION_EXPIRED_ERRCODE)
        assertEquals(3, IlinkClientManager.MAX_QR_REFRESH_COUNT)
        assertEquals("https://ilinkai.weixin.qq.com", IlinkClientManager.DEFAULT_BASE_URL)
    }
}
