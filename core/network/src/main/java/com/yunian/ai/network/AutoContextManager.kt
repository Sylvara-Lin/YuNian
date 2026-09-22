package com.yunian.ai.network

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.domain.AiOperationalMessages
import com.yunian.ai.domain.ConversationScope

/**
 * 上下文组装器（三层重构版）：
 *
 * 预算顺序：totalBudget → system 全额 → turnContext 全额 → rollingSummary
 * （剩余×0.35，上限 [ChatConstants.SUMMARY_MAX_TOKENS]）→ memory（剩余×0.4）→ history（剩余全部）。
 *
 * 滚动摘要：持久化状态由 [RollingSummaryManager]（AppMetaStore KV）承载。
 * - 有持久化状态：摘要直接用 state.summaryText；history 原文只保留 id > coveredUpToMessageId
 *   的消息（水位线之前的不重复进原文）；装不下的消息作为 pending 达到阈值时
 *   fire-and-forget 触发增量合并。
 * - 无持久化状态：走一次性同步摘要路径（AI 优先、本地兜底，保底摘要一定存在），
 *   并通过 [RollingSummaryManager.mergeBlocking] 把摘要写入 KV 建立水位线。
 *
 * 任何情况下都不丢整条摘要：摘要超预算时先按段落从最旧端丢弃（保留末 2 段），
 * 仍超限才做字符截断。
 */
