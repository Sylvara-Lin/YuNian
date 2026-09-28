package com.yunian.ai.wechat.ilink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * iLink 会话过期（官方协议 errcode = -14）。
 * 轮询层捕获此异常后应暂停收发并向上层暴露"需要重新扫码登录"状态。
 */
class IlinkSessionExpiredException(
    message: String = "iLink 会话已过期（errcode=-14），请重新扫码登录",
) : IllegalStateException(message)

/**
 * 微信 iLink Bot 官方 HTTP API 封装（对标 @tencent-weixin/openclaw-weixin 的 api/api.ts）。
 *
 * 请求头与 base_info 严格对齐官方实现：
 * - `Content-Type: application/json`
 * - `AuthorizationType: ilink_bot_token`
 * - `X-WECHAT-UIN`: 随机 uint32 → 十进制字符串 → base64
 * - `Authorization: Bearer {bot_token}`（token 非空时）
 * - **禁止**携带官方没有的 `iLink-App-Id` / `iLink-App-ClientVersion` 头
 * - base_info 仅含 `{channel_version}`，**禁止** `bot_agent` 字段
 *
 * GET 端点（二维码获取/状态长轮询）与官方 apiGetFetch 一致，不带上述头。
 */
class IlinkHttpApi(client: OkHttpClient? = null) {

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        // 长轮询由 call timeout 精确控制，全局 read 留出余量避免抢占
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val random = SecureRandom()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /** base_info：官方实现仅有 channel_version（取官方插件最新版本号，见 IlinkClientManager）。 */
    fun buildBaseInfo(): JsonObject = buildJsonObject {
        put("channel_version", IlinkClientManager.currentChannelVersion())
    }

    /**
     * 官方判错（monitor.ts isApiError）：ret 存在且 != 0，或 errcode 存在且 != 0。
     * errcode/ret == -14 抛 [IlinkSessionExpiredException]；其他错误抛 [IllegalStateException]。
     */
    fun checkApiError(ret: Int?, errcode: Int?, errmsg: String?) {
        val hasError = (ret != null && ret != 0) || (errcode != null && errcode != 0)
        if (!hasError) return
        if (ret == SESSION_EXPIRED_ERRCODE || errcode == SESSION_EXPIRED_ERRCODE) {
            throw IlinkSessionExpiredException()
        }
        throw IllegalStateException(
            "ilink api error ret=$ret errcode=$errcode errmsg=${errmsg.orEmpty()}",
        )
    }

    /**
     * 校验 sendmessage / getuploadurl 等响应体中的 ret/errcode（官方响应体常为空 `{}`，
     * 此时直接通过；出错时 errcode=-14 抛 [IlinkSessionExpiredException]，供发送链路
     * 判定为不可重试并向上层暴露"需重新扫码登录"）。
     */
    fun checkSendResponse(rawText: String) {
        val root = runCatching { jsonParser.parseToJsonElement(rawText).jsonObject }.getOrNull() ?: return
        val ret = root["ret"]?.jsonPrimitive?.intOrNull
        val errcode = root["errcode"]?.jsonPrimitive?.intOrNull
        val errmsg = root["errmsg"]?.jsonPrimitive?.contentOrNull
        checkApiError(ret, errcode, errmsg)
    }

    /**
     * GET 请求（二维码获取 / get_qrcode_status 长轮询），返回原始响应文本。
     * [timeoutMs] 为整次调用（含长轮询挂起）的超时；超时抛 [java.net.SocketTimeoutException]。
     */
    fun getRaw(baseUrl: String, endpoint: String, timeoutMs: Long): String {
        val url = "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
        val request = Request.Builder().url(url).get().build()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(
                    "GET $endpoint http ${resp.code}: ${text.take(200)}",
                )
            }
            return text
        }
    }

    /**
     * POST JSON 请求，返回原始响应文本。非 2xx 抛 [IOException] 子类/[IllegalStateException]。
     */
    fun postJson(
        baseUrl: String,
        endpoint: String,
        body: String,
        botToken: String?,
        timeoutMs: Long,
    ): String {
        val url = "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        buildHeaders(botToken).forEach { (name, value) -> builder.header(name, value) }
        val call = http.newCall(builder.build())
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(
                    "POST $endpoint http ${resp.code}: ${text.take(200)}",
                )
            }
            return text
        }
    }

    /** 官方 buildHeaders：供 POST JSON 端点使用。 */
    internal fun buildHeaders(botToken: String?): Map<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "AuthorizationType" to AUTHORIZATION_TYPE,
            "X-WECHAT-UIN" to randomWechatUin(),
        )
        val token = botToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /** X-WECHAT-UIN：随机 uint32 → 十进制字符串 → base64（与官方 randomWechatUin 一致）。 */
    private fun randomWechatUin(): String {
        val uint32 = random.nextLong() and 0xFFFF_FFFFL
        return Base64.getEncoder().encodeToString(uint32.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val SESSION_EXPIRED_ERRCODE = -14

        const val GET_BOT_QRCODE_ENDPOINT = "ilink/bot/get_bot_qrcode?bot_type=3"
        const val GET_QRCODE_STATUS_ENDPOINT = "ilink/bot/get_qrcode_status"
        const val GET_UPDATES_ENDPOINT = "ilink/bot/getupdates"
        const val SEND_MESSAGE_ENDPOINT = "ilink/bot/sendmessage"
        const val GET_UPLOAD_URL_ENDPOINT = "ilink/bot/getuploadurl"

        /** 二维码获取超时（官方 GET_QRCODE_TIMEOUT_MS）。 */
        const val GET_QRCODE_TIMEOUT_MS = 5_000L
        /** 二维码状态长轮询超时（官方 QR_LONG_POLL_TIMEOUT_MS）。 */
        const val QR_LONG_POLL_TIMEOUT_MS = 35_000L
        /** getupdates 默认长轮询超时（官方 DEFAULT_LONG_POLL_TIMEOUT_MS）。 */
        const val DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000L
        /** 常规 API（sendmessage / getuploadurl）超时（官方 DEFAULT_API_TIMEOUT_MS）。 */
        const val API_TIMEOUT_MS = 15_000L

        private const val AUTHORIZATION_TYPE = "ilink_bot_token"
        private const val CONNECT_TIMEOUT_S = 10L
        private const val WRITE_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 40L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
