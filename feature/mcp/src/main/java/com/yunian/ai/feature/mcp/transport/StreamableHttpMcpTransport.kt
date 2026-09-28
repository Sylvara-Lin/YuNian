package com.yunian.ai.feature.mcp.transport

import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP Streamable HTTP 传输实现（存根实现）。
 *
 * 基于 MCP 2025-06-18 规范的 Streamable HTTP 传输。
 * 当前为存根实现，完整实现需要完善 SSE 解析和会话管理。
 */
class StreamableHttpMcpTransport(
    private val config: com.yunian.ai.domain.McpServerConfig,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : McpTransport {

    private val requestIdGenerator = java.util.concurrent.atomic.AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Any, CancellableContinuation<JsonRpcResponse>>()
    private val serverMessagesChannel = Channel<JsonRpcMessage>(capacity = 100)
    private val connectionState = kotlinx.coroutines.flow.MutableStateFlow<Boolean>(false)
    private val sessionIdRef = AtomicReference<String?>(null)

    override val serverMessages: ReceiveChannel<JsonRpcMessage> = serverMessagesChannel
    override val isConnected: Boolean
        get() = connectionState.value

    override suspend fun connect(): Boolean {
        SecureLog.w("McpTransport", "Streamable HTTP transport not fully implemented")
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