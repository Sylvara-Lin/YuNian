package com.yunian.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.common.StickerManager
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.common.text.BubbleTextSplitter
import com.yunian.ai.common.text.DedupGuard
import com.yunian.ai.common.text.MessageSegmenter
import com.yunian.ai.domain.wechat.WeChatProactiveSync
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.timeline.ConversationRef
import com.yunian.ai.domain.timeline.TimelineStore
import com.yunian.ai.feature.chat.data.ChatContextResolver
import com.yunian.ai.feature.chat.data.ChatDetailSettingsStore
import com.yunian.ai.feature.chat.timeline.EventCommitRules
import com.yunian.ai.feature.chat.timeline.PendingTurn
import com.yunian.ai.feature.chat.timeline.StreamingReasoningMessagePipeline
import com.yunian.ai.feature.chat.timeline.TurnCommitCoordinator
import com.yunian.ai.feature.chat.voice.ChatTtsController
import com.yunian.ai.common.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class AiResponseFinalizer(
    private val companionId: Long,
    private val chatRepository: ChatRepository,
    private val messageWriter: MessageWriteCoordinator,
    private val memoryProvider: MemoryProvider,
    private val stickerManager: StickerManager,
    private val chatDetailSettingsStore: ChatDetailSettingsStore,
    private val appSettingsStore: AppSettingsStore,
    private val contextResolver: ChatContextResolver,
    private val aiService: AiServiceProvider,
    private val applicationApiScope: CoroutineScope,
    private val turnState: ChatTurnState,
    private val chatTtsController: ChatTtsController,
    private val application: Application,
    private val questionRegex: Regex,
    private val turnCommit: TurnCommitCoordinator? = null,
) {
    private val commitCoordinator: TurnCommitCoordinator by lazy {
        turnCommit ?: TurnCommitCoordinator(
            timelineStore = ServiceRegistry.getOrThrow(TimelineStore::class.java),
            messageWriter = messageWriter,
        )
    }

    data class DeliveredResponse(
        val messageId: Long,
        val aiContent: String,
        val segments: List<String>,
        val userContentForMemory: String?,
        val allowFollowUpMessage: Boolean
    )

    suspend fun deliverResponse(
        aiContent: String,
        reasoning: String?,
        userContentForMemory: String? = null,
        logMessage: String = "AI response received",
        pendingTurn: PendingTurn? = null,
        reasoningStartedAtMs: Long? = null,
        /**
         * 每当一段回复**确实写入数据库之后**被回调。该回调透传到
         * [MessageWriteCoordinator.enqueueChat] 的 `onPersisted`，由**不可取消**的持久化消费协程
         * 在执行完写库后触发——因此即使调用方协程在 `await()` 期间被取消，只要行已落库，回调仍会触发。
         * 一轮多段回复时任一 (或全部) 段落库成功都会触发（幂等），用于生成侧打断合并时判断旧 batch 是否可丢弃。
         */
        onCommitted: (() -> Unit)? = null,
        /**
         * 是否允许按 AI 显式空行分段送达。
         * `true`（默认）：未走气泡协议时，按空行分段作为兜底拆分；
         * `false`：气泡协议已逐条生成，本段整条不拆。
         */
        allowParagraphSplit: Boolean = true,
    ): DeliveredResponse {
        val showReasoning = appSettingsStore.getShowReasoning()
        val turn = pendingTurn ?: PendingTurn.start(
            conversation = ConversationRef(conversationId = companionId, conversationType = "chat"),
            startedAtMs = reasoningStartedAtMs ?: System.currentTimeMillis(),
        )

        if (EventCommitRules.shouldPersistReasoning(reasoning) && !turn.isReasoningComplete) {
            turn.replaceReasoningText(reasoning!!.trim())
        }

        if (EventCommitRules.shouldProjectReasoningLive(showReasoning, reasoning)) {
            StreamingReasoningMessagePipeline.upsertStreaming(
                companionId = companionId,
                turnId = turn.turnId,
                text = reasoning!!.trim(),
                timestamp = reasoningStartedAtMs ?: turn.startedAtMs,
                eventIndex = turn.streamingReasoningEvent()?.eventIndex,
                anchorMessageId = turn.anchorMessageId,
            )
        } else if (!showReasoning) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, turn.turnId)
        }

        if (EventCommitRules.shouldPersistReasoning(reasoning) || turn.isReasoningComplete) {
            val completedAt = System.currentTimeMillis()
            val duration = EventCommitRules.durationMs(
                startedAtMs = reasoningStartedAtMs ?: turn.startedAtMs,
                completedAtMs = completedAt,
            )
            turn.completeReasoning(
                finalText = reasoning?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = duration,
                timestamp = completedAt,
            )
            val reasoningEvent = turn.takeReasoningEventForCommit()
            if (reasoningEvent != null) {
                runCatching {
                    commitCoordinator.commitReasoning(turn.conversation, reasoningEvent)
                }.onFailure {
                    SecureLog.e("ChatViewModel", "REASONING commit failed: ${it.message}")
                }
            }

            StreamingReasoningMessagePipeline.removeStreaming(companionId, turn.turnId)
        }

        val settings = chatDetailSettingsStore.getSettings(companionId)

        val deliverySafeText = if (!userContentForMemory.isNullOrBlank()) {
            com.yunian.ai.network.ResponsePostProcessor.trimIdleEmotionOverDelivery(
                aiContent,
                userContentForMemory,
            )
        } else {
            aiContent
        }
        val processedText = TextProcessor.processStickerTagsForSplit(
            deliverySafeText,
            stickerManager,
            settings.stickerProbability,
        ) { sendStickerMessage(it) }

        val rawSegments = BubbleTextSplitter.splitForDelivery(
            processedText,
            allowParagraphSplit = allowParagraphSplit,
            // 条数不设限：AI 敲几个回车就发几条（用户要求「无限制」）
            maxBubbles = BubbleTextSplitter.DEFAULT_MAX_BUBBLES,
        )
        val dedupWindow = loadDedupWindow(turn.turnId.value)
        val segments = BubbleDedupPlanner.plan(rawSegments, dedupWindow)
        val hasPendingSticker = turnState.pendingSticker != null
        val stickerBeforeText = hasPendingSticker && kotlin.random.Random.nextFloat() < 0.5f

        if (processedText.isBlank() && aiContent.isNotBlank()) {
            SecureLog.w("ChatViewModel", "WARNING: processedText is blank but aiContent has ${aiContent.length} chars. Original: '${aiContent.take(80)}'")
        }

        val aiMessageId = if (segments.size <= 1) {

            val safeProcessed = (segments.firstOrNull() ?: processedText).ifBlank {
                if (aiContent.isNotBlank()) {
                    SecureLog.w("ChatViewModel", "Falling back to zero-width space. aiContent length=${aiContent.length}")
                    "\u200B"
                } else {
                    SecureLog.w("ChatViewModel", "Both processedText and aiContent are blank, storing empty message")
                    ""
                }
            }
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            val voiceBar = synthesizeVoiceBarOrNull(safeProcessed)
            val id = commitCoordinator.commitAssistantText(
                companionId = companionId,
                text = safeProcessed,
                turn = turn,
                audioPath = voiceBar?.path,
                durationMs = voiceBar?.durationMs,
                onPersisted = onCommitted,
            )
            // 已落实气泡进跨轮滚动窗口（P1-4）
            turnState.pushRecentDedup(DedupGuard.normalize(safeProcessed))
            SecureLog.d("ChatViewModel", "$logMessage, length=${aiContent.length}, id=$id, voiceBar=${voiceBar != null}")
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            broadcastAiMessage(id, safeProcessed)
            id
        } else {

            if (stickerBeforeText) {
                flushPendingSticker()
            }
            var lastId = -1L
            for ((index, segment) in segments.withIndex()) {
                if (index > 0) {
                    delay(800L + kotlin.random.Random.nextLong(1200L))
                }
                val safeSegment = segment.ifBlank { "\u200B" }
                val voiceBar = synthesizeVoiceBarOrNull(safeSegment)
                val id = commitCoordinator.commitAssistantText(
                    companionId = companionId,
                    text = safeSegment,
                    turn = turn,
                    audioPath = voiceBar?.path,
                    durationMs = voiceBar?.durationMs,
                    onPersisted = onCommitted,
                )
                // 已落实气泡进跨轮滚动窗口（P1-4）
                turnState.pushRecentDedup(DedupGuard.normalize(safeSegment))
                lastId = id
                SecureLog.d(
                    "ChatViewModel",
                    "$logMessage segment ${index + 1}/${segments.size}, length=${segment.length}, id=$id, voiceBar=${voiceBar != null}"
                )
            }
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)

            if (lastId > 0) {
                broadcastAiMessage(lastId, segments.joinToString("\n"))
            }
            lastId
        }

        if (!showReasoning || reasoning.isNullOrBlank()) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, turn.turnId)
        }

        if (turnState.lastStickerMsgId > 0) {
            broadcastWeChatMessage(turnState.lastStickerMsgId, turnState.lastStickerContent)
            turnState.lastStickerMsgId = -1
            turnState.lastStickerContent = ""
        }

        turn.releaseIndexer()

        return DeliveredResponse(
            messageId = aiMessageId,
            aiContent = aiContent,
            segments = segments,
            userContentForMemory = userContentForMemory,
            allowFollowUpMessage = settings.allowFollowUpMessage
        )
    }

    suspend fun afterDeliver(delivered: DeliveredResponse) {

        if (delivered.userContentForMemory != null && delivered.aiContent.isNotBlank()) {
            runCatching {
                withTimeoutOrNull(TimeoutBudgets.CHAT_VM_MEMORY_EXTRACT_MS) {
                    memoryProvider.extractAndSaveFromConversation(
                        userInput = delivered.userContentForMemory,
                        aiResponse = delivered.aiContent,
                        companionId = companionId,
                        groupId = null
                    )
                }
            }.onFailure {
                SecureLog.e("ChatViewModel", "Memory save failed: ${it.message}")
            }
        }

        triggerFollowUpIfNeeded(delivered.aiContent, delivered.allowFollowUpMessage)
    }

    private suspend fun synthesizeVoiceBarOrNull(text: String): com.yunian.ai.feature.chat.voice.VoiceBarAudio? {
        if (text.isBlank() || text == "\u200B") return null

        if (MessageSegmenter.isNoiseText(text)) return null
        return runCatching { chatTtsController.synthesizeOnly(text) }
            .onFailure { SecureLog.w("ChatViewModel", "Voice bar synth failed: ${it.message}") }
            .getOrNull()
    }

    private fun broadcastAiMessage(messageId: Long, finalContent: String) {
        if (finalContent.isNotBlank() && finalContent != "\u200B") {
            broadcastWeChatMessage(messageId, finalContent)
        }
    }

    private fun broadcastWeChatMessage(messageId: Long, finalContent: String? = null) {
        WeChatProactiveSync.enqueue(companionId, messageId, finalContent)
        SecureLog.d("ChatViewModel", "Enqueue WeChat proactive message, companionId=$companionId, messageId=$messageId, hasFinalContent=${!finalContent.isNullOrBlank()}")
    }

    private val followUpMinIntervalMs = 30 * 60 * 1000L

    private val followUpTriggerProbability = 0.15f

    @Volatile
    private var lastFollowUpAt: Long = 0L

    private fun triggerFollowUpIfNeeded(aiContent: String, allowFollowUp: Boolean) {
        if (!allowFollowUp) return
        // 长叙述不追问：AI 已讲完一大段，再自动追问会变成自问自答。
        if (aiContent.length >= ChatConstants.FOLLOW_UP_SUPPRESS_MIN_CHARS) return
        if (questionRegex.containsMatchIn(aiContent)) return
        if (looksLikeTurnBackToUser(aiContent)) return
        val now = System.currentTimeMillis()
        if (now - lastFollowUpAt < followUpMinIntervalMs) return
        if (kotlin.random.Random.nextFloat() > followUpTriggerProbability) return
        lastFollowUpAt = now

        applicationApiScope.launch {
            try {
                delay(ChatConstants.FOLLOW_UP_BASE_DELAY_MS + kotlin.random.Random.nextLong(ChatConstants.FOLLOW_UP_RANDOM_DELAY_MS))

                val history = contextResolver.getShortHistoryForAi(companionId, shortLimit = ChatConstants.SHORT_HISTORY_LIMIT)

                val companionInfo = companionInfoProvider?.invoke() ?: return@launch
                val followUp = aiService.generateFollowUpQuestion(
                    companionInfo, history.map { msg ->
                        com.yunian.ai.domain.AiChatMessage(
                            isFromUser = msg.isFromUser,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            type = if (msg.type == com.yunian.ai.database.model.MessageType.IMAGE)
                                com.yunian.ai.domain.AiMessageType.IMAGE
                            else com.yunian.ai.domain.AiMessageType.TEXT,
                            companionId = companionId
                        )
                    }, aiContent
                ) ?: return@launch

                val followUpSafety = ContentFilter.checkOutputSafety(followUp)
                if (!followUpSafety.isSafe) {
                    SecureLog.w("ChatViewModel", "Follow-up safety violation: ${followUpSafety.reason}")
                    return@launch
                }

                val followUpMsg = ChatMessage(
                    companionId = companionId,
                    content = followUp,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                val msgId = messageWriter.enqueueChat(followUpMsg)
                broadcastWeChatMessage(msgId, followUp)
                SecureLog.d("ChatViewModel", "Follow-up question sent: $followUp")
            } catch (e: Exception) {
                SecureLog.w("ChatViewModel", "Follow-up question failed: ${e.message}")
            }
        }
    }

    /**
     * 明确的话轮移交信号（P1-6）：命中即视为 AI 已把话头递回给用户，不再自动追问。
     * 旧的裸 `contains("你")` 与高频语气词列表误杀面太大（日常口语几乎都带「你/吧/呀」），
     * 把追问转化率压没了；只保留这些一眼就是「在问你」的短语。
     * questionRegex 与长度闸在调用处保持不变。
     */
    private fun looksLikeTurnBackToUser(text: String): Boolean {
        val turnBackMarkers = listOf("你呢", "你说呢", "你觉得", "你们觉得呢", "想听你", "问下你", "问你呢")
        return turnBackMarkers.any { text.contains(it) }
    }

    /**
     * 加载本轮查重窗口：
     * 优先复用 `ChatTurnState` 上按轮次缓存的窗口（同一轮多次送达 —— 如连发气泡逐条送达 —— 共用并把已发气泡累积进去）；
     * 首次则用「最近 [DedupGuard.WINDOW_LAST_AI] 条历史 AI 消息 ∪ 跨轮滚动窗口 recentDedupWindow」初始化（P1-4：
     * 仅靠历史 3 条会随轮次滚动而漏掉上几轮刚发过的内容，滚动窗口把跨轮已落实气泡也纳入查重）。
     */
    private suspend fun loadDedupWindow(turnKey: String): MutableList<String> {
        val cached = turnState.dedupWindow
        if (turnState.dedupTurnKey == turnKey && cached != null) return cached

        val window = mutableListOf<String>()
        runCatching {
            contextResolver.getShortHistoryForAi(companionId, shortLimit = ChatConstants.SHORT_HISTORY_LIMIT)
        }.getOrDefault(emptyList())
            .asReversed()
            .filter { !it.isFromUser }
            .take(DedupGuard.WINDOW_LAST_AI)
            .forEach { window.add(DedupGuard.normalize(it.content)) }

        // 读取侧快照后再并入（与 pushRecentDedup 互斥）：禁止边遍历边改跨轮窗口
        window.addAll(turnState.snapshotRecentDedup())

        turnState.dedupTurnKey = turnKey
        turnState.dedupWindow = window
        return window
    }

    private suspend fun sendStickerMessage(sticker: StickerInfo): Long {
        return turnState.stickerMutex.withLock {
            if (turnState.stickerSentThisTurn) return@withLock -1
            turnState.stickerSentThisTurn = true
            turnState.pendingSticker = sticker
            -1
        }
    }

    private suspend fun flushPendingSticker(): Long {
        val sticker = turnState.pendingSticker ?: return -1
        turnState.pendingSticker = null
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name
        val stickerContent = "[$stickerId]"
        val stickerMessage = ChatMessage(
            companionId = companionId,
            content = stickerContent,
            isFromUser = false,
            timestamp = System.currentTimeMillis()
        )
        val msgId = messageWriter.enqueueChat(stickerMessage)
        if (msgId > 0) {
            turnState.lastStickerMsgId = msgId
            turnState.lastStickerContent = stickerContent
        }
        return msgId
    }

    var companionInfoProvider: (() -> com.yunian.ai.domain.AiCompanionInfo?)? = null

    /**
     * 落地 Rust `send_sticker` 工具事件（Agent 架构贴纸链路）。
     *
     * - `entryId != null`：Rust `builtin_send_sticker` 已通过 `sticker_pick` 回调预选实际表情包
     *   （事件 extra 携带 `entry_id`）→ 按 id **直达落地**，保证与工具回灌结果一致；
     * - `entryId == null`：`description` 为逗号分隔的命中标签（1~3 个）→ 偏好引擎采样选一张。
     * - 均未命中 → 降级为 `[描述]` 文本消息，保证模型意图可见（不丢消息）。
     *
     * 贴纸消息编码约定：**普通 TEXT 消息，内容为 `[stickerId]`**，由渲染层识别为表情包
     * （`MessageType` 无 STICKER 枚举，全仓库统一此约定）。
     *
     * @return 落库后的消息 id；≤0 表示未落地。
     */
    suspend fun deliverSticker(description: String, entryId: Long? = null): Long {
        if (description.isBlank() && entryId == null) return -1
        val tags = description.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val dao = com.yunian.ai.database.AppDatabase.getDatabase(application).stickerEntryDao()

        // ① entryId 直达：跳过重新采样，按工具预选 id 落地
        if (entryId != null) {
            val entry = dao.getById(entryId)
            if (entry != null) {
                val msgId = enqueueStickerMessage(stickerIdOf(entry))
                if (msgId > 0) {
                    com.yunian.ai.agent.sticker.StickerPreferenceFacade.recordUsage(
                        context = application,
                        stickerId = entryId,
                        source = com.yunian.ai.agent.uniffi.StickerSource.MODEL,
                        contextTags = tags,
                    )
                }
                return msgId
            }
            // entry 不存在（资源被清理）→ 落到引擎/兜底路径重新匹配
        }

        // ② 偏好引擎采样：tags → OR 命中 + 加权随机 → DB 条目 → 落地 + 记 Model 使用
        if (tags.isNotEmpty()) {
            val sampledId = runCatching {
                com.yunian.ai.agent.sticker.StickerPreferenceFacade
                    .sampleCandidates(application, limit = 1, queryTags = tags)
                    .firstOrNull()
            }.getOrNull()
            if (sampledId != null) {
                val entry = dao.getById(sampledId)
                if (entry != null) {
                    val msgId = enqueueStickerMessage(stickerIdOf(entry))
                    if (msgId > 0) {
                        com.yunian.ai.agent.sticker.StickerPreferenceFacade.recordUsage(
                            context = application,
                            stickerId = sampledId,
                            source = com.yunian.ai.agent.uniffi.StickerSource.MODEL,
                            contextTags = tags,
                        )
                    }
                    return msgId
                }
            }
        }

        // ③ 兜底：按描述匹配内置/未入库表情包；再不行退化为 [描述] 文本
        val sticker = stickerManager.findStickerByDescriptionExact(description)
            ?: stickerManager.findStickerByDescription(description)
        if (sticker != null) {
            return enqueueStickerMessage(stickerIdOf(sticker))
        }
        SecureLog.w("AiResponseFinalizer", "Sticker not matched: [$description], fallback to text")
        return enqueueStickerMessage(description)
    }

    /** 贴纸 id 收敛：description 优先 → 文件名（去 sticker_/.png）→ name。 */
    private fun stickerIdOf(entry: com.yunian.ai.database.model.StickerEntryEntity): String =
        entry.description?.takeIf { it.isNotBlank() }
            ?: entry.fileName.removePrefix("sticker_").removeSuffix(".png").takeIf { it.isNotBlank() }
            ?: entry.fileName

    private fun stickerIdOf(sticker: StickerInfo): String =
        sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name

    /** 落库单条贴纸/文本消息并做微信广播；贴纸内容统一编码为 `[id]`。 */
    private suspend fun enqueueStickerMessage(rawId: String): Long {
        val content = "[$rawId]"
        val message = ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = false,
            timestamp = System.currentTimeMillis(),
        )
        val msgId = messageWriter.enqueueChat(message)
        if (msgId > 0) broadcastWeChatMessage(msgId, content)
        return msgId
    }
}
