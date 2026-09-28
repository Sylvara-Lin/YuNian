package com.yunian.ai.feature.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.agent.host.AgentToolHost
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.agent.uniffi.AgentTurnRequest
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.ChatMessageCrypto
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.filterDecrypted
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.ProactiveMessageSettings
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import com.yunian.ai.domain.wechat.WeChatProactiveSync
import com.yunian.ai.common.AppForegroundTracker
import com.yunian.ai.common.BanManager
import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.ChatDetailSettingsDataStoreProvider
import com.yunian.ai.common.RemoteKeyProvider
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.text.BubbleTextSplitter
import com.yunian.ai.common.text.DedupGuard
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Serializable
data class ProactiveSettings(
    val proactiveEnabled: Boolean = true,

    val proactiveIntervalMinutes: Int = 180,
    val proactiveMinIntervalMinutes: Int = 60,
    val proactiveMaxIntervalMinutes: Int = 720,
    val proactiveDailyLimit: Int = 6,

    val allowNewTopic: Boolean = true,

    val allowFollowUpMessage: Boolean = true,
    val doNotDisturbEnabled: Boolean = false,
    val dndStartMinutes: Int = 23 * 60,
    val dndEndMinutes: Int = 8 * 60,
    val allowLateNightMessage: Boolean = false,
    val allowPriorityMessageInDnd: Boolean = false,
    val blocked: Boolean = false,

    val followUpReminderEnabled: Boolean = true,

    val followUpReminderIntervalMinutes: Int = 5,

    val followUpReminderMaxTimes: Int = 3
)

class CompanionMessageWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * 仅用于 [AiServiceProvider.shouldProactivelyMessage]（纯本地时间/角色判定，无网络调用）。
     * 主动消息 / 跟进追问的 **LLM 生成** 已改走 Rust Cordis Agent（[AgentFacade.runTurn]）。
     */
    private val aiServiceProvider: AiServiceProvider by lazy {
        ServiceRegistry.get(AiServiceProvider::class.java)
            ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    }

    private val apiConfigRepository: ApiConfigRepository
        get() = ServiceRegistry.getOrThrow(ApiConfigRepository::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (BanManager.isBanned(context)) {
                SecureLog.d("CompanionMessageWorker", "User is banned, skip proactive message")
                return@withContext Result.success()
            }

            val database = AppDatabase.getDatabase(context)
            val companionDao = database.companionDao()
            val messageDao = database.messageDao()

            val companions = companionDao.getAllCompanionsSync()
            if (companions.isEmpty()) return@withContext Result.success()

            val settingsById = readAllCompanionSettings()

            val eligibleCompanions = companions.filter { companion ->
                settingsById[companion.id]?.let { settings ->
                    settings.proactiveEnabled && !settings.blocked
                } ?: false
            }

            if (eligibleCompanions.isEmpty()) {
                SecureLog.d("CompanionMessageWorker", "No eligible companions (all disabled/blocked), reschedule")
                scheduleNext(context, null)
                return@withContext Result.success()
            }

            val now = System.currentTimeMillis()
            val nowCal = java.util.Calendar.getInstance()
            val nowMinutes = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)

            val dueFollowUpCompanions = eligibleCompanions.filter { companion ->
                val s = settingsById[companion.id] ?: return@filter false
                if (!s.followUpReminderEnabled) return@filter false

                if (isInDndRange(nowMinutes, s) && !s.allowLateNightMessage) return@filter false

                if (s.proactiveDailyLimit > 0 && getTodayProactiveCount(context, companion.id) >= s.proactiveDailyLimit) {
                    return@filter false
                }
                // 排除工具调用卡片（TOOL_ACTIVITY）：卡片是本轮最后落库的消息，
                // 不能当作「最后一条对话消息」参与追问判定（否则会干扰 isFromUser/时间/计数判断）。
                val last = runCatching {
                    messageDao.getRecentMessagesSync(companion.id, "chat", 10)
                        .map { it.toChatMessage() }
                        .filter { it.type != MessageType.TOOL_ACTIVITY }
                        .maxByOrNull { it.timestamp }
                }
                    .getOrNull() ?: return@filter false
                if (last.isFromUser) return@filter false
                val elapsedMs = now - last.timestamp

                if (elapsedMs >= ChatConstants.FOLLOW_UP_REMINDER_MAX_AGE_HOURS * 60L * 60L * 1000L) return@filter false
                if (elapsedMs < s.followUpIntervalMs()) return@filter false
                val state = readFollowUpState(context, companion.id)
                val nudgeCount = if (state.lastNudgeMessageId == last.id) state.nudgeCount else 0
                nudgeCount < s.maxNudgeTimes()
            }

            val companion = dueFollowUpCompanions.randomOrNull() ?: eligibleCompanions.random()
            val settings = settingsById[companion.id] ?: ProactiveSettings()
            val domainSettings = settings.toDomain()

            if (isInDndRange(nowMinutes, settings) && !settings.allowLateNightMessage) {
                val minutesToDndEnd = minutesUntilDndEnd(nowMinutes, settings.dndStartMinutes, settings.dndEndMinutes)
                SecureLog.d("CompanionMessageWorker", "DND active for ${companion.name}, retry in ${minutesToDndEnd}min")
                scheduleWithDelay(context, minutesToDndEnd.coerceIn(1, 1440).toLong())
                return@withContext Result.success()
            }

            if (settings.proactiveDailyLimit > 0) {
                val todayCount = getTodayProactiveCount(context, companion.id)
                if (todayCount >= settings.proactiveDailyLimit) {
                    SecureLog.d("CompanionMessageWorker", "Daily limit reached ($todayCount/${settings.proactiveDailyLimit}) for ${companion.name}")
                    scheduleNext(context, settings)
                    return@withContext Result.success()
                }
            }

            val recentMessages = ChatMessageCrypto.decryptFromStorage(
                    messageDao.getRecentMessagesSync(companion.id, "chat", 10)
                        .map { it.toChatMessage() }
                )
                .filterDecrypted()
                // 工具调用卡片（TOOL_ACTIVITY）是本轮最后落库的消息，若不剔除会被当作
                // assistant 文本喂给主动问候/追问模型，污染上下文。此处直接走 DAO，
                // 绕过了 ChatRepository.getRecentMessagesSync 的过滤，需自行剔除。
                .filter { it.type != MessageType.TOOL_ACTIVITY }

            val sortedMessages = recentMessages.sortedBy { it.timestamp }
            val lastMessage = sortedMessages.lastOrNull()

            if (lastMessage != null && !lastMessage.isFromUser && settings.followUpReminderEnabled) {
                val elapsedMs = now - lastMessage.timestamp

                if (elapsedMs >= ChatConstants.FOLLOW_UP_REMINDER_MAX_AGE_HOURS * 60L * 60L * 1000L) {
                    scheduleNext(context, settings)
                    return@withContext Result.success()
                }
                val state = readFollowUpState(context, companion.id)

                val nudgeCount = if (state.lastNudgeMessageId == lastMessage.id) state.nudgeCount else 0

                if (elapsedMs >= settings.followUpIntervalMs() && nudgeCount < settings.maxNudgeTimes()) {
                    val reminder = generateWithAgent(
                        companion = companion,
                        recentMessages = recentMessages,
                        settings = domainSettings,
                        followUp = true,
                    )
                    val nudgeMsgId = reminder?.let { sendMessage(companion, it) }
                    if (nudgeMsgId != null) {

                        saveFollowUpState(context, companion.id, nudgeMsgId, nudgeCount + 1)
                        SecureLog.d(
                            "CompanionMessageWorker",
                            "Follow-up reminder sent for ${companion.name}, nudge=${nudgeCount + 1}/${settings.maxNudgeTimes()}"
                        )
                        scheduleFollowUpNext(context, settings)
                        return@withContext Result.success()
                    }
                    SecureLog.d("CompanionMessageWorker", "Follow-up reminder declined, reschedule")
                }

                if (elapsedMs < settings.followUpIntervalMs()) {
                    scheduleFollowUpNext(context, settings)
                } else {
                    scheduleNext(context, settings)
                }
                return@withContext Result.success()
            }

            if (!aiServiceProvider.shouldProactivelyMessage(companion.toAiCompanionInfo(), recentMessages.toAiChatMessages(), domainSettings)) {
                scheduleNext(context, settings)
                return@withContext Result.success()
            }

            val messageContent = generateWithAgent(
                companion = companion,
                recentMessages = recentMessages,
                settings = domainSettings,
                followUp = false,
            ) ?: run {
                SecureLog.w("CompanionMessageWorker", "Proactive message is null, skipping")
                scheduleNext(context, settings)
                return@withContext Result.success()
            }

            sendMessage(companion, messageContent)

            scheduleNext(context, settings)

            Result.success()
        } catch (e: IllegalStateException) {
            SecureLog.e("CompanionMessageWorker", "Permanent failure, will not retry", e)
            Result.failure()
        } catch (e: SecurityException) {
            SecureLog.e("CompanionMessageWorker", "Permission denied, will not retry", e)
            Result.failure()
        } catch (e: Exception) {
            SecureLog.e("CompanionMessageWorker", "Transient failure, will retry", e)
            Result.retry()
        }
    }

    private fun broadcastProactiveWeChatMessage(companionId: Long, messageId: Long) {
        WeChatProactiveSync.enqueue(companionId, messageId)
        SecureLog.d("CompanionMessageWorker", "Enqueue WeChat proactive message, companionId=$companionId, messageId=$messageId")
    }

    /**
     * 主动消息 / 跟进追问的 LLM 生成 —— 改走 Rust Cordis Agent（[AgentFacade.runTurn]）。
     *
     * 与迁移前的 `AiService.generateFollowUpReminder` / `generateProactiveMessage` 语义对齐：
     * 单轮、无工具、跟随角色人设；模型以 [NO_PROACTIVE_MARKER] 表示「本轮不发言」。
     * 判定（DND / 频控 / 冷却 / 计数）与调度逻辑完全不变，仅生成路径替换。
     */
    private suspend fun generateWithAgent(
        companion: com.yunian.ai.database.model.CompanionEntity,
        recentMessages: List<ChatMessage>,
        settings: ProactiveMessageSettings,
        followUp: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val activeApi = apiConfigRepository.getActiveEnabledConfig()
            if (activeApi == null || activeApi.model.isBlank()) {
                SecureLog.w("CompanionMessageWorker", "No active API config, skipping agent generation")
                return@withContext null
            }
            val partnerSession = runCatching { RemoteKeyProvider.getPartnerSession(appContext) }.getOrNull()
            val isPartner = activeApi.provider == ApiProvider.PARTNER
            AgentFacade.syncRuntimeConfig(
                appContext,
                AgentFacade.buildSettingsJson(role = "GIRLFRIEND"),
                StickerPreferenceFacade.availableTagsWithFallback(appContext),
                AgentFacade.buildCredentialsJson(
                    sessionToken = if (isPartner) partnerSession?.token else null,
                    clientId = if (isPartner) partnerSession?.clientId else null,
                    apiKey = activeApi.apiKey?.takeIf { it.isNotBlank() },
                ),
            )

            val history = recentMessages.sortedBy { it.timestamp }.takeLast(10)
            val lastUserMessage = history.lastOrNull { it.isFromUser }?.content.orEmpty()
            val instruction = buildProactiveInstruction(companion, settings, followUp)
            val turnRequest = AgentTurnRequest(
                groupId = null,
                historyJson = serializeHistoryJson(history.map { it.toAiChatMessage() }, instruction),
                tools = emptyList(),
                maxRounds = 1u,
                toolChoice = "auto",
                stickerProbability = 0u,
                image = null,
                // 主动消息必须由 Rust 编排器注入人设/记忆/世界书，故不传 systemPrompt
                systemPrompt = null,
                companionNameMapJson = null,
            )
            val result = AgentFacade.runTurn(
                turnRequest, appContext, companion.id, AgentToolHost(appContext),
            )
            val raw = result.finalText.trim()
                .ifBlank { result.events.filter { it.kind == "bubble" }.joinToString("\n") { it.text } }
            if (raw.isBlank()) return@withContext null

            // 与旧路径一致的「不发言」语义（AiPromptBuilder.NO_PROACTIVE_MARKER）
            if (raw.contains(NO_PROACTIVE_MARKER)) {
                val without = raw.replace(NO_PROACTIVE_MARKER, "", ignoreCase = true).trim()
                if (without.length < 2) return@withContext null
                return@withContext without
            }
            raw.replace(Regex("\\r\\n|\\r|\\n+"), "，")
                .replace(Regex("，{2,}"), "，")
                .trimStart('，', ',', '.', '。', ' ')
                .trim()
                .takeIf { it.length >= 2 }
        }.onFailure {
            SecureLog.w("CompanionMessageWorker", "Agent proactive generation failed: ${it.message}")
        }.getOrNull()
    }

    /** 主动消息 / 追问的单轮指令（对齐旧 AiService 提示词的语义要点）。 */
    private fun buildProactiveInstruction(
        companion: com.yunian.ai.database.model.CompanionEntity,
        settings: ProactiveMessageSettings,
        followUp: Boolean,
    ): String = if (followUp) {
        """
        你上一条消息发出后，用户一直没回复。
        现在由你决定是否追问：
        - 若判断用户可能在忙、已休息或对话已自然收尾，只输出 $NO_PROACTIVE_MARKER，不要硬催。
        - 若决定追问：只发 1 条，10~30 字，简短自然，语气严格服从你的性格。
        - 不要重复上一条消息的内容，不要堆叠追问，不要说教。禁止括号，禁止AI感词汇。
        """.trimIndent()
    } else {
        """
        以${companion.name}的身份决定是否、以及如何继续刚才的对话。
        - 若用户此刻明显不想被打扰、对话已自然收束，只输出 $NO_PROACTIVE_MARKER，不要硬聊。
        - 话题选择以性格优先：上一话题已完结或不感兴趣时，可轻转、只回情绪，或输出 $NO_PROACTIVE_MARKER。
        若决定发消息：
        1. 像真人聊天一样自然，单次单动作且句式完整；不要长文堆叠共情+方案+追问
        2. 优先 1 条消息，不要拆成很多短句连发
        3. 不要重新开场、不要念日程；语气严格服从角色性格
        4. 禁止括号，禁止AI感词汇，禁止说教
        5. 时间只是背景，不要机械报时或按时段派发固定关心任务
        ${if (settings.allowLateNightMessage) "" else "6. 当前处于免打扰时段，只做话题延续或情绪轻触，禁止提睡/吃/到家/报时"}
        """.trimIndent()
    }

    /** 领域历史 → OpenAI messages JSON，尾部追加一条 user 指令。 */
    private fun serializeHistoryJson(
        history: List<AiChatMessage>,
        instruction: String,
    ): String {
        val arr = org.json.JSONArray()
        for (msg in history) {
            arr.put(
                org.json.JSONObject().apply {
                    put("role", if (msg.isFromUser) "user" else "assistant")
                    put("content", msg.content)
                }
            )
        }
        arr.put(
            org.json.JSONObject().apply {
                put("role", "user")
                put("content", instruction)
            }
        )
        return arr.toString()
    }

    /**
     * 把一次主动生成内容送达为一条或多条气泡。
     *
     * 拆分语义遵循 [BubbleTextSplitter]：AI 敲的每一个换行都是「想发下一条」的信号，
     * 一行一条、空行不产生空气泡；无换行时恰好 1 条，行为与旧版单条发送一致。
     * 每条气泡落库前与「本批已发出气泡的归一化内容」查重（[DedupGuard]），过滤空/重复气泡。
     *
     * @return 最后一条成功落库气泡的消息 id（供 follow-up 状态追踪）；全部失败返回 null。
     */
    private suspend fun sendMessage(companion: com.yunian.ai.database.model.CompanionEntity, content: String): Long? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val safety = com.yunian.ai.common.ContentFilter.checkOutputSafety(trimmed)
        if (!safety.isSafe) {
            SecureLog.w("CompanionMessageWorker", "Proactive message blocked by safety filter: ${safety.reason}")

            return null
        }
        // 主动消息同样可能夹带生图标签/画面描述：落库前统一清洗
        val clean = ImageGenProtocol.sanitizeForDisplay(trimmed).ifBlank { trimmed }

        // 换行即下一条气泡；无换行时整条为 1 个气泡
        val bubbles = BubbleTextSplitter.splitByParagraphs(clean)
        val writeCoordinator = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
        // 查重窗口 = 本批已发出气泡的归一化内容
        val sentNorms = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        var lastMessageId: Long? = null
        for (bubble in bubbles) {
            val text = bubble.trim()
            if (text.isEmpty()) continue
            val norm = DedupGuard.normalize(text)
            if (norm.isNotEmpty() && DedupGuard.isDuplicate(norm, sentNorms)) {
                SecureLog.d("CompanionMessageWorker", "Proactive bubble dropped as in-batch duplicate")
                continue
            }
            val messageId = writeCoordinator.enqueueChat(
                ChatMessage(
                    companionId = companion.id,
                    content = text,
                    isFromUser = false
                )
            )
            broadcastProactiveWeChatMessage(companion.id, messageId)
            lastMessageId = messageId
            delivered.add(text)
            if (norm.isNotEmpty()) sentNorms.add(norm)
        }

        if (lastMessageId == null) return null

        // 按「生成一次」计数：无论一次拆出几条气泡都只算 1 次主动消息
        incrementTodayProactiveCount(context, companion.id, 1)
        if (!AppForegroundTracker.isInForeground) {
            // 多气泡合并成单条预览，沿用 50 字截断规则（与 AiReplyWorker 一致）
            val merged = delivered.joinToString(" ")
            val notificationPreview = if (merged.length > 50) merged.take(50) + "..." else merged
            NotificationHelper.showCompanionMessageNotification(
                context,
                companion.name,
                notificationPreview,
                companion.id
            )
        }
        return lastMessageId
    }

    private fun isInDndRange(nowMinutes: Int, settings: ProactiveSettings): Boolean {
        if (!settings.doNotDisturbEnabled || settings.allowPriorityMessageInDnd) return false
        return if (settings.dndStartMinutes > settings.dndEndMinutes) {

            nowMinutes >= settings.dndStartMinutes || nowMinutes < settings.dndEndMinutes
        } else {
            nowMinutes in settings.dndStartMinutes until settings.dndEndMinutes
        }
    }

    private fun minutesUntilDndEnd(nowMinutes: Int, dndStartMinutes: Int, dndEndMinutes: Int): Int {
        val dayMinutes = 24 * 60
        return if (dndStartMinutes > dndEndMinutes) {

            if (nowMinutes >= dndStartMinutes) (dayMinutes - nowMinutes) + dndEndMinutes
            else dndEndMinutes - nowMinutes
        } else {
            dndEndMinutes - nowMinutes
        }
    }

    private suspend fun readAllCompanionSettings(): Map<Long, ProactiveSettings> {
        return runCatching {
            val dataStore = ChatDetailSettingsDataStoreProvider.get(context)
            val prefs = dataStore.data.first()
            val raw = prefs[stringPreferencesKey("companion_chat_detail_settings_map")] ?: return@runCatching emptyMap()
            json.decodeFromString<Map<Long, ProactiveSettings>>(raw)
        }.getOrNull() ?: emptyMap()
    }

    private fun ProactiveSettings.toDomain() = ProactiveMessageSettings(
        proactiveEnabled = proactiveEnabled,
        proactiveIntervalMinutes = proactiveIntervalMinutes,
        proactiveMinIntervalMinutes = proactiveMinIntervalMinutes,
        proactiveMaxIntervalMinutes = proactiveMaxIntervalMinutes,
        proactiveDailyLimit = proactiveDailyLimit,
        allowNewTopic = allowNewTopic,
        allowFollowUpMessage = allowFollowUpMessage,
        doNotDisturbEnabled = doNotDisturbEnabled,
        dndStartMinutes = dndStartMinutes,
        dndEndMinutes = dndEndMinutes,
        allowLateNightMessage = allowLateNightMessage,
        allowPriorityMessageInDnd = allowPriorityMessageInDnd,
        blocked = blocked,
        followUpReminderEnabled = followUpReminderEnabled,
        followUpReminderIntervalMinutes = followUpReminderIntervalMinutes,
        followUpReminderMaxTimes = followUpReminderMaxTimes
    )

    private fun com.yunian.ai.database.model.CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id, name = name, personality = personality,
        age = age, backstory = backstory, speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun com.yunian.ai.database.model.ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser, content = content, timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId
    )

    private fun List<com.yunian.ai.database.model.ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }

    companion object {
        private const val WORK_NAME = "companion_message_work"

        /** 主动消息/追问的「本轮不发言」语义标记（与 AiPromptBuilder.NO_PROACTIVE_MARKER 同值）。 */
        private const val NO_PROACTIVE_MARKER = "[NO_PROACTIVE]"

        private const val DAILY_COUNT_PREFS = "proactive_daily_count"

        private const val FOLLOW_UP_STATE_PREFS = "proactive_followup_state"

        private fun getTodayProactiveCount(context: Context, companionId: Long): Int {
            val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
            val today = todayKey()
            val storedDate = prefs.getString("date_$companionId", null)
            return if (storedDate == today) prefs.getInt("count_$companionId", 0) else 0
        }

        private fun incrementTodayProactiveCount(context: Context, companionId: Long, delta: Int) {
            val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
            val today = todayKey()
            val count = getTodayProactiveCount(context, companionId) + delta
            prefs.edit()
                .putString("date_$companionId", today)
                .putInt("count_$companionId", count)
                .apply()
        }

        private fun todayKey(): String {
            val cal = java.util.Calendar.getInstance()
            return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
        }

        private data class FollowUpState(
            val lastNudgeMessageId: Long = -1L,
            val nudgeCount: Int = 0
        )

        private fun readFollowUpState(context: Context, companionId: Long): FollowUpState {
            val prefs = context.getSharedPreferences(FOLLOW_UP_STATE_PREFS, Context.MODE_PRIVATE)
            return FollowUpState(
                lastNudgeMessageId = prefs.getLong("last_nudge_msg_$companionId", -1L),
                nudgeCount = prefs.getInt("nudge_count_$companionId", 0)
            )
        }

        private fun saveFollowUpState(context: Context, companionId: Long, lastNudgeMessageId: Long, nudgeCount: Int) {
            context.getSharedPreferences(FOLLOW_UP_STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_nudge_msg_$companionId", lastNudgeMessageId)
                .putInt("nudge_count_$companionId", nudgeCount)
                .apply()
        }

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {

            val hasActiveWork = runCatching {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                    .get(500, TimeUnit.MILLISECONDS)
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(true)
            if (hasActiveWork) return

            val delayMinutes = Random.nextInt(
                ChatConstants.PROACTIVE_FALLBACK_MIN_MINUTES.toInt(),
                ChatConstants.PROACTIVE_FALLBACK_MAX_MINUTES.toInt()
            )
            scheduleWithDelay(context, delayMinutes.toLong())
        }

        private fun scheduleNext(context: Context, settings: ProactiveSettings?) {
            val delayMinutes = if (settings != null && settings.proactiveIntervalMinutes > 0) {

                settings.proactiveIntervalMinutes.coerceIn(
                    ChatConstants.PROACTIVE_USER_MIN_INTERVAL_MINUTES,
                    ChatConstants.PROACTIVE_USER_MAX_INTERVAL_MINUTES
                ).toLong()
            } else {
                val minInterval = settings?.proactiveMinIntervalMinutes
                    ?.coerceAtLeast(ChatConstants.PROACTIVE_USER_MIN_INTERVAL_MINUTES)
                    ?: ChatConstants.PROACTIVE_FALLBACK_MIN_MINUTES.toInt()
                val maxInterval = settings?.proactiveMaxIntervalMinutes
                    ?.coerceAtLeast(minInterval + 1)
                    ?: ChatConstants.PROACTIVE_FALLBACK_MAX_MINUTES.toInt()
                Random.nextInt(minInterval, maxInterval + 1).toLong()
            }
            scheduleWithDelay(context, delayMinutes)
        }

        private fun scheduleFollowUpNext(context: Context, settings: ProactiveSettings) {
            scheduleWithDelay(context, settings.followUpIntervalMs() / 60_000L)
        }

        private fun scheduleWithDelay(context: Context, delayMinutes: Long) {
            val workRequest = OneTimeWorkRequestBuilder<CompanionMessageWorker>()
                .setConstraints(networkConstraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

private fun ProactiveSettings.followUpIntervalMs(): Long =
    followUpReminderIntervalMinutes.coerceIn(
        ChatConstants.FOLLOW_UP_REMINDER_MIN_INTERVAL_MINUTES,
        ChatConstants.FOLLOW_UP_REMINDER_MAX_INTERVAL_MINUTES
    ) * 60_000L

private fun ProactiveSettings.maxNudgeTimes(): Int =
    followUpReminderMaxTimes.coerceIn(1, ChatConstants.FOLLOW_UP_REMINDER_MAX_TIMES_LIMIT)
