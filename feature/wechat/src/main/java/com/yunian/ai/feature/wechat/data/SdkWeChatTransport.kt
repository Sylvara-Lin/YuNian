package com.yunian.ai.feature.wechat.data

import com.yunian.ai.wechat.transport.WeChatTransportPort
import com.yunian.ai.wechat.ilink.IlinkClientManager

class SdkWeChatTransport(
    private val sdkClientManager: IlinkClientManager,
    private val tokenStore: WeChatTokenStore,
) : WeChatTransportPort {

    override suspend fun sendText(
        toUserId: String,
        text: String,
        contextToken: String?,
    ): Result<Unit> {
        tokenStore.getAccount() ?: return Result.failure(IllegalStateException(NOT_LOGGED_IN))
        return runCatching { sdkClientManager.sendText(toUserId, text, contextToken) }
    }

    override suspend fun sendTextSegments(
        toUserId: String,
        segments: List<String>,
        contextToken: String?,
    ): Result<Unit> {
        tokenStore.getAccount() ?: return Result.failure(IllegalStateException(NOT_LOGGED_IN))
        return runCatching { sdkClientManager.sendTextSegments(toUserId, segments, contextToken) }
    }

    override suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String?,
        contextToken: String?,
    ): Result<Unit> {
        tokenStore.getAccount() ?: return Result.failure(IllegalStateException(NOT_LOGGED_IN))
        return runCatching {
            sdkClientManager.sendImage(toUserId, imageBytes, fileName, description, contextToken)
        }
    }

    private companion object {
        const val NOT_LOGGED_IN = "未登录微信，无法发送"
    }
}
