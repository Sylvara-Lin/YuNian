package com.yunian.ai.feature.wechat.data

import android.content.Context
import android.util.Log
import com.yunian.ai.common.AppForegroundTracker
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatMediaRef
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.feature.wechat.data.model.M0
import com.yunian.ai.feature.wechat.data.model.M1Type
import com.yunian.ai.feature.wechat.WeChatDebugLog
import com.yunian.ai.feature.wechat.service.WeChatAiReplyWorker
import com.yunian.ai.feature.wechat.service.WeChatNotificationHelper
import com.yunian.ai.feature.wechat.service.WeChatServiceLocator
import com.yunian.ai.wechat.outbox.WeChatOutboxCoordinator
import com.yunian.ai.wechat.ilink.IlinkClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class WeChatMessageRepository(
    context: Context,
    private val sdkClientManager: IlinkClientManager,
    private val tokenStore: WeChatTokenStore
) {
    private val appContext = context.applicationContext

    private val _incomingMessages = MutableSharedFlow<M0>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: Flow<M0> = _incomingMessages.asSharedFlow()

    val accountFlow = tokenStore.accountFlow
    val isLoggedInFlow = tokenStore.accountFlow.map { it != null }
    suspend fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()

    suspend fun getQrCode(): Result<WeChatQrCode> = withContext(Dispatchers.IO) {
        runCatching {
            sdkClientManager.startLogin().let { qrCode ->
                WeChatQrCode(qrCode.statusToken, qrCode.displayContent)
            }
        }
    }

    suspend fun pollQrCodeStatus(qrCode: String): Result<A0> = withContext(Dispatchers.IO) {
        runCatching {
            sdkClientManager.pollLoginStatus().let { account ->
                A0(
                    botToken = account.botToken,
                    ilinkBotId = account.ilinkBotId,
                    ilinkUserId = account.ilinkUserId,
                    baseUrl = account.baseUrl,
                    accountId = account.accountId,
                )
            }
        }
    }

    suspend fun logout() {
        sdkClientManager.closeAndClear()
        WeChatServiceLocator.inboxCoordinator(appContext).cancelAll()
        com.yunian.ai.feature.wechat.service.WeChatChannelRuntime.reset()
    }

    fun destroy() {
    }

    suspend fun pollMessages(timeoutMs: Long = TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS): Result<WeChatPollResult> = withContext(Dispatchers.IO) {
        runCatching {
            val account = tokenStore.getAccount()
                ?: throw IllegalStateException("未登录微信")

            val rawMessages = sdkClientManager.getUpdates()
            val messages = rawMessages.map { WeChatSdkMessageMapper.toAppMessage(it) }
            if (messages.isNotEmpty()) {
                WeChatDebugLog.log("[Repo] getUpdates returned ${messages.size} messages")
            }

            try {
                if (messages.isNotEmpty()) {
                    messages.forEach { msg -> handleIncomingMessageFast(account, msg) }
                }
                sdkClientManager.commitUpdates()
            } catch (error: Exception) {
                sdkClientManager.resetToCommittedUpdates()
                throw error
            }

            runCatching { drainOutbox() }

            WeChatPollResult(messages)
        }
    }

    suspend fun sendTextMessage(
        toUserId: String,
        text: String,
        contextToken: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            tokenStore.getAccount()
                ?: throw IllegalStateException("未登录微信")
            sdkClientManager.sendText(toUserId, text, contextToken)
        }.mapFailure(::toUserFacingException)
    }

    suspend fun sendImageMessage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String? = null,
        contextToken: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            tokenStore.getAccount()
                ?: throw IllegalStateException("未登录微信")
            sdkClientManager.sendImage(toUserId, imageBytes, fileName, description, contextToken)
        }.mapFailure(::toUserFacingException)
    }

    suspend fun enqueueTextOutbound(
        companionId: Long,
        wechatUserId: String,
        text: String,
        contextToken: String? = null,
        sourceMessageId: Long? = null,
    ): String {
        val outbox = WeChatServiceLocator.outboxCoordinator(appContext)
        return outbox.enqueue(
            WeChatOutboundRequest(
                companionId = companionId,
                wechatUserId = wechatUserId,
                sourceMessageId = sourceMessageId,
                text = text,
                contextToken = contextToken,
            ),
            wechatUserId = wechatUserId,
        )
    }

    suspend fun enqueueImageOutbound(
        companionId: Long,
        wechatUserId: String,
        localPath: String,
        fileName: String? = null,
        description: String? = null,
        contextToken: String? = null,
        sourceMessageId: Long? = null,
    ): String {
        val outbox = WeChatServiceLocator.outboxCoordinator(appContext)
        return outbox.enqueue(
            WeChatOutboundRequest(
                companionId = companionId,
                wechatUserId = wechatUserId,
                sourceMessageId = sourceMessageId,
                media = WeChatMediaRef(
                    kind = WeChatContentKind.IMAGE,
                    localPath = localPath,
                    fileName = fileName,
                    description = description,
                ),
                contextToken = contextToken,
            ),
            wechatUserId = wechatUserId,
        )
    }

    suspend fun enqueueStickerOutbound(
        companionId: Long,
        wechatUserId: String,
        sticker: com.yunian.ai.common.StickerInfo,
        contextToken: String? = null,
        sourceMessageId: Long? = null,
    ): String? {
        val material = WeChatStickerMaterializer.materialize(appContext, sticker) ?: return null
        // 表情包的 description 是内部管理名（如「探头」），不发到微信聊天里；
        // AI 的文字回复已由 Bridge 单独发送，这里 description 必须置空。
        return enqueueImageOutbound(
            companionId = companionId,
            wechatUserId = wechatUserId,
            localPath = material.localPath,
            fileName = material.fileName,
            description = null,
            contextToken = contextToken,
            sourceMessageId = sourceMessageId,
        )
    }

    suspend fun drainOutbox(limit: Int = WeChatOutboxCoordinator.DEFAULT_DRAIN_LIMIT): Int {
        return WeChatServiceLocator.outboxCoordinator(appContext).drain(limit)
    }

    suspend fun getContextToken(userId: String): String? {
        val account = tokenStore.getAccount() ?: return null
        return tokenStore.getContextToken(account.accountId, userId)
    }

    fun extractText(message: M0): String {
        return extractInboundText(message).orEmpty()
    }

    private suspend fun handleIncomingMessageFast(account: A0, message: M0) {

        message.fromUserId?.let { userId ->
            message.contextToken?.let { token ->
                val existingToken = tokenStore.getContextToken(account.accountId, userId)
                if (existingToken != token) {
                    sdkClientManager.notifyContextTokenUpdated(userId, token)
                }
            }
        }

        _incomingMessages.tryEmit(message)

        val text = extractText(message)
        val isImageMessage = isImageMessage(message)

        // Release 可见的入站摘要（现网排障用，I 级）：只打元数据不打正文，用户 id 掩码。
        // 仅在收到非空消息列表时才会进入本方法（pollMessages 对空列表不会调用），空轮询不产生日志。
        Log.i(
            TAG,
            "inbound from=${maskWeChatUserId(message.fromUserId)} " +
                "type=${message.messageType} " +
                "hasText=${text.isNotBlank()} " +
                "hasToken=${!message.contextToken.isNullOrBlank()}",
        )

        if (isOutboundEcho(account, message)) {
            Log.d(TAG, "Skipping outbound echo from self")
            WeChatDebugLog.log("[Repo] Echo skipped from=${message.fromUserId}")
            return
        }

        if (text.isBlank() && !isImageMessage) {
            Log.d(TAG, "Skipping message: no text content and not an image")
            WeChatDebugLog.log("[Repo] Skipping empty message from=${message.fromUserId}")
            return
        }

        val inbound = M0WireAdapter.toInbound(message)
        if (inbound == null) {
            Log.w(TAG, "Failed to map M0 to inbound domain message")
            return
        }

        val inbox = WeChatServiceLocator.inboxCoordinator(appContext)

        val accepted = inbox.acceptIfNew(inbound) { acceptedInbound ->
            processAcceptedInbound(message, acceptedInbound.primaryText.orEmpty(), isImageMessage)
        }
        if (!accepted) {
            Log.d(TAG, "Duplicate inbound dropped: ${inbound.dedupeKey}")
            WeChatDebugLog.log("[Repo] Duplicate dropped key=${inbound.dedupeKey}")
        } else {
            WeChatDebugLog.log("[Repo] Accepted inbound from=${message.fromUserId} text_len=${text.length}")
        }
    }

    private suspend fun processAcceptedInbound(
        message: M0,
        text: String,
        isImageMessage: Boolean,
    ) {
        val notifyEnabled = tokenStore.getNotifyEnabled()
        val autoReplyEnabled = tokenStore.getAutoReply()
        val fromUserId = message.fromUserId ?: return

        if (isImageMessage) {
            if (notifyEnabled) {
                WeChatNotificationHelper.showIncomingMessageNotification(appContext, message)
            }
            if (!autoReplyEnabled || fromUserId.isBlank()) {
                Log.d(TAG, "Auto-reply disabled or userId blank for image from $fromUserId")
                return
            }
            Log.d(TAG, "Starting AI vision reply for image from $fromUserId (serial queue)")
            try {
                val bridge = WeChatServiceLocator.chatBridge(appContext)
                bridge.handleImageMessage(fromUserId, message)
            } catch (e: Exception) {
                Log.e(TAG, "AI vision reply failed for image", e)

                WeChatAiReplyWorker.enqueue(appContext, fromUserId, "[图片]")
            }
            return
        }

        val decision = WeChatIncomingMessagePolicy.evaluate(
            isAppInForeground = AppForegroundTracker.isInForeground,
            notifyEnabled = notifyEnabled,
            autoReplyEnabled = autoReplyEnabled,
            messageText = text,
        )

        if (decision.shouldNotify) {
            WeChatNotificationHelper.showIncomingMessageNotification(appContext, message)
        }

        if (!decision.shouldAutoReply || fromUserId.isBlank()) {
            WeChatDebugLog.log("[Repo] Auto-reply skipped: enabled=$autoReplyEnabled from=$fromUserId")
            return
        }

        Log.d(TAG, "Starting AI reply for $fromUserId (serial queue)")
        WeChatDebugLog.log("[Repo] Starting AI reply for $fromUserId text_len=${text.length}")
        try {
            val bridge = WeChatServiceLocator.chatBridge(appContext)
            val reply = bridge.handleTextMessage(fromUserId, text)
            WeChatDebugLog.log("[Repo] AI reply done for $fromUserId reply_len=${reply?.length ?: 0}")
        } catch (e: Exception) {
            Log.e(TAG, "Direct AI reply failed, falling back to Worker", e)
            WeChatDebugLog.log("[Repo] AI reply FAILED for $fromUserId: ${e.message}")
            WeChatAiReplyWorker.enqueue(appContext, fromUserId, text)
        }
    }

