package com.yunian.ai.wechat.outbox

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.dao.WeChatOutboxDao
import com.yunian.ai.database.model.WeChatOutboxEntity
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatDeliveryStatus
import com.yunian.ai.domain.wechat.WeChatFailureReason
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.domain.wechat.WeChatOutboxFailure
import com.yunian.ai.wechat.ilink.IlinkSessionExpiredException
import com.yunian.ai.wechat.ilink.IlinkSessionStore
import com.yunian.ai.wechat.map.WeChatOutboundSegmenter
import com.yunian.ai.wechat.transport.WeChatTransportPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class WeChatOutboxCoordinator(
    private val dao: WeChatOutboxDao,
    private val transport: WeChatTransportPort,
    private val sessionStore: IlinkSessionStore? = null,
    private val segmentGapMs: Long = DEFAULT_SEGMENT_GAP_MS,
    private val maxRetry: Int = DEFAULT_MAX_RETRY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val managedMediaCacheDir: File? = null,
) {
    private val drainMutex = Mutex()

    suspend fun enqueue(
        request: WeChatOutboundRequest,
        wechatUserId: String = request.wechatUserId.orEmpty(),
        rootId: String = UUID.randomUUID().toString(),
    ): String {
        require(wechatUserId.isNotBlank()) { "wechatUserId blank" }
        val created = nowMs()
        val entities = buildEntities(request, wechatUserId, rootId, created)
        if (entities.isNotEmpty()) {
            dao.insertAll(entities)
            SecureLog.i(
                TAG,
                "enqueue rootId=${rootId.take(8)} segs=${entities.size} kind=${entities.firstOrNull()?.kind} user=${maskUser(wechatUserId)}",
            )
        }
        return rootId
    }

    suspend fun drain(limit: Int = DEFAULT_DRAIN_LIMIT): Int = drainMutex.withLock {
        val ready = dao.listReady(nowMs = nowMs(), limit = limit)
        var sent = 0
        val deliveries = ready.map { it.rootId }.distinct().map { rootId ->
            dao.listOpenByRootId(rootId).ifEmpty { ready.filter { it.rootId == rootId } }
        }
        for ((index, items) in deliveries.withIndex()) {
            if (index > 0 && segmentGapMs > 0) {
                delay(segmentGapMs)
            }
            val delivered = if (
                items.all { WeChatContentKind.fromWireType(it.kind) == WeChatContentKind.TEXT } &&
                items.all { it.nextAttemptAtMs <= nowMs() }
            ) {
                dispatchTextSegments(items.sortedBy { it.segmentIndex })
            } else {
                items.filter { it.nextAttemptAtMs <= nowMs() }.count { dispatchOne(it) }
            }
            sent += delivered
        }

        val cutoff = nowMs() - SENT_RETENTION_MS
        dao.deleteSentBefore(cutoff)
        dao.deleteDeadBefore(maxRetry = maxRetry, cutoffMs = cutoff)
        cleanupManagedCache(dao.listMediaLocalPaths().toSet(), cutoff)
        if (sent > 0) {
            SecureLog.i(TAG, "drain sent=$sent ready=${ready.size}")
        } else if (ready.isNotEmpty()) {
            // 「明明排了队却一条都没发出去」是用户报「无法同步」的典型现象，
            // 必须留 I 级证据：正式包 Log.d 会被 proguard 剥离。
            val sample = recentFailures(DEFAULT_RECENT_FAILURE_LIMIT)
                .joinToString(" | ") { "${it.kind}:${it.lastError?.take(80)}" }
            SecureLog.i(
                TAG,
                "drain blocked ready=${ready.size} sent=0 recentFailures=$sample",
            )
        }
        sent
    }

    private suspend fun dispatchTextSegments(items: List<WeChatOutboxEntity>): Int {
        var sent = 0
        for ((index, item) in items.withIndex()) {
            if (index > 0 && segmentGapMs > 0) {
                delay(segmentGapMs)
            }
            if (dispatchOne(item)) {
                sent++
            }
        }
        return sent
    }

    internal fun cleanupManagedCache(referencedPaths: Set<String>, cutoffMs: Long): Int {
        val cacheDir = managedMediaCacheDir?.takeIf { it.exists() && it.isDirectory } ?: return 0
        val canonicalDir = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return 0
        val canonicalReferences = referencedPaths.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }
        return canonicalDir.listFiles().orEmpty().count { file ->
            file.isFile &&
                file.lastModified() < cutoffMs &&
                file.canonicalFile.parentFile == canonicalDir &&
                file.canonicalPath !in canonicalReferences &&
                file.delete()
        }
    }

    suspend fun openCount(): Int = dao.countOpen()

    suspend fun countByStatus(status: WeChatDeliveryStatus): Int =
        dao.countByStatus(status.name)

    suspend fun recentFailures(limit: Int = DEFAULT_RECENT_FAILURE_LIMIT): List<WeChatOutboxFailure> {
        return dao.listRecentFailed(limit).map { entity ->
            WeChatOutboxFailure(
                id = entity.id,
                wechatUserId = entity.wechatUserId,
                kind = WeChatContentKind.fromWireType(entity.kind).name,
                retryCount = entity.retryCount,
                lastError = entity.lastError,
                updatedAtMs = entity.updatedAtMs,
            )
        }
    }

    fun buildEntities(
        request: WeChatOutboundRequest,
        wechatUserId: String,
        rootId: String,
        createdAtMs: Long,
    ): List<WeChatOutboxEntity> {
        val segments = WeChatOutboundSegmenter.expand(request, wechatUserId, rootId)
        return segments.map { seg ->
            WeChatOutboxEntity(
                id = seg.outboxId,
                rootId = rootId,
                companionId = request.companionId,
                wechatUserId = wechatUserId,
                kind = seg.kind.wireType,
                text = seg.text,
                mediaLocalPath = seg.media?.localPath,
                mediaFileName = seg.media?.fileName,
                mediaDescription = seg.media?.description,
                segmentIndex = seg.segmentIndex,
                segmentCount = seg.segmentCount,
                sourceMessageId = request.sourceMessageId,
                status = WeChatDeliveryStatus.PENDING.name,
                retryCount = 0,
                nextAttemptAtMs = 0L,
                lastError = null,
                createdAtMs = createdAtMs,
                updatedAtMs = createdAtMs,
            )
        }
    }

    private suspend fun dispatchOne(item: WeChatOutboxEntity): Boolean {
        dao.updateStatus(item.id, WeChatDeliveryStatus.SENDING.name)
        val result = runCatching {
            val contextToken = resolveContextToken(item.wechatUserId)
            // iLink 协议只能回复：无 token 或 token 过期时发送会被服务端静默丢弃（ret=0 但不投递）。
            // sessionStore 存在（生产环境）时发送前拦截并判死；未注入时保持旧行为交由 transport/SDK 处理。
            if (sessionStore != null) {
                if (contextToken.isNullOrBlank()) error(SEND_BLOCKED_NO_TOKEN)
                ensureContextTokenFresh(item.wechatUserId, contextToken)
            }
            when (WeChatContentKind.fromWireType(item.kind)) {
                WeChatContentKind.TEXT -> {
                    val text = item.text?.takeIf { it.isNotBlank() }
                        ?: error("empty text segment")
                    transport.sendText(item.wechatUserId, text, contextToken).getOrThrow()
                }
                WeChatContentKind.IMAGE -> {
                    val path = item.mediaLocalPath ?: error("image path missing")
                    val bytes = File(path).takeIf { it.exists() }?.readBytes()
                        ?: error("image file missing: $path")
                    transport.sendImage(
                        toUserId = item.wechatUserId,
                        imageBytes = bytes,
                        fileName = item.mediaFileName ?: "image.png",
                        description = item.mediaDescription,
                        contextToken = contextToken,
                    ).getOrThrow()
                }
                else -> error("unsupported outbox kind=${item.kind}")
            }
        }
        return if (result.isSuccess) {
            dao.updateStatus(item.id, WeChatDeliveryStatus.SENT.name)
            true
        } else {
            updateFailure(item, result.exceptionOrNull())
            false
        }
    }

    private suspend fun ensureContextTokenFresh(wechatUserId: String, contextToken: String) {
        val store = sessionStore ?: return
        val account = store.getSessionAccount() ?: return
        val savedAt = store.getContextTokenSavedAt(account.accountId, wechatUserId) ?: return
        val ageMs = nowMs() - savedAt
        if (ageMs > CONTEXT_TOKEN_MAX_AGE_MS) {
            SecureLog.w(TAG, "context token expired age=${ageMs / 1000}s user=${maskUser(wechatUserId)}")
            error(SEND_BLOCKED_EXPIRED)
        }
    }

    private suspend fun resolveContextToken(wechatUserId: String): String? =
        sessionStore?.getSessionAccount()?.let { account ->
            sessionStore.getContextToken(account.accountId, wechatUserId)
        }

    private suspend fun updateFailure(item: WeChatOutboxEntity, error: Throwable?) {
        val raw = error?.message ?: "send failed"
        val nextRetry = item.retryCount + 1
        val nonRetryable = isNonRetryable(error)
        val reason = if (nextRetry >= maxRetry || nonRetryable) {
            WeChatFailureReason.OUTBOX_DEAD
        } else {
            WeChatFailureReason.OUTBOX_SEND
        }
        val err = "${reason.wireName}: ${raw.take(160)}"
        if (nextRetry >= maxRetry || nonRetryable) {
            dao.updateAttempt(
                id = item.id,
                status = WeChatDeliveryStatus.FAILED.name,
                retryCount = nextRetry,
                nextAttemptAtMs = Long.MAX_VALUE / 4,
                lastError = err,
            )
            SecureLog.w(
                TAG,
                "dead id=${item.id.take(8)} retry=$nextRetry user=${maskUser(item.wechatUserId)} err=$err",
            )
        } else {
            val backoff = (1L shl nextRetry.coerceAtMost(6)) * 1000L
            dao.updateAttempt(
                id = item.id,
                status = WeChatDeliveryStatus.FAILED.name,
                retryCount = nextRetry,
                nextAttemptAtMs = nowMs() + backoff.coerceAtMost(60_000L),
                lastError = err,
            )
            SecureLog.w(
                TAG,
                "retry id=${item.id.take(8)} retry=$nextRetry backoff=${backoff.coerceAtMost(60_000L)}ms err=$err",
            )
        }
    }

    private fun isNonRetryable(error: Throwable?): Boolean {
        // iLink 会话过期（errcode=-14）：重新扫码前重试必然失败，直接判死
        if (error is IlinkSessionExpiredException) return true
        val message = (error?.message ?: return false).lowercase()
        return NON_RETRYABLE_MARKERS.any { message.contains(it) }
    }

    companion object {
        private const val TAG = "WeChatOutbox"
        const val DEFAULT_SEGMENT_GAP_MS = 0L
        const val DEFAULT_MAX_RETRY = 5
        const val DEFAULT_DRAIN_LIMIT = 20
        const val DEFAULT_RECENT_FAILURE_LIMIT = 5

        /** iLink 官方协议：用户超过 24 小时未发消息，context_token 过期，无法回复 */
        const val CONTEXT_TOKEN_MAX_AGE_MS = 24L * 60 * 60 * 1000

        const val SEND_BLOCKED_NO_TOKEN =
            "context_token 缺失：iLink 协议只能回复对方先发来的消息，请让对方先发一条消息"
        const val SEND_BLOCKED_EXPIRED =
            "context_token 已过期（会话超过 24 小时）：请让对方重新发送一条消息后再试"

        private const val SENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        // 统一小写匹配（isNonRetryable 会 lowercase）
        private val NON_RETRYABLE_MARKERS = listOf(
            "未登录",
            "errcode=-14",
            "context_token 缺失",
            "context_token 已过期",
            "contexttoken 缺失",
            "missing latest context token",
        )

        private fun maskUser(userId: String): String {
            if (userId.length <= 6) return "***"
            return userId.take(3) + "***" + userId.takeLast(2)
        }
    }
}
