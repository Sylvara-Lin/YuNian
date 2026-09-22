package com.yunian.ai.wechat

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatFailureReason
import com.yunian.ai.domain.wechat.WeChatOutboundPort
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.feature.wechat.data.WeChatStickerMaterializer
import com.yunian.ai.feature.wechat.service.WeChatServiceLocator
import com.yunian.ai.wechat.map.WeChatContentCleaner

class WeChatOutboundPortImpl(
    private val appContext: Context,
) : WeChatOutboundPort {

    override suspend fun enqueue(request: WeChatOutboundRequest): String {
        val companionId = request.companionId
        if (companionId <= 0L) return SKIPPED

        val tokenStore = WeChatServiceLocator.tokenStore(appContext)
        if (!tokenStore.isLoggedIn()) {
            SecureLog.i(TAG, "skip enqueue: not logged in companionId=$companionId")
            return SKIPPED
        }
        if (!tokenStore.getForwardEnabled()) {
            SecureLog.i(TAG, "skip enqueue: forward disabled companionId=$companionId")
            return SKIPPED
        }

        // 图片（生图 / 表情包）走 media 通道：content 是保留标签「[图片]」，
        // 若当成文本清洗会被 isStickerContent 判定为表情包名再查表失败 → 整条丢弃。
        val imageMedia = request.media?.takeIf { it.kind == WeChatContentKind.IMAGE }
        val content = if (imageMedia != null) null else resolveContent(request)
        if (imageMedia == null && content.isNullOrBlank()) {
            SecureLog.i(TAG, "skip enqueue: empty content companionId=$companionId")
            return SKIPPED
        }
        if (imageMedia != null && imageMedia.localPath.isNullOrBlank()) {
            SecureLog.w(TAG, "skip enqueue: image media without localPath companionId=$companionId")
            return SKIPPED
        }

        val wechatUserIds = resolveUserIds(request, tokenStore)
        if (wechatUserIds.isEmpty()) {
            SecureLog.i(
                TAG,
                "skip enqueue reason=${WeChatFailureReason.MAPPING_MISSING.wireName} companionId=$companionId",
            )
            return SKIPPED
        }

        val repository = WeChatServiceLocator.messageRepository(appContext)
        val account = tokenStore.getAccount()
        val isSticker = content != null && WeChatContentCleaner.isStickerContent(content)
        var lastRootId = SKIPPED
        var enqueuedAny = false

        for (wechatUserId in wechatUserIds) {
            val contextToken = request.contextToken
                ?: account?.let { tokenStore.getContextToken(it.accountId, wechatUserId) }
            if (imageMedia != null) {
                // 生图：直接把本地文件排进出站队列（WeChatOutboxCoordinator 支持 IMAGE kind）
                val localPath = imageMedia.localPath ?: continue
                val rootId = repository.enqueueImageOutbound(
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    localPath = localPath,
                    fileName = imageMedia.fileName,
                    description = null,
                    contextToken = contextToken,
                    sourceMessageId = request.sourceMessageId,
                )
                lastRootId = rootId
                enqueuedAny = true
                SecureLog.i(
                    TAG,
                    "image enqueued rootId=$rootId user=${mask(wechatUserId)} companionId=$companionId",
                )
            } else if (isSticker) {
                val rootId = enqueueSticker(
                    repository = repository,
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    content = content ?: continue,
                    contextToken = contextToken,
                    sourceMessageId = request.sourceMessageId,
                )
                if (rootId != null) {
                    lastRootId = rootId
                    enqueuedAny = true
                }
            } else {
                val cleaned = WeChatContentCleaner.clean(content ?: continue)
                if (cleaned.isBlank()) {
                    SecureLog.i(TAG, "skip blank after clean user=${mask(wechatUserId)}")
                    continue
                }
                lastRootId = repository.enqueueTextOutbound(
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    text = cleaned,
                    contextToken = contextToken,
                    sourceMessageId = request.sourceMessageId,
                )
                enqueuedAny = true
                SecureLog.i(
                    TAG,
                    "enqueued rootId=$lastRootId user=${mask(wechatUserId)} companionId=$companionId",
                )
            }
        }

        if (enqueuedAny) {
            val sent = runCatching { repository.drainOutbox() }.getOrDefault(0)
            SecureLog.i(TAG, "drain after enqueue sent=$sent")
        }
        return lastRootId
    }

    private suspend fun resolveContent(request: WeChatOutboundRequest): String? {
        if (!request.text.isNullOrBlank()) return request.text
        val messageId = request.sourceMessageId ?: return null
        if (messageId <= 0L) return null
        val msg = AppDatabase.getDatabase(appContext).messageDao().getMessageById(messageId)
        return msg?.body?.content
    }

    private suspend fun resolveUserIds(
        request: WeChatOutboundRequest,
        tokenStore: com.yunian.ai.feature.wechat.data.WeChatTokenStore,
    ): List<String> {
        val explicit = request.wechatUserId?.trim().orEmpty()
        if (explicit.isNotEmpty()) return listOf(explicit)
        return tokenStore.getWechatUserIdsForCompanionId(request.companionId)
    }

    private suspend fun enqueueSticker(
        repository: com.yunian.ai.feature.wechat.data.WeChatMessageRepository,
        companionId: Long,
        wechatUserId: String,
        content: String,
        contextToken: String?,
        sourceMessageId: Long?,
    ): String? {
        val stickerName = WeChatContentCleaner.extractStickerName(content) ?: run {
            SecureLog.w(TAG, "invalid sticker format")
            return null
        }
        val material = WeChatStickerMaterializer.materializeByName(appContext, stickerName)
        if (material == null) {
            // 修 P9：物化失败（表情已被删除等）不静默丢弃 —— 降级为文本入队。
            // 文本取 AI 原文去标签后的残余文案；为空则用固定占位文案，保证「能发则发、不能发降级文字」。
            SecureLog.w(
                TAG,
                "sticker materialize failed, fallback to text name=$stickerName user=${mask(wechatUserId)} companionId=$companionId",
            )
            val residual = content.replace("[$stickerName]", "").trim()
            val fallbackText = residual.ifBlank { "[发了一个表情]" }
            val fallbackRootId = repository.enqueueTextOutbound(
                companionId = companionId,
                wechatUserId = wechatUserId,
                text = fallbackText,
                contextToken = contextToken,
                sourceMessageId = sourceMessageId,
            )
            SecureLog.i(
                TAG,
                "sticker fallback text enqueued rootId=$fallbackRootId user=${mask(wechatUserId)}",
            )
            return fallbackRootId
        }
        val rootId = repository.enqueueImageOutbound(
            companionId = companionId,
            wechatUserId = wechatUserId,
            localPath = material.localPath,
            fileName = material.fileName,
            description = material.description,
            contextToken = contextToken,
            sourceMessageId = sourceMessageId,
        )
        SecureLog.d(
            TAG,
            "sticker enqueued rootId=$rootId user=${mask(wechatUserId)} name=$stickerName",
        )
        return rootId
    }

    companion object {
        private const val TAG = "WeChatOutboundPort"
        private const val SKIPPED = ""

        private fun mask(userId: String): String {
            if (userId.length <= 6) return "***"
            return userId.take(3) + "***" + userId.takeLast(2)
        }
    }
}
