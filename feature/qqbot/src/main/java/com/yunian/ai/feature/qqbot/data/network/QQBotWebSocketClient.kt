package com.yunian.ai.feature.qqbot.data.network

import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.feature.qqbot.data.QQBotTokenStore
import com.yunian.ai.feature.qqbot.data.model.QQGatewayPayload
import com.yunian.ai.feature.qqbot.data.model.QQHelloData
import com.yunian.ai.feature.qqbot.data.model.QQReadyData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.yunian.ai.common.ChatConstants
import com.yunian.ai.network.NetworkConstants
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class QQBotWebSocketClient(
    private val tokenStore: QQBotTokenStore,
    private val apiClient: QQBotApiClient,
    private val onEvent: suspend (QQGatewayPayload) -> Unit,
    private val onConnectionStateChange: ((ConnectionState) -> Unit)? = null
) {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        AUTH_FAILED
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private val client = OkHttpClient.Builder()
        .connectTimeout(NetworkConstants.QQ_BOT_WS_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(NetworkConstants.QQ_BOT_WS_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .writeTimeout(NetworkConstants.QQ_BOT_WS_WRITE_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .pingInterval(NetworkConstants.QQ_BOT_WS_PING_INTERVAL_SECONDS.toLong(), TimeUnit.SECONDS)
        .build()

    private val connectMutex = Mutex()
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var handshakeWatchdogJob: Job? = null

    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val lastSequence = AtomicLong(0)
    private val reconnectAttempt = AtomicInteger(0)

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var heartbeatActive = false

    @Volatile
    private var lastHeartbeatAckMs = 0L

    private val heartbeatAckTracker = HeartbeatAckTracker()

    private val opDispatch = 0
    private val opHeartbeat = 1
    private val opIdentify = 2
    private val opResume = 6
    private val opReconnect = 7
    private val opInvalidSession = 9
    private val opHello = 10
    private val opHeartbeatAck = 11

    // 仅订阅群聊/单聊事件（GROUP_AND_C2C_EVENT）与互动事件（INTERACTION）。
    // 旧版本额外声明了频道域 intents（GUILDS/GUILD_MESSAGES/DIRECT_MESSAGE/PUBLIC_GUILD_MESSAGES），
    // 机器人无对应权限时 IDENTIFY 会被服务端拒绝，表现为一直卡在“连接中”。如需频道能力再单独加回。
    private val intents = GROUP_AND_C2C_EVENT_INTENT or INTERACTION_INTENT

    suspend fun connect() = connectMutex.withLock {
        connectLocked()
    }

    private suspend fun connectLocked() {
        if (isConnected.get() || isConnecting.get()) return
        isConnecting.set(true)
        onConnectionStateChange?.invoke(ConnectionState.CONNECTING)
        try {
            val account = tokenStore.getAccount()
                ?: throw IllegalStateException("未配置 QQ Bot 账号")

            val retryCount = reconnectAttempt.get()
            if (retryCount >= 3) {
                SecureLog.w(TAG, "Reconnect attempt $retryCount, clearing token cache for fresh token")
                apiClient.clearApiCache()
            }
            sessionId = tokenStore.getSessionId()?.takeIf { it.isNotBlank() }
            val persistedSeq = tokenStore.getLastSequence()
            if (persistedSeq > 0L) {
                lastSequence.set(persistedSeq)
            }

            val restApi = apiClient.createAuthenticatedRestApi()
            val gateway = restApi.getGateway()
            if (!gateway.isSuccessful || gateway.body() == null) {
                val errorBody = gateway.errorBody()?.string()
                val hint = when (gateway.code()) {
                    401 -> "（access token 无效或已过期）"
                    403 -> "（请求被拒绝：请检查开放平台 IP 白名单配置——Android 动态 IP 需在沙箱环境验证）"
                    else -> ""
                }
                SecureLog.e(TAG, "获取 QQ Gateway 失败: ${gateway.code()}$hint body=$errorBody")
                throw IllegalStateException("获取 QQ Gateway 失败: ${gateway.code()}$hint $errorBody")
            }
            val gatewayUrl = gateway.body()!!.url
            SecureLog.i(TAG, "Gateway URL: $gatewayUrl")
            val request = Request.Builder().url(gatewayUrl).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    SecureLog.i(TAG, "WebSocket connected")
                    // 看门狗：READ_TIMEOUT=0 下若服务端永不下发 HELLO（典型原因：
                    // 开放平台 IP 白名单拦截 / 后台已切换 Webhook 导致 WS 链路停用），
                    // 连接会无限挂起，UI 永远停在“连接中”。超时强制断开走重连。
                    startHandshakeWatchdog()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    SecureLog.w(TAG, "WebSocket closing: $code $reason")
                    cleanupConnectionState()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    SecureLog.w(TAG, "WebSocket closed: $code $reason")
                    cleanupConnectionState()
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    SecureLog.e(TAG, "WebSocket failure: ${t.message}", t)
                    cleanupConnectionState()
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            SecureLog.e(TAG, "connect failed: ${e.message}", e)
            isConnecting.set(false)

            apiClient.clearApiCache()

            if (reconnectAttempt.get() >= 5) {
                onConnectionStateChange?.invoke(ConnectionState.AUTH_FAILED)
            }
            scheduleReconnect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        cancelHandshakeWatchdog()
        heartbeatActive = false
        webSocket?.close(1000, "manual disconnect")
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
        onConnectionStateChange?.invoke(ConnectionState.DISCONNECTED)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private fun handleMessage(text: String) {
        try {
            val payload = json.decodeFromString<QQGatewayPayload>(text)
            payload.s?.let {
                lastSequence.set(it.toLong())
                scope.launch { tokenStore.setLastSequence(it.toLong()) }
            }
            when (payload.op) {
                opDispatch -> {
                    if (payload.t == "READY") {
                        payload.d?.let {
                            val ready = json.decodeFromJsonElement<QQReadyData>(it)
                            sessionId = ready.sessionId

                            scope.launch { tokenStore.setSessionId(ready.sessionId) }
                            SecureLog.i(TAG, "READY: sessionId=${ready.sessionId}, bot=${ready.user?.username}")
                        }

                        isConnected.set(true)
                        isConnecting.set(false)
                        reconnectAttempt.set(0)
                        cancelHandshakeWatchdog()
                        onConnectionStateChange?.invoke(ConnectionState.CONNECTED)
                    }
                    scope.launch { onEvent(payload) }
                }
                opHello -> {
                    val hello = payload.d?.let { json.decodeFromJsonElement<QQHelloData>(it) }
                    val heartbeatIntervalMs = hello?.heartbeatInterval
                        ?.takeIf { it > 0 }
                        ?: DEFAULT_HEARTBEAT_INTERVAL_MS

                    val savedSessionId = sessionId
                    val savedSeq = lastSequence.get()
                    val handshakeSent = if (!savedSessionId.isNullOrBlank() && savedSeq > 0) {
                        sendResume(savedSessionId, savedSeq)
                    } else {
                        sendIdentify()
                    }

                    if (!handshakeSent) {
                        SecureLog.w(TAG, "Handshake deferred: no usable access token, refreshing")
                        refreshTokenAndRetryHandshake(savedSessionId, savedSeq, heartbeatIntervalMs)
                        return
                    }
                    startHeartbeat(heartbeatIntervalMs)

                    isConnecting.set(false)
                }
                opReconnect -> {
                    SecureLog.w(TAG, "Server requested reconnect")
                    reconnect()
                }
                opInvalidSession -> {
                    SecureLog.w(TAG, "Invalid session, clearing session state")
                    sessionId = null
                    scope.launch {
                        tokenStore.setSessionId(null)
                        tokenStore.setLastSequence(0)
                    }

                    if (reconnectAttempt.get() >= 5) {
                        onConnectionStateChange?.invoke(ConnectionState.AUTH_FAILED)
                    }
                    reconnect()
                }
                opHeartbeatAck -> {
                    lastHeartbeatAckMs = System.currentTimeMillis()
                }
                else -> SecureLog.d(TAG, "Unhandled op: ${payload.op}")
            }
        } catch (e: Exception) {
            SecureLog.e(TAG, "Failed to handle gateway message: $text", e)
        }
    }

    private fun refreshTokenAndRetryHandshake(
        savedSessionId: String?,
        savedSeq: Long,
        heartbeatIntervalMs: Long,
    ) {
        scope.launch {
            val account = tokenStore.getAccount()
            if (account == null) {
                SecureLog.e(TAG, "No QQ Bot account configured, aborting handshake")
                return@launch
            }
            val token = runCatching { apiClient.getOrRefreshToken(account) }
                .onFailure { SecureLog.e(TAG, "Token refresh failed during handshake", it) }
                .getOrNull()

            if (token.isNullOrBlank()) {
                reconnect()
                return@launch
            }

            val handshakeSent = if (!savedSessionId.isNullOrBlank() && savedSeq > 0) {
                sendResume(savedSessionId, savedSeq)
            } else {
                sendIdentify()
            }
            if (!handshakeSent) {
                SecureLog.e(TAG, "Handshake still not sent after token refresh, reconnecting")
                reconnect()
                return@launch
            }
            startHeartbeat(heartbeatIntervalMs)
            isConnecting.set(false)
        }
    }

    private fun sendIdentify(): Boolean {

        val token = apiClient.getCachedToken()

        if (token == null) {
            SecureLog.w(TAG, "Cached token is null/expired, refresh required before identify")

            return false
        }
        val d = JsonObject(
            mapOf(
                "token" to JsonPrimitive("QQBot $token"),
                "intents" to JsonPrimitive(intents),
                "shard" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1))),
                "properties" to JsonObject(
                    mapOf(
                        PROPERTY_OS to JsonPrimitive("android"),
                        PROPERTY_BROWSER to JsonPrimitive("YuNianQQBot"),
                        PROPERTY_DEVICE to JsonPrimitive("YuNianAndroid")
                    )
                )
            )
        )
        val payload = QQGatewayPayload(op = opIdentify, d = d)
        send(json.encodeToString(payload))
        SecureLog.i(TAG, "Identify sent successfully")
        return true
    }

    private fun sendResume(sessionIdValue: String, seq: Long): Boolean {
        var token = apiClient.getCachedToken()

        if (token == null) {
            SecureLog.w(TAG, "Cached token is null/expired for resume, will reconnect with fresh token")
            scope.launch {
                try {
                    val account = tokenStore.getAccount() ?: return@launch
                    apiClient.getOrRefreshToken(account)
                } catch (e: Exception) {
                    SecureLog.e(TAG, "Token refresh failed during resume", e)
                }
            }
            return false
        }
        val d = JsonObject(
            mapOf(
                "token" to JsonPrimitive("QQBot $token"),
                "session_id" to JsonPrimitive(sessionIdValue),
                "seq" to JsonPrimitive(seq)
            )
        )
        val payload = QQGatewayPayload(op = opResume, d = d)
        send(json.encodeToString(payload))
        SecureLog.i(TAG, "Resume sent with sessionId=$sessionIdValue, seq=$seq")
        return true
    }

    suspend fun connectWithFreshToken() = connectMutex.withLock {
        if (isConnected.get()) return

        // 用户主动重试：重置重连计数，避免上一轮失败次数累计导致直接 AUTH_FAILED
        reconnectAttempt.set(0)
        apiClient.clearApiCache()
        tokenStore.setAccessToken(null)
        tokenStore.setTokenExpireAt(0)
        SecureLog.i(TAG, "Token cache cleared, retrying connect with fresh token")
        connectLocked()
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatActive = true
        lastHeartbeatAckMs = 0L
        heartbeatAckTracker.reset()
        heartbeatJob = scope.launch {
            val period = (intervalMs * 0.8).toLong().coerceAtLeast(5000)
            while (isActive && heartbeatActive) {
                delay(period)
                if (!heartbeatActive) break

                if (heartbeatAckTracker.onBeforeSend(System.currentTimeMillis(), lastHeartbeatAckMs)) {
                    SecureLog.w(TAG, "No heartbeat ACK for 2 consecutive intervals, reconnecting")
                    reconnect()
                    return@launch
                }
                send(json.encodeToString(QQGatewayPayload(op = opHeartbeat, d = JsonPrimitive(lastSequence.get()))))
            }
        }
    }

    private fun send(text: String): Boolean {
        val sent = webSocket?.send(text) ?: false
        if (!sent) SecureLog.w(TAG, "Failed to send websocket message")
        return sent
    }

    private fun cleanupConnectionState() {
        val wasConnected = isConnected.get()
        isConnected.set(false)
        isConnecting.set(false)
        heartbeatActive = false
        lastHeartbeatAckMs = 0L
        heartbeatAckTracker.reset()
        heartbeatJob?.cancel()
        heartbeatJob = null
        cancelHandshakeWatchdog()
        if (wasConnected) {
            onConnectionStateChange?.invoke(ConnectionState.DISCONNECTED)
        }
    }

    /** 握手看门狗：onOpen 后 HANDSHAKE_TIMEOUT_MS 内未进入 CONNECTED（收到 READY）则强制断开重连 */
    private fun startHandshakeWatchdog() {
        cancelHandshakeWatchdog()
        handshakeWatchdogJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (isConnected.get()) return@launch
            SecureLog.w(TAG, "Handshake timeout: no READY within ${HANDSHAKE_TIMEOUT_MS}ms, forcing reconnect")
            val stale = webSocket
            webSocket = null
            stale?.cancel()
            cleanupConnectionState()
            scheduleReconnect()
        }
    }

    private fun cancelHandshakeWatchdog() {
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = null
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt.incrementAndGet()
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                SecureLog.e(TAG, "Reconnect gave up after $attempt attempts")
                onConnectionStateChange?.invoke(ConnectionState.AUTH_FAILED)
                return@launch
            }
            val delayMs = (attempt * ChatConstants.QQ_BOT_RECONNECT_BACKOFF_BASE_MS).coerceAtMost(ChatConstants.QQ_BOT_RECONNECT_MAX_DELAY_MS)
            SecureLog.i(TAG, "Scheduling reconnect in ${delayMs}ms (attempt $attempt)")
            onConnectionStateChange?.invoke(ConnectionState.RECONNECTING)
            delay(delayMs)
            if (isActive) {
                connect()
            }
        }
    }

    private fun reconnect() {
        disconnect()
        scheduleReconnect()
    }

    companion object {
        private const val TAG = "QQBotWS"
        private const val PROPERTY_OS = "\$os"
        private const val PROPERTY_BROWSER = "\$browser"
        private const val PROPERTY_DEVICE = "\$device"
        private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 41250L
        private const val MAX_RECONNECT_ATTEMPTS = 10

        // QQ 开放平台 intents：GROUP_AND_C2C_EVENT（群聊/单聊）、INTERACTION（互动/按钮回调）
        const val GROUP_AND_C2C_EVENT_INTENT = 1 shl 25
        const val INTERACTION_INTENT = 1 shl 26

        /** 握手超时：超出后视为死链（服务端不回 HELLO/READY），强制重连 */
        private const val HANDSHAKE_TIMEOUT_MS = 30_000L
    }
}
