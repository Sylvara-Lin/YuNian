package com.yunian.ai.feature.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunian.ai.common.AppForegroundTracker
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.filterDecrypted
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiReplyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val companionId = inputData.getLong(KEY_COMPANION_ID, -1L)
            val userMessageContent = inputData.getString(KEY_USER_MESSAGE) ?: ""

            if (companionId == -1L || userMessageContent.isBlank()) {
                return@withContext Result.failure()
            }

            if (com.yunian.ai.common.BanManager.isBanned(applicationContext)) {
                return@withContext Result.failure()
            }

            val inputCheck = com.yunian.ai.common.ContentFilter.checkInput(userMessageContent)
            if (inputCheck.isViolating) {
                com.yunian.ai.common.BanManager.recordViolation(applicationContext, inputCheck.level)
                return@withContext Result.failure()
            }

            val database = AppDatabase.getDatabase(applicationContext)
            val companionRepository = CompanionRepository(database.companionDao())
            val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)

            try {
                val companionModel = companionRepository.getCompanionById(companionId)
                if (companionModel == null) {
                    return@withContext Result.failure()
                }

                val history = chatRepository.getRecentMessagesSync(companionId, limit = 50)
                    .filterDecrypted()

                // Agent 回合（Rust Cordis）：通道/后台回复固定单轮，不启用气泡连发协议。
                syncRuntimeConfig()
                val turnRequest = com.yunian.ai.agent.uniffi.AgentTurnRequest(
                    groupId = null,
                    historyJson = serializeHistoryJson(history.toAiChatMessages()),
                    tools = emptyList(),
                    maxRounds = 1u,
                    toolChoice = "auto",
                    stickerProbability = 0u,
                    image = null,
                    systemPrompt = null,
                    companionNameMapJson = null,
                )
                val result = com.yunian.ai.agent.AgentFacade.runTurn(
                    turnRequest,
                    applicationContext,
                    companionId,
                    com.yunian.ai.agent.host.AgentToolHost(applicationContext),
                )
                // 兜底：模型经气泡事件产出内容但 finalText 为空时，从事件流拼接，
                // 避免后台主动回复静默丢失。
                val trimmedResponse = result.finalText.ifBlank {
                    result.events.filter { it.kind == "bubble" && it.text.isNotBlank() }
                        .joinToString("\n") { it.text.trim() }
                }.trim()

                if (trimmedResponse.isNotEmpty()) {

                    val outputSafety = com.yunian.ai.common.ContentFilter.checkOutputSafety(trimmedResponse)
                    val safeResponse = if (!outputSafety.isSafe) {
                        android.util.Log.w("AiReplyWorker", "AI output blocked by safety filter: ${outputSafety.reason}")
                        com.yunian.ai.common.BanManager.recordViolation(applicationContext, outputSafety.level)
                        "抱歉，我无法回应这个话题。"
                    } else {
                        trimmedResponse
                    }

                    // 主动消息同样可能夹带生图标签/画面描述：落库前统一清洗
                    val cleanResponse = ImageGenProtocol.sanitizeForDisplay(safeResponse)
                        .ifBlank { safeResponse }
                    val aiMessage = ChatMessage(
                        companionId = companionId,
                        content = cleanResponse,
                        isFromUser = false
                    )
                    ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java).enqueueChat(aiMessage)
                    companionRepository.updateTimestamp(companionId)
                    companionRepository.increaseIntimacy(companionId, 2)

                    ServiceRegistry.getOrThrow(com.yunian.ai.domain.MemoryProvider::class.java)
                        .extractAndSaveFromConversation(
                            userInput = userMessageContent,
                            aiResponse = safeResponse,
                            companionId = companionId,
                        )

                    if (!AppForegroundTracker.isInForeground) {
                        val notificationPreview = if (cleanResponse.length > 50) {
                            cleanResponse.take(50) + "..."
                        } else cleanResponse
                        NotificationHelper.showCompanionMessageNotification(
                            applicationContext,
                            companionModel.name,
                            notificationPreview,
                            companionId
                        )
                    }
                }
            } finally {
            }

            Result.success()
        } catch (e: IllegalStateException) {
            android.util.Log.e("AiReplyWorker", "Permanent failure, will not retry", e)
            Result.failure()
        } catch (e: SecurityException) {
            android.util.Log.e("AiReplyWorker", "Permission denied, will not retry", e)
            Result.failure()
        } catch (e: Exception) {
            android.util.Log.e("AiReplyWorker", "Transient failure, will retry", e)
            Result.retry()
        }
    }

    /** 同步 Agent 全局配置（settings / stickers / credentials 热更新，对齐单聊与通道中间层）。 */
    private suspend fun syncRuntimeConfig() {
        val stickers = com.yunian.ai.agent.sticker.StickerPreferenceFacade
            .availableTagsWithFallback(applicationContext)
        val partnerSession = com.yunian.ai.common.RemoteKeyProvider
            .getPartnerSession(applicationContext)
        val activeApi = ServiceRegistry
            .getOrThrow(com.yunian.ai.database.repository.ApiConfigRepository::class.java)
            .getActiveEnabledConfig()
        val isPartner = activeApi?.provider == com.yunian.ai.database.model.ApiProvider.PARTNER
        com.yunian.ai.agent.AgentFacade.syncRuntimeConfig(
            applicationContext,
            com.yunian.ai.agent.AgentFacade.buildSettingsJson(role = "GIRLFRIEND"),
            stickers,
            com.yunian.ai.agent.AgentFacade.buildCredentialsJson(
                sessionToken = if (isPartner) partnerSession?.token else null,
                clientId = if (isPartner) partnerSession?.clientId else null,
                apiKey = activeApi?.apiKey?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /** 领域历史 → OpenAI messages JSON（AgentTurnRequest.historyJson）。 */
    private fun serializeHistoryJson(history: List<AiChatMessage>): String {
        val arr = org.json.JSONArray()
        for (msg in history) {
            val role = when (msg.role) {
                com.yunian.ai.domain.AiMessageRole.SYSTEM -> "system"
                com.yunian.ai.domain.AiMessageRole.TOOL -> "tool"
                com.yunian.ai.domain.AiMessageRole.USER -> "user"
                com.yunian.ai.domain.AiMessageRole.ASSISTANT -> "assistant"
                null -> if (msg.isFromUser) "user" else "assistant"
            }
            val m = org.json.JSONObject().apply {
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

    private fun ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser, content = content, timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId
    )

    private fun List<ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }

    companion object {
        private const val WORK_NAME_PREFIX = "ai_reply_"
        const val KEY_COMPANION_ID = "companion_id"
        const val KEY_USER_MESSAGE = "user_message"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context, companionId: Long, userMessage: String) {
            val inputData = Data.Builder()
                .putLong(KEY_COMPANION_ID, companionId)
                .putString(KEY_USER_MESSAGE, userMessage)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<AiReplyWorker>()
                .setInputData(inputData)
                .setConstraints(networkConstraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$companionId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )
        }
    }
}
