package com.yunian.ai.feature.mcp.transport

import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.Response

interface McpTransport {

    suspend fun connect(): Boolean

    suspend fun disconnect()

    suspend fun sendRequest(request: JsonRpcRequest): JsonRpcResponse?

    suspend fun sendNotification(notification: JsonRpcNotification)

    val serverMessages: ReceiveChannel<JsonRpcMessage>

    val isConnected: Boolean
}

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any,
    val method: String,
    val params: Any? = null
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any,
    val result: Any? = null,
    val error: JsonRpcError? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Any? = null
)

sealed interface JsonRpcMessage {
    data class Request(val request: JsonRpcRequest) : JsonRpcMessage
    data class Response(val response: JsonRpcResponse) : JsonRpcMessage
    data class Notification(val notification: JsonRpcNotification) : JsonRpcMessage

    data class Unknown(val data: String) : JsonRpcMessage
}

data class McpInitializeParams(
    val protocolVersion: String = "2025-06-18",
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpClientInfo = McpClientInfo()
)

data class McpClientCapabilities(
    val roots: McpRootsCapability? = null,
    val sampling: McpSamplingCapability? = null
)

data class McpRootsCapability(
    val listChanged: Boolean = true
)

data class McpSamplingCapability(
    val dummy: String = ""
)

data class McpClientInfo(
    val name: String = "YuNian",
    val version: String = "1.0.0"
)

data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpServerInfo
)

data class McpServerCapabilities(
    val tools: McpToolsCapability? = null,
    val resources: McpResourcesCapability? = null,
    val prompts: McpPromptsCapability? = null,
    val logging: McpLoggingCapability? = null
)

data class McpToolsCapability(
    val listChanged: Boolean = true
)

data class McpResourcesCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = true
)

data class McpPromptsCapability(
    val listChanged: Boolean = true
)

data class McpLoggingCapability(
    val dummy: String = ""
)

data class McpServerInfo(
    val name: String,
    val version: String
)

data class McpListToolsParams(
    val cursor: String? = null
)

data class McpListToolsResult(
    val tools: List<McpToolDefinition>,
    val nextCursor: String? = null
)

data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: kotlinx.serialization.json.JsonObject
)

data class McpCallToolParams(
    val name: String,
    val arguments: kotlinx.serialization.json.JsonObject? = null
)

data class McpCallToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false
)

sealed interface McpContent {
    data class Text(val type: String = "text", val text: String) : McpContent
    data class Image(val type: String = "image", val data: String, val mimeType: String) : McpContent
    data class Resource(val type: String = "resource", val resource: McpResource) : McpContent
}

data class McpResource(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)
