package com.yunian.ai.feature.coffee.data

import com.yunian.ai.common.TimeoutBudgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LuckinMcpClient(
    private val client: OkHttpClient = defaultClient
) {
    companion object {
        private const val MCP_URL = "https://gwmcp.lkcoffee.com/order/user/mcp"
        private const val JSON_RPC = "2.0"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TimeoutBudgets.MCP_CONNECT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TimeoutBudgets.MCP_READ_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TimeoutBudgets.MCP_WRITE_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private var sessionToken: String? = null
    private var sessionId: String? = null
    private val initialized = AtomicBoolean(false)
    private val initMutex = Mutex()
    private var requestId = 1

    suspend fun callTool(
        token: String,
        toolName: String,
        arguments: JsonObject
    ): JsonElement = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Token 不能为空" }

        ensureInitialized(token)

        val request = buildToolCallRequest(token, toolName, arguments)
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            response.close()

            if (response.code == 401 || response.code == 403) {
                initialized.set(false)
                sessionId = null
            }
            throw McpException(
                "MCP 请求失败: HTTP ${response.code}",
                isAuthError = response.code == 401 || response.code == 403
            )
        }

        val textPayload = parseMcpTextPayload(response)

        extractBusinessData(textPayload)
    }

    private suspend fun ensureInitialized(token: String) {
        if (initialized.get() && sessionToken == token && sessionId != null) return

        initMutex.withLock {
            if (initialized.get() && sessionToken == token && sessionId != null) return

            doInitialize(token)
        }
    }

    private suspend fun doInitialize(token: String) {
        val initRequest = buildJsonObject {
            put("jsonrpc", JSON_RPC)
            put("method", "initialize")
            put("id", requestId++)
            put("params", buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "YuNian")
                    put("version", "1.0.0")
                })
            })
        }

        val initPayload = json.encodeToString(JsonObject.serializer(), initRequest)
        val request = Request.Builder()
            .url(MCP_URL)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .post(initPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            response.body?.string()
            response.close()
            throw McpException(
                "MCP 初始化失败: HTTP ${response.code}",
                isAuthError = response.code == 401 || response.code == 403
            )
        }

        sessionId = response.header("Mcp-Session-Id")
        sessionToken = token

        readResponseBody(response)

        val notifyRequest = buildJsonObject {
            put("jsonrpc", JSON_RPC)
            put("method", "notifications/initialized")
        }

        val notifyPayload = json.encodeToString(JsonObject.serializer(), notifyRequest)
        val notifyReq = Request.Builder()
            .url(MCP_URL)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(notifyPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val notifyResponse = client.newCall(notifyReq).execute()
        notifyResponse.close()

        initialized.set(true)
    }

    private fun buildToolCallRequest(
        token: String,
        toolName: String,
        arguments: JsonObject
    ): Request {
        val rpcRequest = buildJsonObject {
            put("jsonrpc", JSON_RPC)
            put("method", "tools/call")
            put("id", requestId++)
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", arguments)
            })
        }

        val payload = json.encodeToString(JsonObject.serializer(), rpcRequest)

        return Request.Builder()
            .url(MCP_URL)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun parseMcpTextPayload(response: Response): String {
        val rawText = readResponseBody(response)

        if (rawText.isBlank()) {
            throw IOException("MCP 响应为空")
        }

        val rpcResponse = try {
            json.parseToJsonElement(rawText).jsonObject
        } catch (e: Exception) {
            throw IOException("MCP 响应解析失败: ${e.message}\n原始内容: $rawText", e)
        }

        val error = rpcResponse["error"] as? JsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"

            if (code == -32001 || message.contains("session", ignoreCase = true)) {
                initialized.set(false)
                sessionId = null
            }
            throw McpException(
                "MCP 错误[$code]: $message",
                isAuthError = code == -32001 ||
                    message.contains("oauth", ignoreCase = true) ||
                    message.contains("token", ignoreCase = true)
            )
        }

        val result = rpcResponse["result"] as? JsonObject
            ?: throw IOException("MCP 响应缺少 result 字段")

        val isError = result["isError"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false

        val contentArray = result["content"]
        val textContent = if (contentArray is JsonArray) {
            contentArray.firstOrNull()
                ?.let { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        } else {
            null
        }

        if (isError) {
            throw McpException(textContent ?: "MCP 工具执行出错")
        }

        return textContent ?: throw IOException("MCP 响应缺少 content[0].text")
    }

    private fun extractBusinessData(textPayload: String): JsonElement {
        val envelope = try {
            json.parseToJsonElement(textPayload).jsonObject
        } catch (e: Exception) {
            throw IOException("瑞幸业务响应解析失败: ${e.message}\n原始: $textPayload", e)
        }

        val code = envelope["code"]?.jsonPrimitive?.intOrNull
            ?: envelope["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val msg = envelope["msg"]?.jsonPrimitive?.contentOrNull
        val success = envelope["success"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: (code == 0)

        if (code != null && code != 0 || !success) {
            val message = msg?.takeIf { it.isNotBlank() } ?: "瑞幸接口返回错误 (code=$code)"
            throw McpException(
                message,
                isAuthError = message.contains("token", ignoreCase = true) ||
                    message.contains("登录", ignoreCase = true) ||
                    message.contains("授权", ignoreCase = true)
            )
        }

        return envelope["data"]
            ?: throw IOException("瑞幸业务响应缺少 data 字段: $textPayload")
    }

    private suspend fun readResponseBody(response: Response): String {
        val contentType = response.header("Content-Type").orEmpty()
        val body = response.body ?: throw IOException("响应体为空")

        val rawText = if (contentType.contains("text/event-stream", ignoreCase = true)) {

            withTimeout(TimeoutBudgets.MCP_SSE_READ_MS) {
                readSseStream(body.byteStream())
            }
        } else {
            body.string()
        }
        response.close()
        return rawText
    }

    private fun readSseStream(inputStream: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line!!.startsWith("data:")) {
                sb.append(line!!.removePrefix("data:").trim())
            }
        }
        reader.close()
        return sb.toString()
    }
}

class McpException(message: String, val isAuthError: Boolean = false) : IOException(message)
