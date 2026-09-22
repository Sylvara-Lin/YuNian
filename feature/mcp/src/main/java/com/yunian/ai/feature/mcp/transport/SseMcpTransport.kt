package com.yunian.ai.feature.mcp.transport

import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP SSE (Server-Sent Events) 传输实现（存根实现）。
 *
 * 注意：完整的 SSE 实现需要 OkHttp 的 EventSource 依赖 (com.squareup.okhttp3:okhttp-sse)。
 * 当前为存根实现，实际使用时需添加依赖并完善实现。
 */
class SseMcpTransport(
    private val config: com.yunian.ai.domain.McpServerConfig,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
) : McpTransport {

    private val requestIdGenerator = java.util.concurrent.atomic.AtomicInteger(1)
    private val pendingRequests = java.util.concurrent.ConcurrentHashMap<Any, CancellableContinuation<JsonRpcResponse>>()
    private val serverMessagesChannel = Channel<JsonRpcMessage>(capacity = 100)
    private val connectionState = kotlinx.coroutines.flow.MutableStateFlow<Boolean>(false)

    override val serverMessages: ReceiveChannel<JsonRpcMessage> = serverMessagesChannel
    override val isConnected: Boolean
        get() = connectionState.value

    override suspend fun connect(): Boolean {
        SecureLog.w("McpTransport", "SSE transport not fully implemented - requires okhttp-sse dependency")
        return false
    }

    override suspend fun disconnect() {
        connectionState.value = false
        pendingRequests.values.forEach { it.resumeWith(kotlin.Result.failure(Exception("Disconnected"))) }
        pendingRequests.clear()
        serverMessagesChannel.close()
    }

    override suspend fun sendRequest(request: JsonRpcRequest): JsonRpcResponse? {
        return null
    }

    override suspend fun sendNotification(notification: JsonRpcNotification) {
    }

    /** 列出工具（存根实现） */
    suspend fun listTools(): List<McpToolDefinition> = emptyList()

    /** 调用工具（存根实现） */
    suspend fun callTool(name: String, arguments: kotlinx.serialization.json.JsonObject?): McpCallToolResult {
        return McpCallToolResult(emptyList(), true)
    }
}