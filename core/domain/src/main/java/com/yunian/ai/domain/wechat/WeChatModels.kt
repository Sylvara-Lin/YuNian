package com.yunian.ai.domain.wechat

data class WeChatCdnMediaRef(
    val encryptQueryParam: String? = null,
    val aesKey: String? = null,
)

data class WeChatMediaRef(
    val kind: WeChatContentKind,
    val localPath: String? = null,
    val fileName: String? = null,
    val description: String? = null,
    val cdn: WeChatCdnMediaRef? = null,
    val thumbCdn: WeChatCdnMediaRef? = null,
    val byteSize: Long? = null,
)

data class WeChatContentPart(
    val kind: WeChatContentKind,
    val text: String? = null,
    val media: WeChatMediaRef? = null,
)

data class WeChatInboundMessage(
    val dedupeKey: String,
    val messageId: Long? = null,
    val seq: Long? = null,
    val fromUserId: String,
    val toUserId: String? = null,
    val createTimeMs: Long? = null,
    val sessionId: String? = null,
    val direction: WeChatMessageDirection = WeChatMessageDirection.INBOUND,

    val protocolMessageType: Int? = null,
    val contextToken: String? = null,
    val parts: List<WeChatContentPart> = emptyList(),
) {
    val primaryKind: WeChatContentKind
        get() = parts.firstOrNull()?.kind ?: WeChatContentKind.UNKNOWN

    val primaryText: String?
        get() = parts.firstNotNullOfOrNull { it.text?.takeIf { t -> t.isNotBlank() } }

    val hasImage: Boolean
        get() = parts.any { it.kind == WeChatContentKind.IMAGE }

    val isAiDialogueCandidate: Boolean
        get() = WeChatAppTypeAlignment.isSupportedByAiDialogue(primaryKind) ||
            (primaryText != null) ||
            hasImage
}

data class WeChatOutboundRequest(
    val companionId: Long,
    val wechatUserId: String? = null,
    val sourceMessageId: Long? = null,
    val text: String? = null,
    val media: WeChatMediaRef? = null,
    val contextToken: String? = null,
    val priority: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class WeChatOutboundSegment(
    val outboxId: String,
    val wechatUserId: String,
    val kind: WeChatContentKind,
    val text: String? = null,
    val media: WeChatMediaRef? = null,
    val segmentIndex: Int,
    val segmentCount: Int,
    val contextToken: String? = null,
    val status: WeChatDeliveryStatus = WeChatDeliveryStatus.PENDING,
    val retryCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null,
)

data class WeChatAccount(
    val accountId: String,
    val ilinkBotId: String,
    val ilinkUserId: String,
    val baseUrl: String,

    val hasBotToken: Boolean,
)

data class WeChatUserMapping(
    val wechatUserId: String,
    val companionId: Long,
    val accountId: String? = null,
    val updatedAtMs: Long = 0L,
)

data class WeChatDialogueRequest(
    val companionId: Long,
    val wechatUserId: String,
    val inbound: WeChatInboundMessage,
)

data class WeChatDialogueResult(
    val replyText: String,
    val stickerLabels: List<String> = emptyList(),
    val blocked: Boolean = false,
    val assistantMessageId: Long? = null,
    val assistantMessageIds: List<Long> = emptyList(),
)

data class WeChatConnectionSnapshot(
    val state: WeChatConnectionState,
    val accountId: String? = null,
    val lastError: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class WeChatChannelHealthSnapshot(
    val primaryPollerActive: Boolean = false,
    val consecutiveFailures: Int = 0,
    val lastPollAtMs: Long = 0L,
    val lastErrorAtMs: Long = 0L,
    val lastError: String? = null,
    val openOutboxCount: Int = 0,
    val pendingOutboxCount: Int = 0,
    val failedOutboxCount: Int = 0,
    val sendingOutboxCount: Int = 0,
    val recentFailures: List<WeChatOutboxFailure> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),

    val watchdogStallCount: Int = 0,

    val lastWatchdogStallMs: Long = 0L,
)

data class WeChatOutboxFailure(
    val id: String,
    val wechatUserId: String,
    val kind: String,
    val retryCount: Int,
    val lastError: String?,
    val updatedAtMs: Long,
)
