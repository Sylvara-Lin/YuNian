package com.yunian.ai.wechat.transport

interface WeChatTransportPort {
    suspend fun sendText(
        toUserId: String,
        text: String,
        contextToken: String? = null,
    ): Result<Unit>

    suspend fun sendTextSegments(
        toUserId: String,
        segments: List<String>,
        contextToken: String? = null,
    ): Result<Unit> = sendText(toUserId, segments.joinToString(""), contextToken)

    suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String? = null,
        contextToken: String? = null,
    ): Result<Unit>
}
