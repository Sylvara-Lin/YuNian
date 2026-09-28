package com.yunian.ai.feature.wechat.data

import android.content.Context
import com.yunian.ai.common.RemoteKeyProvider
import com.yunian.ai.common.SuFlowApi
import com.yunian.ai.network.CertificatePins
import com.yunian.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class WeChatRelayPushRequest(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val payload: JsonElement
)

@Serializable
data class WeChatRelayPushResponse(
    val id: String,
    val created: Boolean
)

@Serializable
data class WeChatRelayEvent(
    val id: String,
    val direction: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val payload: JsonElement,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class WeChatRelayAckRequest(
    @SerialName("event_ids") val eventIds: List<String>
)

class WeChatRelayClient(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(SuFlowApi.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SuFlowApi.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SuFlowApi.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .certificatePinner(CertificatePins.certificatePinner)
        .apply(RequestSecurityInterceptor::enforceTls)
        .build()

    suspend fun pushOutbound(idempotencyKey: String, payload: JsonElement): WeChatRelayPushResponse {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200)
        val body = json.encodeToString(
            WeChatRelayPushRequest.serializer(),
            WeChatRelayPushRequest(idempotencyKey, payload)
        )
        return executeWithSessionRetry { token ->
            Request.Builder()
                .url("${SuFlowApi.BASE_URL}$EVENTS_PATH")
                .header(SESSION_HEADER, token)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.use { response ->
            json.decodeFromString(
                WeChatRelayPushResponse.serializer(),
                response.body?.string() ?: throw IOException("Empty relay response")
            )
        }
    }

    suspend fun pullInbound(limit: Int = 50): List<WeChatRelayEvent> {
        val boundedLimit = limit.coerceIn(1, 100)
        return executeWithSessionRetry { token ->
            Request.Builder()
                .url("${SuFlowApi.BASE_URL}$EVENTS_PATH?limit=$boundedLimit")
                .header(SESSION_HEADER, token)
                .get()
                .build()
        }.use { response ->
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(WeChatRelayEvent.serializer()),
                response.body?.string() ?: throw IOException("Empty relay response")
            )
        }
    }

    suspend fun acknowledgeInbound(eventIds: List<String>) {
        require(eventIds.isNotEmpty() && eventIds.size <= 100)
        val body = json.encodeToString(
            WeChatRelayAckRequest.serializer(),
            WeChatRelayAckRequest(eventIds)
        )
        executeWithSessionRetry { token ->
            Request.Builder()
                .url("${SuFlowApi.BASE_URL}$ACK_PATH")
                .header(SESSION_HEADER, token)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.close()
    }

    private suspend fun executeWithSessionRetry(request: (String) -> Request) = withContext(Dispatchers.IO) {
        var session = RemoteKeyProvider.ensureSession(appContext)
            ?: throw IOException("YuNian session unavailable")
        var response = client.newCall(request(session.token)).execute()
        if (response.code == 401) {
            response.close()
            session = RemoteKeyProvider.ensureSession(appContext, forceRefresh = true)
                ?: throw IOException("YuNian session refresh failed")
            response = client.newCall(request(session.token)).execute()
        }
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.body?.string()?.take(500)
            response.close()
            throw IOException("Relay request failed: HTTP $code ${message.orEmpty()}")
        }
        response
    }

    private companion object {
        const val EVENTS_PATH = "/api/lianyu/wechat/events"
        const val ACK_PATH = "/api/lianyu/wechat/events/ack"
        const val SESSION_HEADER = "X-LianYu-Session"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}