private fun isImageMessage(message: M0): Boolean {
        return message.messageType == M1Type.IMAGE.value ||
            message.itemList?.any { it.type == M1Type.IMAGE.value && it.imageItem != null } == true
    }

    private fun toUserFacingException(error: Throwable): Throwable {
        val message = error.message.orEmpty()
        return if (message.contains("missing latest context token", ignoreCase = true) ||
            message.contains("context_token is required", ignoreCase = true)
        ) {
            IllegalStateException("缺少 context_token，请先让对方发送消息", error)
        } else {
            error
        }
    }

    companion object {
        private const val TAG = "WeChatMsgRepo"
    }
}

data class WeChatPollResult(
    val messages: List<M0>
)

data class WeChatQrCode(
    val statusToken: String,
    val displayContent: String
)

/**
 * 判断一条 getUpdates 消息是否为 bot 自身的出站回声。
 *
 * 协议模型依据（参考 openclaw-weixin / wong2 weixin-agent-sdk 的 login-qr.ts 与 bot.ts）：
 * - 扫码登录 confirmed 返回的 ilink_user_id 是【扫码的微信用户】的 ID，即对话对端，
 *   NOT bot 自己；官方 bot.sendMessage 的发送目标正是这个 userId（与入站 from 同值）。
 * - ilink_bot_id 才是 bot 自身 ID。
 * - 用户在微信里给 bot 发消息时，getUpdates 收到的 from_user_id 恰好等于 ilink_user_id——
 *   这是正常入站消息，绝不能当回声过滤（官方 monitor.ts 处理入站时没有任何 echo 过滤）。
 * 因此这里只保留 from == ilink_bot_id 的保险分支（官方协议下 getUpdates 理论上不会
 * 回推 bot 自己的消息，保留此判断无害）。
 */
internal fun isOutboundEcho(account: A0, message: M0): Boolean {
    val from = message.fromUserId?.takeIf { it.isNotBlank() } ?: return false
    return account.ilinkBotId.isNotBlank() && from == account.ilinkBotId
}

/** 用户 ID 掩码：前 3 后 2，中间 ***；长度不足以安全掩码时整体打码，避免日志泄露完整 id */
internal fun maskWeChatUserId(userId: String?): String {
    val id = userId.orEmpty()
    if (id.length <= 6) return "***"
    return id.take(3) + "***" + id.takeLast(2)
}

private inline fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(transform(it)) }
    )
}
