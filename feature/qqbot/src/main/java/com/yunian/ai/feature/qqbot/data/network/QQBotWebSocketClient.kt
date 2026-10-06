package com.yunian.ai.feature.qqbot.data.network

import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.feature.qqbot.QQBotDebugLog
import com.yunian.ai.feature.qqbot.data.QQBotTokenStore
import com.yunian.ai.feature.qqbot.data.model.QQGatewayPayload
import com.yunian.ai.feature.qqbot.data.model.QQHelloData
import com.yunian.ai.feature.qqbot.data.model.QQReadyData
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class QQBotWebSocketClient(
    private val tokenStore: QQBotTokenStore,
    private val apiClient: QQBotApiClient,
    private val onEvent: suspend (QQGatewayPayload) -> Unit,
    private val onConnectionStateChange: ((ConnectionState) -> Unit)? = null
) {
    // 连接状态枚举 ConnectionState 已迁到 ConnectionStateMachine.kt（顶层、纯逻辑、可 JVM 单测）。

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

    /** 状态 + 进入时间戳的唯一事实来源。所有状态迁移都必须经由它。 */
    private val stateMachine = ConnectionStateMachine { state ->
        onConnectionStateChange?.invoke(state)
    }

    /** 是否已有重连在途。用于 [ConnectionStateMachine.shouldForceRecovery] 判定。 */
    @Volatile
    private var reconnectPending = false

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
        if (stateMachine.isConnected() || stateMachine.isConnecting()) return
        stateMachine.onConnectStarted(System.currentTimeMillis())
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
                    // 三个回调必须一致排重连：旧实现唯独漏掉这里，服务端发起关闭后
                    // 客户端再也不会重连，状态永久停在 CONNECTING。
                    cleanupConnectionState()
                    scheduleReconnect()
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

            apiClient.clearApiCache()

            // 决策必须是纯逻辑且不依赖 scheduleReconnect() 发状态：后者在「重连 job
            // 已活跃」时会早退，旧实现因此让 attempt 0..4 期间完全没有状态迁移，
            // UI 停在 CONNECTING。这里先判定，再保证状态离开 CONNECTING。
            when (
                ConnectionRecoveryPolicy.onConnectFailed(
                    state = stateMachine.current,
                    attempt = reconnectAttempt.get() + 1,
                    retryPending = reconnectPending,
                    maxAttempts = MAX_RECONNECT_ATTEMPTS,
                )
            ) {
                ConnectFailureOutcome.GiveUp ->
                    stateMachine.onAuthFailed(System.currentTimeMillis())

                ConnectFailureOutcome.WaitForScheduledRetry ->
                    // 重连 job 已在跑：仍必须离开 CONNECTING，否则就是「永久连接中」。
                    stateMachine.onReconnectScheduled(System.currentTimeMillis())

                ConnectFailureOutcome.ScheduleRetry ->
                    scheduleReconnect()
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectPending = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        cancelHandshakeWatchdog()
        heartbeatActive = false
        webSocket?.close(1000, "manual disconnect")
        webSocket = null
        stateMachine.onDisconnected(System.currentTimeMillis())
    }

    /**
     * 是否已经卡在中间态（CONNECTING / RECONNECTING）超过阈值。
     *
     * 供 FGS 看门狗使用：旧看门狗无条件跳过 CONNECTING，而 [onClosing] 又会取消
     * 握手看门狗，两者叠加导致无人可救、UI 永久停在「连接中」。
     */
    fun isConnectionStuck(): Boolean =
        stateMachine.shouldForceRecovery(System.currentTimeMillis(), reconnectPending)

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
                    if (payload.t == READY_EVENT || payload.t == RESUMED_EVENT) {
                        // RESUMED（RESUME 成功）与 READY 都是「会话已建立」，必须走同一套收尾。
                        //
                        // 只认 READY 的后果（回归根因）：重启后走 RESUME 的分支永远不进入
                        // CONNECTED → 握手看门狗在 HANDSHAKE_TIMEOUT_MS(30s) 后判定死链并强制
                        // 断开重连 → 每个周期都收到 RESUMED 却仍停在中间态 → 每 30 秒无限重连；
                        // 且 reconnectAttempt 永不归零，最终撞上 MAX_RECONNECT_ATTEMPTS 永久放弃。
                        // 真机日志特征：[Repo] dispatch type=RESUMED 严格每 30 秒出现一次。
                        if (payload.t == READY_EVENT) {
                            payload.d?.let {
                                val ready = json.decodeFromJsonElement<QQReadyData>(it)
                                sessionId = ready.sessionId

                                scope.launch { tokenStore.setSessionId(ready.sessionId) }
                                SecureLog.i(TAG, "READY: sessionId=${ready.sessionId}, bot=${ready.user?.username}")
                            }
                        }

                        QQBotDebugLog.log("[WS] handshake settled by " + payload.t)
                        reconnectAttempt.set(0)
                        cancelHandshakeWatchdog()
                        stateMachine.onReady(System.currentTimeMillis())
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

                    // 这里原先在 attempt>=5 时直接 invoke(AUTH_FAILED)，但它紧接着就被
                    // reconnect() → disconnect() → DISCONNECTED 覆盖，用户从未看到过。
                    // 现在 AUTH_FAILED 只由状态机在重连预算真正耗尽时发出（终态）。
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
        if (stateMachine.isConnected()) return

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
            // Hello 后立即首发：协议约定（QQ 官方文档「鉴权成功之后就需要按照周期进行心跳发送」）
            // + AGENTS.md 教训 #1「心跳标志与 isConnected 解耦，Hello 后立即发」。
            // 首发前调 onBeforeSend：pendingSentAtMs=0 走 else 分支只记基线，与循环内语义对齐；
            // 不会误触发重连（HeartbeatAckTrackerTest.first heartbeat never triggers reconnect even without ack 已覆盖）。
            if (heartbeatAckTracker.onBeforeSend(System.currentTimeMillis(), lastHeartbeatAckMs)) {
                reconnect()
                return@launch
            }
            send(json.encodeToString(QQGatewayPayload(op = opHeartbeat, d = JsonPrimitive(lastSequence.get()))))

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
        heartbeatActive = false
        lastHeartbeatAckMs = 0L
        heartbeatAckTracker.reset()
        heartbeatJob?.cancel()
        heartbeatJob = null
        cancelHandshakeWatchdog()
        // 旧实现是 `if (wasConnected) { invoke(DISCONNECTED) }`：卡在 CONNECTING 时
        // （wasConnected=false）连状态回调都不发。现在状态迁移交给状态机，
        // 中间态不会被静默吞掉——随后的 scheduleReconnect() 必然把它推出去。
        stateMachine.onTransportLost(System.currentTimeMillis())
    }

    /** 握手看门狗：onOpen 后 HANDSHAKE_TIMEOUT_MS 内未进入 CONNECTED（收到 READY）则强制断开重连 */
    private fun startHandshakeWatchdog() {
        cancelHandshakeWatchdog()
        handshakeWatchdogJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (stateMachine.isConnected()) return@launch
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
        if (reconnectPending) return
        reconnectPending = true
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt.incrementAndGet()
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                SecureLog.e(TAG, "Reconnect gave up after $attempt attempts")
                stateMachine.onAuthFailed(System.currentTimeMillis())
                reconnectPending = false
                return@launch
            }
            val delayMs = ConnectionRecoveryPolicy.backoffMs(
                attempt = attempt,
                baseMs = ChatConstants.QQ_BOT_RECONNECT_BACKOFF_BASE_MS,
                maxDelayMs = ChatConstants.QQ_BOT_RECONNECT_MAX_DELAY_MS,
            )
            SecureLog.i(TAG, "Scheduling reconnect in ${delayMs}ms (attempt $attempt)")
            // 进入 RECONNECTING：终态之外的状态一律不得长期保持，故必然有后续事件。
            stateMachine.onReconnectScheduled(System.currentTimeMillis())
            try {
                delay(delayMs)
            } catch (cancelled: CancellationException) {
                reconnectPending = false
                throw cancelled
            }
            // 必须在 connect() **之前**清除：connect() 失败时会再次调用
            // scheduleReconnect()，若此时 reconnectPending 仍为 true 就会被判定为
            // 「已有重连在途」而不再排程，导致退避循环只跑一轮就永久卡在 RECONNECTING。
            reconnectPending = false
            connect()
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

        /** 握手成功事件：READY（全新会话）与 RESUMED（会话恢复）同等意义。 */
        private const val READY_EVENT = "READY"
        private const val RESUMED_EVENT = "RESUMED"
    }
}
