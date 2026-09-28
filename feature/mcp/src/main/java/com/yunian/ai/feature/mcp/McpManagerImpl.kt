package com.yunian.ai.feature.mcp

import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.domain.McpManager
import com.yunian.ai.domain.McpServerConfig
import com.yunian.ai.domain.McpServerStatus
import com.yunian.ai.domain.McpTool
import com.yunian.ai.domain.TransportType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class McpManagerImpl(
    private val appSettings: com.yunian.ai.common.AppSettingsStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
) : McpManager {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttpClient }
        install(ContentNegotiation) {
            json(Json { isLenient = true })
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private class McpSession(val config: McpServerConfig) {
        @Volatile var client: Client? = null
        val mutex = Mutex()
    }

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val _serverStatuses = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val serverStatuses: StateFlow<Map<String, McpServerStatus>> = _serverStatuses.asStateFlow()

    override suspend fun getServerStatuses(): List<McpServerStatus> =
        _serverStatuses.value.values.toList()

    override suspend fun getAvailableTools(): List<McpTool> =
        _serverStatuses.value.values.flatMap { it.tools }

    override suspend fun upsertServer(config: McpServerConfig): Boolean {
        sessions[config.id] = McpSession(config)

        scope.launch { connectServer(config) }
        return true
    }

    override suspend fun removeServer(serverId: String): Boolean {
        val session = sessions.remove(serverId)
        session?.client?.let { client ->
            runCatching { client.close() }
                .onFailure { SecureLog.w("McpManager", "Failed to close MCP client $serverId: ${it.message}") }
        }
        updateStatus(serverId) { it.copy(connected = false, tools = emptyList()) }
        return true
    }

    override suspend fun setServerEnabled(serverId: String, enabled: Boolean): Boolean {
        val config = sessions[serverId]?.config ?: return false
        if (enabled) {
            scope.launch { connectServer(config) }
        } else {
            removeServer(serverId)
        }
        return true
    }

    override suspend fun callTool(serverId: String, toolName: String, argumentsJson: String): String {
        val session = sessions[serverId]
            ?: return """{"ok":false,"error":"MCP session not found: $serverId"}"""
        val client = session.client
            ?: return """{"ok":false,"error":"MCP client $serverId is not connected"}"""

        val args: JsonObject = try {
            json.parseToJsonElement(argumentsJson).jsonObject
        } catch (e: Exception) {
            return """{"ok":false,"error":"Invalid arguments JSON: ${e.message}"}"""
        }

        return withContext(Dispatchers.IO) {
            try {
                val result: CallToolResult = client.callTool(
                    request = CallToolRequest(
                        params = CallToolRequestParams(name = toolName, arguments = args)
                    )
                )
                val isError = result.isError == true
                val textParts = result.content.mapNotNull { content ->
                    when (content) {
                        is TextContent -> content.text
                        else -> null
                    }
                }
                buildJsonObject {
                    put("ok", JsonPrimitive(!isError))
                    if (isError) put("error", JsonPrimitive(textParts.joinToString("\n")))
                    else put("content", JsonPrimitive(textParts.joinToString("\n")))
                }.toString()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("McpManager", "callTool $toolName failed", e)
                """{"ok":false,"error":"${e.message ?: e.javaClass.simpleName}"}"""
            }
        }
    }

    override suspend fun connectAll(): List<McpServerStatus> {
        val configs = sessions.values.map { it.config }
        configs.forEach { config -> connectServer(config) }
        return getServerStatuses()
    }

    override suspend fun disconnectAll() {
        sessions.keys.toList().forEach { removeServer(it) }
    }

    override suspend fun refreshServerTools(serverId: String): List<McpTool> {
        val config = sessions[serverId]?.config ?: return emptyList()
        connectServer(config)
        return _serverStatuses.value[serverId]?.tools ?: emptyList()
    }

    private suspend fun connectServer(config: McpServerConfig) {
        val session = sessions.computeIfAbsent(config.id) { McpSession(config) }
        session.mutex.withLock {

            session.client?.let { runCatching { it.close() } }
            session.client = null
            updateStatus(config.id) { it.copy(connected = false, lastError = null) }

            val sdkClient = Client(clientInfo = Implementation(name = "YuNian", version = "1.0.0"))
            val transport = createTransport(config)

            try {
                sdkClient.connect(transport)

                val toolList = sdkClient.listTools()
                val mcpTools = toolList.tools.map { tool ->
                    McpTool(
                        serverId = config.id,
                        name = tool.name,
                        description = tool.description ?: tool.name,
                        inputSchema = tool.inputSchema.properties ?: JsonObject(emptyMap()),
                        needsApproval = false
                    )
                }

                session.client = sdkClient
                updateStatus(config.id) { it.copy(connected = true, tools = mcpTools) }
                SecureLog.i("McpManager", "Connected ${config.name}: ${mcpTools.size} tools")
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { sdkClient.close() }
                throw e
            } catch (e: Exception) {
                runCatching { sdkClient.close() }
                SecureLog.e("McpManager", "Failed to connect ${config.name}", e)
                updateStatus(config.id) {
                    it.copy(connected = false, tools = emptyList(), lastError = e.message ?: "Connection failed")
                }
            }
        }
    }

    private fun createTransport(config: McpServerConfig) = when (config.transportType) {
        TransportType.SSE -> SseClientTransport(
            urlString = config.url,
            client = httpClient,
            requestBuilder = {
                config.headers.forEach { (name, value) -> headers.append(name, value) }
            }
        )
        TransportType.STREAMABLE_HTTP -> StreamableHttpClientTransport(
            url = config.url,
            client = httpClient,
            requestBuilder = {
                config.headers.forEach { (name, value) -> headers.append(name, value) }
            }
        )
    }

    private inline fun updateStatus(serverId: String, update: (McpServerStatus) -> McpServerStatus) {
        val config = sessions[serverId]?.config
            ?: _serverStatuses.value[serverId]?.config
            ?: return
        val current = _serverStatuses.value[serverId] ?: McpServerStatus(
            config = config, connected = false, tools = emptyList()
        )
        _serverStatuses.value = _serverStatuses.value + (serverId to update(current))
    }
}
