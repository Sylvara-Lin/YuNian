package com.yunian.ai.agent

import android.content.Context
import com.yunian.ai.agent.host.AgentToolHost
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.agent.uniffi.AgentTurnRequest
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

        val aiTextRaw = runTurn(companionId, history, imagePath = null)
            ?: return DialogueResult(replyText = "抱歉，我暂时无法处理这条消息。", blocked = true)

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

        val aiTextRaw = runTurn(companionId, history, imagePath = imagePath)
            ?: return DialogueResult(
                replyText = "图片识别过程中出现错误，请稍后重试或发送文字描述。",
                blocked = false,
            )

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
        )
    }

    // ── Agent 回合 ──

    /**
     * 通道对话回合：单轮 run_turn（无气泡连发协议 / 无会话工具，与旧
     * AiService.sendMessage 行为对齐），Rust 侧仍会注入全局工具（builtin + global）。
     */
    private suspend fun runTurn(
        companionId: Long,
        history: List<com.yunian.ai.database.model.ChatMessage>,
        imagePath: String?,
    ): String? {
        syncRuntimeConfig()
        val request = AgentTurnRequest(
            groupId = null,
            historyJson = serializeHistoryJson(history.map { it.toAiChatMessage() }),
            tools = emptyList(),
            maxRounds = 1u,
            toolChoice = "auto",
            stickerProbability = 0u,
            image = imagePath?.let { ImageInput(path = it, base64Data = null, mimeType = null) },
            systemPrompt = null,
            companionNameMapJson = null,
        )
        return runCatching {
            AgentFacade.runTurn(request, context, companionId, AgentToolHost(context))
        }.onFailure {
            SecureLog.e(TAG, "runTurn failed, companion=$companionId", it)
        }.getOrNull()?.finalText
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

    /** 安全拦截回复：落库 + 返回 blocked 结果。 */
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

    companion object {
        private const val TAG = "AgentDialogueCoordinator"

        /** 模型整条回复只有画面描述时的占位文案（不含方括号，避免被当成表情包标签） */
        private const val IMAGE_GEN_ONLY_REPLY_TEXT = "（正在为你配图…）"
    }
}
