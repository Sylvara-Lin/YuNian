package com.yunian.ai.feature.qqbot.data

import android.content.Context
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.DialogueRequest
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.ImageGenService
import com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val MERGE_WINDOW_MS = 2000L
private const val MISSING_COMPANION_HINT_COOLDOWN_MS = 10 * 60 * 1000L

class QQBotChatBridge(
    private val context: Context,
    private val qqBotRepository: QQBotMessageRepository,
    private val tokenStore: QQBotTokenStore
) {
    private val database = AppDatabase.getDatabase(context)
    private val companionRepository = CompanionRepository(database.companionDao())
    private val mappingManager = QQBotUserMappingManager(tokenStore, companionRepository)

    /** 统一 AI 对话中间层（core:agent 实现）：AI 回合 / 安全 / 落库 / 记忆全部内聚。 */
    private val dialogueCoordinator: DialogueCoordinator by lazy {
        ServiceRegistry.get(DialogueCoordinator::class.java)
            ?: throw IllegalStateException("DialogueCoordinator not registered in ServiceRegistry")
    }

    private val bridgeJob = SupervisorJob()
    private val bridgeScope = CoroutineScope(bridgeJob + AppDispatchers.io)

    private var eventCollectionJob: kotlinx.coroutines.Job? = null
    private val activeReplyJobs = Any()
    private val pendingLock = Any()
    private val pendingByKey = ConcurrentHashMap<String, MutableList<QQInboundEvent>>()
    private val missingCompanionHintAtMs = ConcurrentHashMap<String, Long>()

    fun start() {
        if (eventCollectionJob?.isActive == true) {
            android.util.Log.d("QQBotBridge", "Already started")
            return
        }
        android.util.Log.i("QQBotBridge", "Starting event collection")
        eventCollectionJob = bridgeScope.launch {
            qqBotRepository.incomingEvents.collect { event ->
                val autoReply = tokenStore.getAutoReply()
                val text = qqBotRepository.extractText(event)
                android.util.Log.d("QQBotBridge", "Event received, autoReply=$autoReply, text=$text")
                if (!autoReply) return@collect
                val key = qqBotRepository.getReplyKey(event)

                synchronized(pendingLock) {
                    pendingByKey.getOrPut(key) { mutableListOf() }.add(event)
                }

                synchronized(activeReplyJobs) {
                    val existingJob = qqBotRepository.getActiveReplyJob(key)
                    if (existingJob?.isActive == true) {
                        android.util.Log.d("QQBotBridge", "Merge window open for $key, queued")
                        return@synchronized
                    }
                    val newJob = bridgeScope.launch { drainPending(key) }
                    qqBotRepository.setActiveReplyJob(key, newJob)
                }
            }
        }
    }

    fun stop() {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
        synchronized(activeReplyJobs) {
            qqBotRepository.activeReplyJobKeys().forEach { key ->
                qqBotRepository.getActiveReplyJob(key)?.cancel()
                qqBotRepository.removeActiveReplyJob(key)
            }
        }
        synchronized(pendingLock) { pendingByKey.clear() }
    }

    private suspend fun drainPending(key: String) {
        try {
            while (true) {
                delay(MERGE_WINDOW_MS)
                val batch: List<QQInboundEvent> = synchronized(pendingLock) {
                    val list = pendingByKey[key] ?: mutableListOf()
                    val snapshot = list.toList()
                    list.clear()
                    snapshot
                }
                if (batch.isEmpty()) break
                if (batch.size == 1) {
                    handleIncomingEventStreaming(batch.last())
                } else {
                    val mergedText = batch.joinToString("\n") { qqBotRepository.extractText(it) }.trim()
                    if (mergedText.isNotBlank()) {
                        android.util.Log.d("QQBotBridge", "Merged ${batch.size} events for $key")
                        runReply(batch.last(), mergedText)
                    }
                }
            }
        } finally {
            synchronized(activeReplyJobs) {
                qqBotRepository.removeActiveReplyJob(key)
            }
            val hasMore = synchronized(pendingLock) { pendingByKey[key].orEmpty().isNotEmpty() }
            if (hasMore) {
                synchronized(activeReplyJobs) {
                    if (qqBotRepository.getActiveReplyJob(key)?.isActive != true) {
                        qqBotRepository.setActiveReplyJob(key, bridgeScope.launch { drainPending(key) })
                    }
                }
            }
        }
    }

    suspend fun handleIncomingEventStreaming(event: QQInboundEvent) = withContext(Dispatchers.IO) {
        val text = qqBotRepository.extractText(event)
        if (text.isNotBlank()) {
            runReply(event, text)
        }
    }

    private suspend fun runReply(event: QQInboundEvent, text: String) = withContext(Dispatchers.IO) {
        try {
            val qqUserId = when (event) {
                is QQInboundEvent.C2CMessage -> event.userOpenid
                is QQInboundEvent.GroupAtMessage -> "${event.groupOpenid}:${event.memberOpenid}"
                is QQInboundEvent.GuildMessage -> "${event.channelId}:${event.authorId}"
                is QQInboundEvent.DirectMessage -> "${event.guildId}:${event.authorId}"
            }
            android.util.Log.d("QQBotBridge", "Handling streaming event, qqUserId=$qqUserId")
            if (qqUserId.isBlank()) return@withContext

            android.util.Log.d("QQBotBridge", "Extracted text: $text")

            val companionId = mappingManager.getOrCreateMapping(qqUserId) ?: run {
                android.util.Log.w("QQBotBridge", "No companion mapping for $qqUserId")
                notifyMissingCompanion(event)
                return@withContext
            }
            android.util.Log.d("QQBotBridge", "Mapped to companionId=$companionId")
            val companion = companionRepository.getCompanionById(companionId) ?: run {
                android.util.Log.w("QQBotBridge", "Companion not found: $companionId")
                return@withContext
            }
            android.util.Log.d("QQBotBridge", "Mapped companion=${companion.name}")

            // 纯净桥接：封禁判定 / 输入安全检查 / AI 回合 / 输出安全检查 / 生图清洗 /
            // 落库 / 记忆提取全部收敛在中间层（DialogueCoordinator，core:agent 实现）。
            val result = dialogueCoordinator.generateReply(
                DialogueRequest(
                    companionId = companionId,
                    text = text,
                    imagePath = null,
                )
            )
            android.util.Log.d(
                "QQBotBridge",
                "Dialogue done blocked=${result.blocked} reply_len=${result.replyText.length}",
            )

            val safeText = result.replyText
            if (safeText.isBlank()) {
                // 中间层未产出可发送内容（被拦截或上游错误）：不回灌任何消息，
                // 拦截文案已由中间层落库并随首次 user 消息进入会话。
                return@withContext
            }

            var lastSendTime = 0L
            val minGapMs = 500L
            val sentences = splitIntoSentences(safeText)
            for (sentence in sentences) {
                if (sentence.isBlank()) continue
                val elapsed = System.currentTimeMillis() - lastSendTime
                if (elapsed < minGapMs && lastSendTime > 0) {
                    delay(minGapMs - elapsed)
                }
                if (tokenStore.getForwardEnabled()) {
                    sendReply(event, sentence)
                }
                lastSendTime = System.currentTimeMillis()
            }

            // 生图：判定逻辑与 App 内完全一致（复用 ImageGenService），失败绝不影响聊天主流程
            runCatching { generateAndSendImages(event, companionId, text, safeText) }
                .onFailure { e ->
                    android.util.Log.e("QQBotBridge", "image gen failed: ${e.message}", e)
                }

        } catch (e: Exception) {
            android.util.Log.e("QQBotBridge", "Error handling incoming event", e)
        }
    }

    /**
     * 桥接链路的生图：判定逻辑与 App 内完全一致（复用 [ImageGenService]），
     * 落库由服务完成，这里只负责把同一张图发到 QQ。
     *
     * QQ 富媒体依赖「上传 file_info → msg_type=7 发送」两步，频道场景与任何失败都会
     * 明确降级为一条文字说明并落 I 级日志，绝不静默失败。
     */
    private suspend fun generateAndSendImages(
        event: QQInboundEvent,
        companionId: Long,
        userText: String,
        aiText: String,
    ) {
        if (aiText.isBlank()) return
        val service = ServiceRegistry.get(ImageGenService::class.java) ?: run {
            android.util.Log.i("QQBotBridge", "image gen skipped: ImageGenService not registered")
            return
        }
        val images = service.generateForReply(
            companionId = companionId,
            userText = userText,
            aiText = aiText,
        )
        if (images.isEmpty()) {
            android.util.Log.i("QQBotBridge", "image gen not triggered companionId=$companionId")
            return
        }
        android.util.Log.i("QQBotBridge", "image gen done companionId=$companionId count=${images.size}")
        if (!tokenStore.getForwardEnabled()) {
            android.util.Log.i("QQBotBridge", "image not sent: forward disabled")
            return
        }
        images.forEach { image ->
            if (image.filePath.isBlank()) return@forEach
            val result = qqBotRepository.sendImageMessage(event, image.filePath)
            result.onFailure { e ->
                // 明确降级：告诉用户图片没发出去，并把原因写进 I 级日志
                android.util.Log.i(
                    "QQBotBridge",
                    "QQ image send failed, fallback to text: ${e.message}",
                )
                runCatching { sendReply(event, "（配图已生成，但发送失败了：${e.message ?: "未知原因"}）") }
            }.onSuccess {
                android.util.Log.i("QQBotBridge", "QQ image sent path=${image.filePath.take(60)}")
            }
        }
    }

    private suspend fun notifyMissingCompanion(event: QQInboundEvent) {
        val key = qqBotRepository.getReplyKey(event)
        val now = System.currentTimeMillis()
        val last = missingCompanionHintAtMs.putIfAbsent(key, now) ?: 0L
        if (last > 0L && now - last < MISSING_COMPANION_HINT_COOLDOWN_MS) {
            missingCompanionHintAtMs[key] = last
            return
        }
        missingCompanionHintAtMs[key] = now
        sendReply(event, "还没有可用的 AI 伴侣，请先在予念里创建一个伴侣，再回来和我聊天。")
    }

    private suspend fun sendReply(event: QQInboundEvent, text: String) {
        val result = qqBotRepository.sendTextMessage(event, text)
        result.onFailure { e ->
            android.util.Log.e("QQBotBridge", "Failed to send QQ reply: ${e.message}", e)
        }
    }

    private fun cleanReplyText(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\[\\]\\s，。！？、]+"), "")
            .replace(Regex("[\\[\\]\\s，。！？、]+$"), "")
            .trim()
    }

    private fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val delimiters = charArrayOf('。', '！', '？', '!', '?', '\n')
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val idx = text.indexOfAny(delimiters, startIndex = start)
            if (idx < 0) {
                val remaining = text.substring(start).trim()
                if (remaining.isNotEmpty()) result.add(remaining)
                break
            }
            val end = idx + 1
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) result.add(sentence)
            start = end
        }
        return result
    }

    fun close() {
        bridgeJob.cancel()
    }
}
