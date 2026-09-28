package com.yunian.ai.wechat

import com.yunian.ai.wechat.ilink.IlinkHttpApi
import com.yunian.ai.wechat.ilink.IlinkWireJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IlinkWireJsonTest {

    // ------------------------------------------------------------------
    // getupdates 响应解析 → Wire 模型
    // ------------------------------------------------------------------

    @Test
    fun parsesOfficialTextMessageSnakeCase() {
        val raw = """
            {
              "ret": 0,
              "msgs": [
                {
                  "seq": 42,
                  "message_id": 42,
                  "from_user_id": "user@im.wechat",
                  "to_user_id": "bot@ilink.ai",
                  "client_id": "wxid_abc",
                  "create_time_ms": 1717171717171,
                  "session_id": "s-1",
                  "message_type": 1,
                  "message_state": 0,
                  "item_list": [ { "type": 1, "text_item": { "text": "hello official" } } ],
                  "context_token": "ctx-token-xyz"
                }
              ],
              "get_updates_buf": "BUF-1",
              "longpolling_timeout_ms": 30000
            }
        """.trimIndent()

        val resp = IlinkWireJson.parseUpdatesResponse(raw)

        assertEquals("BUF-1", resp.getUpdatesBuf)
        assertEquals(30000L, resp.longpollingTimeoutMs)
        assertEquals(0, resp.ret)
        assertNull(resp.errcode)

        val wire = IlinkWireJson.toWireMessage(resp.msgs.single())
        assertEquals(42L, wire.messageId)
        assertEquals(42L, wire.seq)
        assertEquals("user@im.wechat", wire.fromUserId)
        assertEquals("bot@ilink.ai", wire.toUserId)
        assertEquals(1717171717171L, wire.createTimeMs)
        assertEquals("s-1", wire.sessionId)
        assertEquals(1, wire.messageType)
        assertEquals(0, wire.messageState)
        assertEquals("ctx-token-xyz", wire.contextToken)
        assertEquals("hello official", wire.itemList?.single()?.textItem?.text)
    }

    @Test
    fun inboundImageHexAeskeyIsUnifiedToBase64AndPreferred() {
        // image_item.aeskey 为 hex 明文（16 字节），应优先并统一成 base64
        val hexKey = "00112233445566778899aabbccddeeff"
        val raw = """
            {
              "msgs": [
                {
                  "message_id": 7,
                  "from_user_id": "u1",
                  "context_token": "tok",
                  "item_list": [ {
                    "type": 2,
                    "image_item": {
                      "media": { "encrypt_query_param": "EQP", "aes_key": "c3R1ZmZzdHVmZnN0dWZmc3R1Zg==", "encrypt_type": 1 },
                      "aeskey": "$hexKey",
                      "mid_size": 1024
                    }
                  } ]
                }
              ]
            }
        """.trimIndent()

        val resp = IlinkWireJson.parseUpdatesResponse(raw)
        val wire = IlinkWireJson.toWireMessage(resp.msgs.single())
        val cdn = wire.itemList?.single()?.imageItem?.cdnImg

        val expected = java.util.Base64.getEncoder()
            .encodeToString(com.yunian.ai.wechat.ilink.IlinkCdnCodec.hexToBytes(hexKey))
        assertEquals(expected, cdn?.aesKey)
        assertEquals("EQP", cdn?.encryptQueryParam)
    }

    @Test
    fun inboundImageMediaAesKeyBase64PassesThroughWhenHexAbsent() {
        val raw = """
            {
              "msgs": [
                {
                  "message_id": 8,
                  "item_list": [ {
                    "type": 2,
                    "image_item": {
                      "media": { "encrypt_query_param": "EQP2", "aes_key": "c3R1ZmZzdHVmZnN0dWZmc3R1Zg==" }
                    }
                  } ]
                }
              ]
            }
        """.trimIndent()

        val resp = IlinkWireJson.parseUpdatesResponse(raw)
        val cdn = IlinkWireJson.toWireMessage(resp.msgs.single()).itemList?.single()?.imageItem?.cdnImg
        assertEquals("c3R1ZmZzdHVmZnN0dWZmc3R1Zg==", cdn?.aesKey)
    }

    @Test
    fun unknownJsonFieldsAreIgnored() {
        val raw = """
            {
              "msgs": [
                {
                  "message_id": 9,
                  "future_field": { "x": 1 },
                  "item_list": [ { "type": 1, "text_item": { "text": "t" }, "future_item": true } ]
                }
              ]
            }
        """.trimIndent()

        val wire = IlinkWireJson.toWireMessage(IlinkWireJson.parseUpdatesResponse(raw).msgs.single())
        assertEquals("t", wire.itemList?.single()?.textItem?.text)
    }

    @Test
    fun voiceFileVideoItemsMapToWire() {
        val raw = """
            {
              "msgs": [
                {
                  "message_id": 10,
                  "item_list": [
                    { "type": 3, "voice_item": { "media": { "encrypt_query_param": "v" }, "encode_type": 6, "text": "voice text" } },
                    { "type": 4, "file_item": { "media": { "encrypt_query_param": "f" }, "file_name": "a.pdf" } },
                    { "type": 5, "video_item": { "media": { "encrypt_query_param": "vd" }, "thumb_media": { "encrypt_query_param": "th" } } }
                  ]
                }
              ]
            }
        """.trimIndent()

        val wire = IlinkWireJson.toWireMessage(IlinkWireJson.parseUpdatesResponse(raw).msgs.single())
        val items = wire.itemList.orEmpty()
        assertEquals(3, items.size)
        assertEquals("v", items[0].voiceItem?.cdnVoice?.encryptQueryParam)
        assertEquals("a.pdf", items[1].fileItem?.fileName)
        assertEquals("vd", items[2].videoItem?.cdnVideo?.encryptQueryParam)
        assertEquals("th", items[2].videoItem?.cdnThumb?.encryptQueryParam)
    }

    // ------------------------------------------------------------------
    // 请求体组装
    // ------------------------------------------------------------------

    @Test
    fun textMessageBodyMatchesOfficialProtocol() {
        val baseInfo = IlinkHttpApi().buildBaseInfo()
        val body = IlinkWireJson.buildTextMessageBody(
            toUserId = "u1",
            text = "hi",
            contextToken = "tok",
            clientId = "lianyu-android:1-abcd1234",
            baseInfo = baseInfo,
        )

        val msg = body["msg"]!!.jsonObject
        assertEquals("", msg["from_user_id"]!!.jsonPrimitive.content)
        assertEquals("u1", msg["to_user_id"]!!.jsonPrimitive.content)
        assertEquals("lianyu-android:1-abcd1234", msg["client_id"]!!.jsonPrimitive.content)
        assertEquals(2, msg["message_type"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, msg["message_state"]!!.jsonPrimitive.content.toInt())
        assertEquals("tok", msg["context_token"]!!.jsonPrimitive.content)
        val item = msg["item_list"]!!.let { it }.toString()
        assertTrue(item.contains("\"type\":1"))
        assertTrue(item.contains("\"text_item\":{\"text\":\"hi\"}"))
        // base_info 必须原样挂在外层
        assertEquals(baseInfo, body["base_info"]!!.jsonObject)
        // 禁止官方没有的 bot_agent 字段
        assertFalse(body.toString().contains("bot_agent"))
    }

    @Test
    fun getUploadUrlBodyMatchesOfficialProtocol() {
        val body = IlinkWireJson.buildGetUploadUrlBody(
            filekey = "fk",
            toUserId = "u1",
            rawsize = 100,
            rawfilemd5 = "md5",
            filesize = 112,
            aeskeyHex = "00112233445566778899aabbccddeeff",
            baseInfo = IlinkHttpApi().buildBaseInfo(),
        )
        val text = body.toString()
        assertTrue(text.contains("\"filekey\":\"fk\""))
        assertTrue(text.contains("\"media_type\":1"))
        assertTrue(text.contains("\"rawsize\":100"))
        assertTrue(text.contains("\"rawfilemd5\":\"md5\""))
        assertTrue(text.contains("\"filesize\":112"))
        assertTrue(text.contains("\"no_need_thumb\":true"))
        assertTrue(text.contains("\"aeskey\":\"00112233445566778899aabbccddeeff\""))
        assertFalse(text.contains("bot_agent"))
    }

    @Test
    fun imageMessageBodyMatchesOfficialProtocol() {
        val body = IlinkWireJson.buildImageMessageBody(
            toUserId = "u1",
            contextToken = "tok",
            clientId = "cid",
            downloadEncryptedQueryParam = "DP",
            aesKeyBase64 = "QUJD",
            cipherSize = 112,
            baseInfo = IlinkHttpApi().buildBaseInfo(),
        )
        val text = body.toString()
        assertTrue(text.contains("\"type\":2"))
        assertTrue(text.contains("\"encrypt_query_param\":\"DP\""))
        assertTrue(text.contains("\"aes_key\":\"QUJD\""))
        assertTrue(text.contains("\"encrypt_type\":1"))
        assertTrue(text.contains("\"mid_size\":112"))
        assertTrue(text.contains("\"context_token\":\"tok\""))
    }
}
