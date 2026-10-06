package com.yunian.ai.agent

import android.content.Context
import com.yunian.ai.agent.host.AgentToolHost
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.agent.uniffi.AgentTurnRequest
import com.yunian.ai.agent.uniffi.AgentTurnResult
import com.yunian.ai.agent.uniffi.ImageInput
import com.yunian.ai.common.BanManager
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.RemoteKeyProvider
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerManager
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.filterDecrypted
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiMessageRole
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.DialogueRequest
import com.yunian.ai.domain.DialogueResult
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.dialogue.DialogueTurnSnapshot
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一 AI 对话中间层（core:agent 实现，app 经 ServiceRegistry 绑定）。
 *
 * 职责（「全包」）：落库用户消息 → 读历史(30)
 * → syncRuntimeConfig → [AgentFacade.runTurn]（文本 / 视觉）
 * → 落库 AI 回复 → updateTimestamp / increaseIntimacy(2) / 记忆提取。
 *
 * 通道桥接层（微信 / QQ）只做消息收发，不触碰任何 AI / 安全 / 落库逻辑。
 */
class AgentDialogueCoordinator(
    private val appContext: Context,
) : DialogueCoordinator {

    private val context: Context get() = appContext

    private val chatRepository: ChatRepository
        get() = ServiceRegistry.getOrThrow(ChatRepository::class.java)

    private val messageWriter: MessageWriteCoordinator
        get() = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)

    private val companionRepository: CompanionRepository
        get() = ServiceRegistry.getOrThrow(CompanionRepository::class.java)

    // 合并自 origin/linzihan：远端已删除 @Deprecated 的 MemoryRepository
    // （memory_entries / temp_memory 旧仓），统一走 core:domain 的 MemoryProvider
    // （实现 = UnifiedMemoryProvider，内部写 unified_memory 并自动同步 GLOBAL 作用域）。
    // 与 ChatGenerationManager / GroupChatViewModel 保持同一接线模式。
    private val memoryProvider: MemoryProvider by lazy {
        ServiceRegistry.getOrThrow(MemoryProvider::class.java).also { it.initialize() }
    }

    private val apiConfigRepository: ApiConfigRepository
        get() = ServiceRegistry.getOrThrow(ApiConfigRepository::class.java)

    /** 确认门守卫（Commerce 工具在无确认界面的通道回合上 fail-closed 自动拒绝 + 有限次重跑）。 */
    private val agentConfirmGuard = AgentConfirmGuard()

    override suspend fun generateReply(request: DialogueRequest): DialogueResult =
        withContext(Dispatchers.IO) {
            val companionId = request.companionId
            val companion = companionRepository.getCompanionById(companionId)
                ?: return@withContext DialogueResult(replyText = "", blocked = true)

            // 封禁态：与旧桥接层（微信/QQ）一致，封禁期间不产生任何 AI 回合。
            // 安全检查收敛在本中间层，通道桥接层不再自行判定（Plan §7.2 / 待办 C）。
            if (BanManager.isBanned(context)) {
                SecureLog.w(TAG, "generateReply blocked: device banned, companion=$companionId")
                return@withContext DialogueResult(replyText = "", blocked = true)
            }

            val imagePath = request.imagePath
            // 通道身份由调用方声明（DialogueRequest.channelKey）——本中间层无法从
            // companionId / 文本推断消息是从 QQ 还是微信来的。该字段是**通道自身的身份**
            // （通道侧日志 / 观测；通道插件化时每个插件显式声明自己是谁），
            // 能力预授权白名单（core:domain CapabilityGrantStore）**只按 (伴侣 × 工具) 命中，
            // 不含通道维度**，装配入口见 runTurn 里的 AgentFacade.toolDefinitionsFor。
            // 因此本方法**不再把 channelKey 往下传**：本文件内没有任何一处读它（见 runTurn KDoc）。
            if (imagePath != null) {
                return@withContext generateVisionReply(companionId, companion, imagePath)
            }

            val text = request.text?.trim().orEmpty()
            if (text.isBlank()) {
                return@withContext DialogueResult(replyText = "", blocked = true)
            }
            generateTextReply(companionId, companion, text)
        }

    // ── 文本对话 ──

    private suspend fun generateTextReply(
        companionId: Long,
        companion: CompanionEntity,
        text: String,
    ): DialogueResult {
        // 输入侧安全过滤：与本地 ContentFilter 基线保持一致（Rust 侧尚未下沉，见待办 C）
        val inputCheck = ContentFilter.checkInput(text)
        if (inputCheck.isViolating) {
            SecureLog.w(TAG, "Input blocked by safety filter: ${inputCheck.level} - ${inputCheck.reason}")
            BanManager.recordViolation(context, inputCheck.level)
            return blockedReply(companionId, "抱歉，我无法处理这个话题。")
        }

        val userMessage = ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
        )
        messageWriter.enqueueChat(userMessage)
        companionRepository.updateTimestamp(companionId)

        val history = chatRepository.getRecentMessagesSync(companionId, limit = 30)
            .filterDecrypted()

        // runTurn 返回 null = Agent 回合根本没跑起来；outcome.replyText 为 null = 本轮无可见文本。
        // 两条分支的文案 / blocked 与改动前逐字一致（原先由同一个 `?:` 兜住两者）。
        val outcome = runTurn(companionId, history, imagePath = null)
            ?: return textTurnFailedReply()
        val aiTextRaw = outcome.replyText ?: return textTurnFailedReply()

        // 画面描述绝不能出现在消息或聊天记录里：统一走 ImageGenProtocol 清洗
        val aiText = sanitizeImageGen(aiTextRaw)

        if (aiText.isNotBlank()) {
            val outputSafety = ContentFilter.checkOutputSafety(aiText)
            if (!outputSafety.isSafe) {
                SecureLog.w(TAG, "AI output blocked by safety filter: ${outputSafety.level} - ${outputSafety.reason}")
                BanManager.recordViolation(context, outputSafety.level)
                return blockedReply(companionId, "抱歉，我无法回应这个话题。")
            }
        }

        val contentToStore = aiText.ifBlank { "API返回空内容" }
        val aiMessageId = messageWriter.enqueueChat(
            ChatMessage(
                companionId = companionId,
                content = contentToStore,
                isFromUser = false,
                timestamp = System.currentTimeMillis(),
            )
        )

        if (aiMessageId > 0) {
            companionRepository.updateTimestamp(companionId)
            companionRepository.increaseIntimacy(companionId, 2)
            runCatching {
                memoryProvider.extractAndSaveFromConversation(
                    userInput = text,
                    aiResponse = aiText,
                    companionId = companionId,
                )
            }.onFailure {
                SecureLog.e(TAG, "Memory save failed: ${it.message}")
            }
        }

        return DialogueResult(
            replyText = aiText,
            blocked = false,
            assistantMessageId = aiMessageId.takeIf { it > 0 },
            // 结构化投影（P3-3b）：发生在确认门守卫之后、ImageGenProtocol 清洗与
            // ContentFilter 输出过滤之后——快照里的每段文本都出自上面那条已过滤的 aiText。
            turn = projectTurn(outcome, aiText),
        )
    }

    // ── 视觉对话 ──

    private suspend fun generateVisionReply(
        companionId: Long,
        companion: CompanionEntity,
        imagePath: String,
    ): DialogueResult {
        val userMessage = ChatMessage(
            companionId = companionId,
            content = imagePath,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
            type = MessageType.IMAGE,
            linkString = imagePath,
        )
        messageWriter.enqueueChat(userMessage)
        companionRepository.updateTimestamp(companionId)

        // 图片消息暂无法做输入内容审核
        val history = chatRepository.getRecentMessagesSync(companionId, limit = 30)
            .filterDecrypted()

        val outcome = runTurn(companionId, history, imagePath = imagePath)
            ?: return visionTurnFailedReply()
        val aiTextRaw = outcome.replyText ?: return visionTurnFailedReply()

        val aiText = sanitizeImageGen(aiTextRaw)

        if (aiText.isNotBlank()) {
            val outputSafety = ContentFilter.checkOutputSafety(aiText)
            if (!outputSafety.isSafe) {
                SecureLog.w(TAG, "Vision AI output blocked by safety filter: ${outputSafety.level} - ${outputSafety.reason}")
                BanManager.recordViolation(context, outputSafety.level)
                return blockedReply(companionId, "抱歉，我无法回应这个话题。")
            }
        }

        val contentToStore = aiText.ifBlank { "API返回空内容" }
        val aiMessageId = messageWriter.enqueueChat(
            ChatMessage(
                companionId = companionId,
                content = contentToStore,
                isFromUser = false,
                timestamp = System.currentTimeMillis(),
            )
        )

        if (aiMessageId > 0) {
            companionRepository.updateTimestamp(companionId)
            companionRepository.increaseIntimacy(companionId, 2)
            runCatching {
                // 视觉轮次无文本输入，用图片路径作为「用户输入」写入工作记忆。
                memoryProvider.extractAndSaveFromConversation(
                    userInput = imagePath,
                    aiResponse = aiText,
                    companionId = companionId,
                )
            }.onFailure {
                SecureLog.e(TAG, "Memory save failed: ${it.message}")
            }
        }

        return DialogueResult(
            replyText = aiText,
            blocked = false,
            assistantMessageId = aiMessageId.takeIf { it > 0 },
            // 视觉回合与文本回合共用同一套投影（同一份已清洗 + 已过滤的 aiText）。
            turn = projectTurn(outcome, aiText),
        )
    }

    // ── Agent 回合 ──

    /**
     * 通道对话同样消费 Native 工具事件；技能/记忆调用后必须留出回复轮次。
     *
     * 返回 [TurnOutcome]：文本兜底（[AgentTurnReplyText.resolve]，与改动前逐字一致）**加上**
     * 守卫收束后的原始 [AgentTurnResult]，供调用方在清洗 + 过滤之后做结构化投影。
     * 返回 null 的语义与改动前一致：Agent 回合根本没跑起来（`AgentFacade.runTurn` 抛错 / 返回 null）。
     *
     * ## 为什么这里**没有** channelKey 参数
     * 本方法曾经接收 `channelKey`，但函数体**从未读取它**——它只被
     * [generateTextReply] / [generateVisionReply] 原样转发进来。通道身份只在入口处
     * （`DialogueRequest.channelKey`）有意义：那是**通道自身的身份**，用于通道侧日志 /
     * 观测，以及通道插件化时「每个插件显式声明自己是谁」。
     *
     * 授权侧与它无关：工具装配的唯一入口是 `AgentFacade.toolDefinitionsFor`，它按
     * (伴侣 × 工具) 折叠（`CapabilityGrantStore.decisionsFor(companionId)` 的签名里
     * 根本没有通道参数），**不含通道维度**。
     *
     * 因此保留一个谁都读不到的参数只会制造「本中间层是通道感知的」这一**假信号**：
     * 读代码的人会以为通道差异在此处生效。P3 若真需要它，加回来的成本是一行参数
     * ——比留下一个恒被忽略的形参便宜得多。
     */
    private suspend fun runTurn(
        companionId: Long,
        history: List<com.yunian.ai.database.model.ChatMessage>,
        imagePath: String?,
    ): TurnOutcome? {
        syncRuntimeConfig()
        val request = AgentTurnRequest(
            groupId = null,
            historyJson = serializeHistoryJson(history.map { it.toAiChatMessage() }),
            // 工具装配的**唯一入口**：AgentFacade.toolDefinitionsFor 同时完成
            // 「领域 AiTool → Rust ToolDefinition」与「工具授权折叠」。
            // 授权只按 (伴侣 × 工具) 命中，**不含通道维度**。通道身份（`DialogueRequest.channelKey`）
            // 在入口处即被消费，不进本方法（见本函数 KDoc「为什么这里没有 channelKey 参数」）。
            // 无决定 / 存储读失败时其 category 与本改动前逐字一致（fail-closed）。
            tools = (AgentFacade.memoryToolDefinitions(context) +
                AgentFacade.skillToolDefinitions() +
                AgentFacade.toolDefinitionsFor(
                    companionId = companionId,
                    tools = com.yunian.ai.domain.ToolRegistry.availableTools(),
                ))
                .distinctBy { it.name },
            maxRounds = 6u,
            toolChoice = "auto",
            stickerProbability = 0u,
            image = imagePath?.let { ImageInput(path = it, base64Data = null, mimeType = null) },
            systemPrompt = null,
            companionNameMapJson = null,
        )
        val toolHost = AgentToolHost(context)
        var result = runCatching {
            AgentFacade.runTurn(request, context, companionId, toolHost)
        }.onFailure {
            SecureLog.e(TAG, "runTurn failed, companion=$companionId", it)
        }.getOrNull() ?: return null
        // Commerce 类工具的确认门在通道侧没有界面可确认：默认拒绝（一次性）后重跑回合，
        // 让模型改用文字回应，而不是把用户晾在「需要确认」上。循环语义收在 [AgentConfirmGuard]
        // （群聊共用同一份实现），此处只做接线，tag / 文案 / 上限均与抽取前逐字一致。
        result = agentConfirmGuard.drive(
            tag = TAG,
            initial = result,
            rerunFailureMessage = "runTurn after auto-reject failed, companion=$companionId",
            runTurn = { AgentFacade.runTurn(request, context, companionId, toolHost) },
            rejectTool = { name, args -> AgentFacade.rejectTool(context, name, args) },
        )
        return TurnOutcome(
            replyText = AgentTurnReplyText.resolve(result.events, result.finalText, result.finishedReason),
            agentResult = result,
        )
    }

    private suspend fun syncRuntimeConfig() {
        val stickers = StickerPreferenceFacade.availableTagsWithFallback(context)
        val partnerSession = RemoteKeyProvider.getPartnerSession(context)
        // Rust 无法解密 SQLite 中的 Tink 加密 API Key，Kotlin 解密后经 credentials 传入
        val activeApi = apiConfigRepository.getActiveEnabledConfig()
        val decryptedKey = activeApi?.apiKey?.takeIf { it.isNotBlank() }
        // 认证分离：session / client_id 只用于内置 Clove API（PARTNER）；
        // 其他 provider 走 OpenAI 标准 Bearer，不传 session。
        val isPartner = activeApi?.provider == com.yunian.ai.database.model.ApiProvider.PARTNER
        AgentFacade.syncRuntimeConfig(
            context,
            AgentFacade.buildSettingsJson(
                role = "GIRLFRIEND",
                reasoningEffort = com.yunian.ai.common.AppSettingsStore(context).getReasoningEffort(),
            ),
            stickers,
            AgentFacade.buildCredentialsJson(
                sessionToken = if (isPartner) partnerSession?.token else null,
                clientId = if (isPartner) partnerSession?.clientId else null,
                apiKey = decryptedKey,
            ),
        )
    }

    /** 领域历史 → OpenAI messages JSON（AgentTurnRequest.historyJson）。 */
    private fun serializeHistoryJson(history: List<AiChatMessage>): String {
        val arr = JSONArray()
        for (msg in history) {
            val role = when (msg.role) {
                AiMessageRole.SYSTEM -> "system"
                AiMessageRole.TOOL -> "tool"
                AiMessageRole.USER -> "user"
                AiMessageRole.ASSISTANT -> "assistant"
                null -> if (msg.isFromUser) "user" else "assistant"
            }
            val m = JSONObject().apply {
                put("role", role)
                put("content", msg.content)
            }
            if (role == "tool" && !msg.toolName.isNullOrBlank()) {
                m.put("name", msg.toolName)
            }
            arr.put(m)
        }
        return arr.toString()
    }

    /**
     * 剥离生图标签/画面描述（与 App 内聊天、旧通道桥接层同一份清洗逻辑）。
     * 剥离后为空且原文仅为画面描述时，返回占位文案（不含方括号，避免被当作表情包标签）。
     */
    private fun sanitizeImageGen(raw: String): String {
        val stripped = ImageGenProtocol.sanitizeForDisplay(raw)
        return when {
            stripped.isNotBlank() -> stripped
            ImageGenProtocol.isPromptOnly(raw) -> IMAGE_GEN_ONLY_REPLY_TEXT
            else -> raw
        }
    }

    /**
     * 安全拦截回复：落库 + 返回 blocked 结果。
     *
     * **不携带任何回合快照**（`turn` 保持默认 null）：拦截路径绝不允许把未过滤的原始事件文本
     * 带出去，调用方只能发送这里写死的安全话术。
     */
    private suspend fun blockedReply(companionId: Long, text: String): DialogueResult {
        val blockedId = messageWriter.enqueueChat(
            ChatMessage(
                companionId = companionId,
                content = text,
                isFromUser = false,
                timestamp = System.currentTimeMillis(),
            )
        )
        return DialogueResult(
            replyText = text,
            blocked = true,
            assistantMessageId = blockedId.takeIf { it > 0 },
        )
    }

    private fun com.yunian.ai.database.model.ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser,
        content = content,
        timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId,
    )

    private fun List<com.yunian.ai.database.model.ChatMessage>.toAiChatMessages() =
        map { it.toAiChatMessage() }

    /**
     * 结构化投影的**安全出口**（P3-3b）。
     *
     * 快照是新增契约，投影失败不得影响既有回合（与记忆提取同一手法：runCatching + 记日志 + 降级）：
     * 失败 → `turn = null`，调用方回退 [DialogueResult.replyText]，行为与改动前一致。
     * 降级方向只会「少给快照」，不会「多给未过滤文本」。
     */
    private fun projectTurn(outcome: TurnOutcome, aiText: String): DialogueTurnSnapshot? =
        runCatching {
            DialogueTurnMapper.project(result = outcome.agentResult, cleanedReplyText = aiText)
        }.onFailure {
            SecureLog.w(TAG, "turn projection failed, fallback to replyText: ${it.message}")
        }.getOrNull()

    /** 文本回合失败的统一结果（Agent 抛错 / 本轮无可见文本）：blocked 且不带回合快照。 */
    private fun textTurnFailedReply() = DialogueResult(
        replyText = "抱歉，我暂时无法处理这条消息。",
        blocked = true,
    )

    /** 视觉回合失败的统一结果：既有语义是 `blocked = false`，逐字保留。 */
    private fun visionTurnFailedReply() = DialogueResult(
        replyText = "图片识别过程中出现错误，请稍后重试或发送文字描述。",
        blocked = false,
    )

    /**
     * 一轮 Agent 回合的载体：文本兜底 + 守卫收束后的原始结果。
     *
     * [replyText] 为 null 表示 [AgentTurnReplyText.resolve] 判定本轮没有可见文本（调用方走失败兜底）；
     * [agentResult] **只在投影时使用**（[DialogueTurnMapper.project]），不得直接外发。
     */
    private class TurnOutcome(
        val replyText: String?,
        val agentResult: AgentTurnResult,
    )

    companion object {
        private const val TAG = "AgentDialogueCoordinator"

        /** 模型整条回复只有画面描述时的占位文案（不含方括号，避免被当成表情包标签） */
        private const val IMAGE_GEN_ONLY_REPLY_TEXT = "（正在为你配图…）"
    }
}
