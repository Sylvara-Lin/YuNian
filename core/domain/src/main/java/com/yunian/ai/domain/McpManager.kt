package com.yunian.ai.domain

import kotlinx.serialization.json.JsonObject

data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val transportType: TransportType,
    val headers: Map<String, String> = emptyMap(),
    val enabledTools: List<String> = emptyList(),
    val disabledTools: List<String> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class TransportType {
    SSE,
    STREAMABLE_HTTP
}

data class McpTool(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val needsApproval: Boolean = false
)

data class McpServerStatus(
    val config: McpServerConfig,
    val connected: Boolean,
    val tools: List<McpTool>,
    val lastError: String? = null,
    val lastConnectedAt: Long? = null
)

interface McpManager {

    suspend fun getServerStatuses(): List<McpServerStatus>

    suspend fun getAvailableTools(): List<McpTool>

    suspend fun upsertServer(config: McpServerConfig): Boolean

    suspend fun removeServer(serverId: String): Boolean

    suspend fun setServerEnabled(serverId: String, enabled: Boolean): Boolean

    suspend fun callTool(serverId: String, toolName: String, argumentsJson: String): String

    suspend fun connectAll(): List<McpServerStatus>

    suspend fun disconnectAll()

    suspend fun refreshServerTools(serverId: String): List<McpTool>
}

interface McpManagerListener {
    fun onServerStatusChanged(status: McpServerStatus)
    fun onToolListChanged(serverId: String, tools: List<McpTool>)
    fun onError(serverId: String, error: String)
}
