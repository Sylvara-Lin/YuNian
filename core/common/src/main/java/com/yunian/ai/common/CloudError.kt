package com.yunian.ai.common

import org.json.JSONObject

data class CloudError(

    val code: String,

    val message: String?,

    val type: String? = null
) {
    companion object {

        const val CLOUD_SERVICE_DISABLED = "cloud_service_disabled"

        const val APP_KEY_MISMATCH = "app_key_mismatch"

        const val BUILTIN_ACCESS_DENIED = "builtin_cloud_access_denied"
        const val NETWORK_ERROR = "network_error"

        fun parse(body: String?, statusCode: Int = 0): CloudError? {
            if (body.isNullOrBlank()) return null
            return try {
                val json = JSONObject(body)

                json.optJSONObject("error")?.let { err ->
                    val code = err.optString("code").ifEmpty { err.optString("type") }
                    val message = err.optString("message").ifEmpty { null }
                    if (code.isNotBlank()) {
                        return CloudError(
                            code = code,
                            message = message,
                            type = err.optString("type").ifEmpty { null }
                        )
                    }
                }

                if (!json.optBoolean("ok", true)) {
                    val code = json.optString("error").ifEmpty { return null }
                    val message = json.optString("message").ifEmpty { null }
                    return CloudError(code = code, message = message)
                }

                null
            } catch (_: Exception) {
                null
            }
        }

        fun parseErrorStream(connection: java.net.HttpURLConnection, statusCode: Int): CloudError? {
            val body = try {
                connection.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            return parse(body, statusCode)
        }
    }

    fun friendlyMessage(): String = when (code) {
        CLOUD_SERVICE_DISABLED -> "云端服务尚未开启"
        APP_KEY_MISMATCH -> "应用凭证校验失败"
        BUILTIN_ACCESS_DENIED -> "云端访问已被安全策略禁用"
        NETWORK_ERROR -> "网络连接失败"
        else -> message?.takeIf { it.isNotBlank() } ?: "请求失败（$code）"
    }

    val isCloudServiceDisabled: Boolean get() = code == CLOUD_SERVICE_DISABLED
}