class AutoContextManager(
    private val aiSummarizer: (suspend (messages: List<ChatMessage>, companionNameMap: Map<Long, String>, memoryContext: String) -> String?)? = null,
    private val rollingSummaryManager: RollingSummaryManager? = null
) {

    data class ContextConfig(
        val model: String,
        val provider: ApiProvider? = null,
        val maxOutputTokens: Int = 4096,
        val safetyMargin: Int = 512
    )

    /** 一次性同步摘要路径的内部产出：原始摘要文本 + 被压缩掉的原始消息。 */
    private data class HistoryBuildResult(
        val messages: List<Message>,
        /** 因预算被裁掉、且 id > 水位线的原始消息（pending，供滚动摘要增量合并）。 */
        val pending: List<ChatMessage>,
        /** 原始（未包装）摘要文本，供首启路径写入 KV；空表示未生成。 */
        val summaryText: String
    )

    suspend fun build(
        history: List<ChatMessage>,
        systemPrompt: String,
        memoryContext: String,
        lastUserMessage: String,
        companionNameMap: Map<Long, String> = emptyMap(),
        config: ContextConfig,
        turnContext: String = "",
        scope: ConversationScope = ConversationScope.Single(-1),
        selfName: String? = null
    ): List<Message> {

        val contextWindow = ModelContextRegistry.getContextWindow(config.model, config.provider)

        val totalBudget = contextWindow - config.maxOutputTokens - config.safetyMargin
        if (totalBudget <= 0) {
            SecureLog.w(TAG, "Budget too small: window=$contextWindow, output=${config.maxOutputTokens}, margin=${config.safetyMargin}")

            return buildMinimalMessages(systemPrompt, history, lastUserMessage)
        }

        val systemTokens = TokenEstimator.estimate(systemPrompt)
        val systemMessage = Message("system", systemPrompt)
        var remainingBudget = totalBudget - systemTokens

        if (remainingBudget <= 0) {
            SecureLog.w(TAG, "System prompt alone exceeds budget ($systemTokens > $totalBudget)")

            val messages = mutableListOf(systemMessage)
            appendLastUserMessage(messages, history, lastUserMessage)
            return messages
        }

        var turnMessage: Message? = null
        if (turnContext.isNotBlank()) {
            val turnTokens = TokenEstimator.estimate(turnContext)
            if (turnTokens <= remainingBudget) {
                turnMessage = Message("system", turnContext)
                remainingBudget -= turnTokens
            } else {
                SecureLog.w(TAG, "Turn context exceeds budget, dropping it ($turnTokens > $remainingBudget)")
            }
        }

        // ---------- rollingSummary 层 ----------
        // scope 不可用（默认占位 Single(-1)）时视同无持久化状态，走一次性路径且不落 KV。
        val persistedState = rollingSummaryManager
            ?.takeIf { isUsableScope(scope) }
            ?.loadState(scope)

        // 陈旧防护：上下文里最旧一条消息的 id 远超水位线（超过 ROLLING_STALE_GAP），
        // 说明会话历史被清空重建过 → 旧滚动摘要作废，回退首启路径。
        var rollingState = persistedState
        if (rollingState != null) {
            val oldestId = history.firstOrNull()?.id ?: 0L
            if (oldestId > rollingState.coveredUpToMessageId + ChatConstants.ROLLING_STALE_GAP) {
                SecureLog.w(
                    TAG,
                    "Stale rolling summary detected (oldest=$oldestId, watermark=${rollingState.coveredUpToMessageId}), resetting"
                )
                rollingSummaryManager?.clear(scope)
                rollingState = null
            }
        }

        var summaryMessage: Message? = null
        if (rollingState != null && rollingState.summaryText.isNotBlank()) {
            val summaryBudget = (remainingBudget * SUMMARY_BUDGET_RATIO).toInt()
                .coerceAtMost(ChatConstants.SUMMARY_MAX_TOKENS)
            if (summaryBudget > 0) {
                val summaryText = compressSummaryToBudget(rollingState.summaryText, summaryBudget)
                if (summaryText.isNotBlank()) {
                    val wrapped = formatRollingSummaryMessage(rollingState, summaryText)
                    val summaryTokens = TokenEstimator.estimate(wrapped)
                    summaryMessage = Message("system", wrapped)
                    remainingBudget -= summaryTokens.coerceAtMost(summaryBudget)
                }
            }
        }

        // ---------- memory 层 ----------
        val memoryBudget = (remainingBudget * MEMORY_BUDGET_RATIO).toInt()
        val memoryTokens = TokenEstimator.estimate(memoryContext)
        var memoryMessage: Message? = null
        if (memoryContext.isNotBlank() && memoryTokens <= memoryBudget) {
            memoryMessage = Message("system", memoryContext)
            remainingBudget -= memoryTokens
        } else if (memoryContext.isNotBlank()) {

            val trimmedMemory = trimMemoryToBudget(memoryContext, memoryBudget)
            if (trimmedMemory.isNotBlank()) {
                memoryMessage = Message("system", trimmedMemory)
                remainingBudget -= TokenEstimator.estimate(trimmedMemory)
                SecureLog.d(TAG, "Memory context trimmed: $memoryTokens -> ${TokenEstimator.estimate(trimmedMemory)} tokens")
            }
        }

        // ---------- history 层（剩余全部预算） ----------
        val historyBudget = remainingBudget
        // 有水位线时原文只保留 id > coveredUpToMessageId 的消息（不重复进原文）
        val effectiveHistory = if (rollingState != null) {
            history.filter { it.id > rollingState!!.coveredUpToMessageId }
        } else {
            history
        }
        val historyResult = buildHistoryMessages(
            effectiveHistory,
            historyBudget,
            companionNameMap,
            memoryContext,
            oneShotSummaryEnabled = rollingState == null
        )

        // 无持久化状态的首启路径：一次性同步摘要已生成（AI/本地兜底，保底存在），
        // 通过 mergeBlocking 写入 KV 建立水位线（prewrittenText 直接落库，避免重复调 LLM）。
        if (rollingState == null && rollingSummaryManager != null && isUsableScope(scope) &&
            historyResult.pending.isNotEmpty()
        ) {
            val batchEndId = historyResult.pending.lastOrNull()?.id ?: 0L
            if (batchEndId > 0L) {
                rollingSummaryManager.mergeBlocking(
                    scope = scope,
                    delta = historyResult.pending,
                    batchEndId = batchEndId,
                    companionNameMap = companionNameMap,
                    memoryContext = memoryContext,
                    selfName = selfName,
                    prewrittenText = historyResult.summaryText.takeIf { it.isNotBlank() }
                )
            }
        }

        // 有持久化状态：被裁掉的 pending 消息达到触发阈值时，fire-and-forget 增量合并
        if (rollingState != null && rollingSummaryManager != null && historyResult.pending.isNotEmpty()) {
            val pendingText = formatPendingForEstimate(historyResult.pending, companionNameMap)
            val pendingTokens = TokenEstimator.estimate(pendingText)
            if (historyResult.pending.size >= ChatConstants.ROLLING_MERGE_MIN_MESSAGES ||
                pendingTokens >= ChatConstants.ROLLING_MERGE_MIN_TOKENS
            ) {
                rollingSummaryManager.requestIncrementalMerge(
                    scope = scope,
                    delta = historyResult.pending,
                    batchEndId = historyResult.pending.last().id,
                    companionNameMap = companionNameMap,
                    memoryContext = memoryContext,
                    selfName = selfName
                )
            }
        }

        val messages = mutableListOf(systemMessage)
        summaryMessage?.let { messages.add(it) }
        messages.addAll(historyResult.messages)
        turnMessage?.let { messages.add(it) }
        memoryMessage?.let { messages.add(it) }
        appendLastUserMessage(messages, history, lastUserMessage)

        SecureLog.api(
            "CONTEXT",
            "AutoContext: window=$contextWindow, budget=$totalBudget, system=$systemTokens, " +
                "rolling=${rollingState != null}, history=${historyResult.messages.size} msgs, total=${messages.size} msgs"
        )

        return messages
    }

    private suspend fun buildHistoryMessages(
        history: List<ChatMessage>,
        budgetTokens: Int,
        companionNameMap: Map<Long, String>,
        memoryContext: String,
        oneShotSummaryEnabled: Boolean
    ): HistoryBuildResult {
        if (history.isEmpty()) return HistoryBuildResult(emptyList(), emptyList(), "")

        val filtered = history.filterNot { msg ->
            val text = msg.content.replace("\u200B", "").trim()
            text.isBlank() || AiOperationalMessages.isOperationalContent(text)
        }
        if (filtered.isEmpty()) return HistoryBuildResult(emptyList(), emptyList(), "")

        val allMessages = filtered.map { msg ->
            val content = formatMessageContent(msg)
            val role = when {
                content.startsWith("[工具调用结果]") -> "user"
                msg.isFromUser -> "user"
                else -> "assistant"
            }
            Message(role = role, content = content)
        }
        val totalTokens = TokenEstimator.estimate(allMessages)

        if (totalTokens <= budgetTokens) {
            return HistoryBuildResult(allMessages, emptyList(), "")
        }

        val keepMessages = mutableListOf<Message>()
        var usedTokens = 0

        for (msg in allMessages.reversed()) {
            val msgTokens = TokenEstimator.estimate(listOf(msg))
            if (usedTokens + msgTokens > budgetTokens) break
            keepMessages.add(0, msg)
            usedTokens += msgTokens
        }

        val compressedCount = allMessages.size - keepMessages.size
        val droppedRaw = if (compressedCount > 0) filtered.dropLast(keepMessages.size) else emptyList()

        if (compressedCount > 0 && oneShotSummaryEnabled) {
            // 一次性同步摘要路径（AI 优先、本地兜底，保底摘要一定存在）
            val rawSummary = summarizeMessages(droppedRaw, companionNameMap, memoryContext)
            if (rawSummary.isNotBlank()) {
                val summaryMessage = Message("system", formatOneShotSummaryMessage(rawSummary, droppedRaw.size))
                val summaryTokens = TokenEstimator.estimate(listOf(summaryMessage))

                if (usedTokens + summaryTokens <= budgetTokens) {
                    SecureLog.api("CONTEXT", "Auto-compressed $compressedCount old messages into summary (${rawSummary.length} chars)")
                    return HistoryBuildResult(listOf(summaryMessage) + keepMessages, droppedRaw, rawSummary)
                } else {

                    SecureLog.w(TAG, "Summary too large to fit, dropping compression")
                    return HistoryBuildResult(keepMessages, droppedRaw, rawSummary)
                }
            }
        }

        return HistoryBuildResult(keepMessages, droppedRaw, "")
    }

    /**
     * 一次性同步摘要（无缓存版）：旧 LRU summaryCache 已移除，
     * 摘要的复用由持久化滚动摘要状态（KV + 水位线）承担。
     * 返回原始摘要文本（不含包装标题）。
     */
    private suspend fun summarizeMessages(
        messages: List<ChatMessage>,
        companionNameMap: Map<Long, String>,
        memoryContext: String
    ): String {
        if (messages.isEmpty()) return ""

        if (aiSummarizer != null) {
            try {
                val aiSummary = aiSummarizer(messages, companionNameMap, memoryContext)
                if (!aiSummary.isNullOrBlank()) {
                    SecureLog.api("CONTEXT", "AI summary generated: ${aiSummary.length} chars")
                    return aiSummary.trim()
                }
            } catch (e: Exception) {
                SecureLog.w(TAG, "AI summary failed, falling back to local: ${e.message}")
            }
        }

        return AiContextTools.buildLocalSummary(messages, companionNameMap, memoryContext)
    }

    /**
     * 滚动摘要注入格式（KV 只存裸文本，组装时在此包装）。
     */
    private fun formatRollingSummaryMessage(state: RollingSummaryState, summaryText: String): String {
        return "=== 对话进展摘要（已压缩${state.coveredMessageCount}条，更新至消息#${state.coveredUpToMessageId}） ===\n$summaryText"
    }

    private fun formatOneShotSummaryMessage(summaryText: String, compressedCount: Int): String {
        return "=== 早期对话摘要（已压缩${compressedCount}条消息） ===\n$summaryText"
    }

    private fun formatPendingForEstimate(messages: List<ChatMessage>, companionNameMap: Map<Long, String>): String {
        return messages.joinToString("\n") { msg ->
            val sender = if (msg.isFromUser) "用户" else (companionNameMap[msg.companionId] ?: "AI")
            "$sender：${msg.content}"
        }
    }

    /**
     * 摘要压缩到预算：按 `\n` 段落切分，从头部（最旧）丢弃直到达标，
     * 但至少保留末 2 段；丢弃过内容时首行加 `[更早内容已省略]` 标记；
     * 仍超限才字符截断（[truncateToTokens]）——任何情况不丢整条摘要。
     */
    internal fun compressSummaryToBudget(text: String, budgetTokens: Int): String {
        if (text.isBlank() || budgetTokens <= 0) return ""
        if (TokenEstimator.estimate(text) <= budgetTokens) return text

        val paragraphs = text.split("\n")
        var kept = paragraphs
        var dropped = false
        while (kept.size > SUMMARY_MIN_KEEP_PARAGRAPHS && TokenEstimator.estimate(kept.joinToString("\n")) > budgetTokens) {
            kept = kept.drop(1)
            dropped = true
        }

        var result = if (dropped) "[更早内容已省略]\n${kept.joinToString("\n")}" else kept.joinToString("\n")

        if (TokenEstimator.estimate(result) > budgetTokens) {
            result = truncateToTokens(result, budgetTokens)
        }
        return result
    }

    /**
     * 记忆压缩到预算：按空行分段（无空行退化为按行），按整条从最旧端丢弃直到达标，
     * 永不切半条；若保底留下的最后一条单独超预算，做字符截断兜底（不丢整条）。
     */
    internal fun trimMemoryToBudget(text: String, budgetTokens: Int): String {
        if (text.isBlank() || budgetTokens <= 0) return ""
        if (TokenEstimator.estimate(text) <= budgetTokens) return text

        val separator = Regex("\\n\\s*\\n")
        val segments = if (separator.containsMatchIn(text)) {
            text.split(separator)
        } else {
            text.split("\n")
        }.map { it.trim() }.filter { it.isNotBlank() }

        var kept = segments
        while (kept.size > 1 && TokenEstimator.estimate(kept.joinToString("\n\n")) > budgetTokens) {
            kept = kept.drop(1)
        }

        val result = kept.joinToString("\n\n")
        return if (TokenEstimator.estimate(result) > budgetTokens) {
            truncateToTokens(result, budgetTokens)
        } else {
            result
        }
    }

    private fun formatMessageContent(msg: ChatMessage): String {
        return if (msg.isFromUser && msg.content.startsWith("[") && msg.content.endsWith("]")) {
            val inner = msg.content.removeSurrounding("[", "]")
            val label = when {
                inner.startsWith("sticker_", ignoreCase = true) -> "表情包"
                inner.length > 20 -> "表情包"
                else -> inner
            }
            "用户发送了一个表情包：[$label]"
        } else {
            msg.content
        }
    }

    private fun truncateToTokens(text: String, maxTokens: Int): String {
        if (text.isBlank() || maxTokens <= 0) return ""
        val estimated = TokenEstimator.estimate(text)
        if (estimated <= maxTokens) return text

        val ratio = maxTokens.toFloat() / estimated
        val targetLength = (text.length * ratio * 0.9).toInt().coerceAtLeast(1)
        return text.take(targetLength)
    }

    /** 占位 scope（默认参数 Single(-1)）不参与滚动摘要持久化。 */
    private fun isUsableScope(scope: ConversationScope): Boolean = scope != ConversationScope.Single(-1)

    private fun buildMinimalMessages(
        systemPrompt: String,
        history: List<ChatMessage>,
        lastUserMessage: String
    ): List<Message> {
        val messages = mutableListOf(Message("system", systemPrompt))
        appendLastUserMessage(messages, history, lastUserMessage)
        return messages
    }

    private fun appendLastUserMessage(
        messages: MutableList<Message>,
        history: List<ChatMessage>,
        lastUserMessage: String
    ) {
        val lastMsg = messages.lastOrNull()
        val isLastFromUser = lastMsg?.role == "user"
        if (!isLastFromUser && lastUserMessage.isNotBlank()) {
            messages.add(Message("user", lastUserMessage))
        }
    }

    private companion object {
        const val TAG = "AutoContextManager"

        /** rollingSummary 层占剩余预算的比例。 */
        const val SUMMARY_BUDGET_RATIO = 0.35

        /** memory 层占剩余预算的比例。 */
        const val MEMORY_BUDGET_RATIO = 0.4

        /** 摘要压缩时至少保留的段落数。 */
        const val SUMMARY_MIN_KEEP_PARAGRAPHS = 2
    }
}
