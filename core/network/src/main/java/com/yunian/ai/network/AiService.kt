package com.yunian.ai.network

import android.content.Context
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.common.EnvAnchorCooldown
import com.yunian.ai.common.EnvAnchorStore
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerManager
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.common.YandereModeManager
import com.yunian.ai.common.RemoteKeyProvider
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.AiResponse
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ConversationScope
import com.yunian.ai.domain.ProactiveMessageSettings
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.PlaceholderProvider
import com.yunian.ai.domain.PlaceholderContext
import com.yunian.ai.network.Message
import com.yunian.ai.network.bubble.BubbleJsonProtocol
import com.yunian.ai.network.transformers.MessageTransformer
import com.yunian.ai.network.transformers.PlaceholderTransformer

import com.yunian.ai.network.transformers.TimeReminderTransformer
import com.yunian.ai.network.transformers.TransformerContext
import com.yunian.ai.network.transformers.runPipeline
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.TokenUsageRepository
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.network.stream.ChatBodyAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class AiService(context: Context) : AiServiceProvider {
    private val appContext = context.applicationContext
    private val apiConfigRepository: ApiConfigRepository
    private val companionRepository: CompanionRepository
    private val memoryProvider: com.yunian.ai.domain.MemoryProvider
    private val tokenUsageRepository: TokenUsageRepository
    private val userRepository: UserRepository
    private val appSettingsStore = AppSettingsStore(appContext)
    private val envAnchorStore = EnvAnchorStore(appContext)
    private val summaryProvider: com.yunian.ai.database.repository.SummaryProvider? =
        com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.database.repository.SummaryProvider::class.java)

    /**
     * 滚动摘要管理器：状态持久化走 AppMetaStore KV（schema 冻结 v41 红线下的合法持久化方式）。
     * - mergeSummarizer：委托给 SummaryProvider.summarizeRollingMerge（全局配置，本期不改）；
     * - backgroundScope：进程级作用域（SupervisorJob 隔离失败，IO 调度），
     *   fire-and-forget 增量合并绝不阻塞主聊天链路；
     * - gapFetcher：经 ServiceRegistry 惰性取 ChatRepository（避免构造顺序依赖），
     *   按消息 id 拉取解密后的聊天记录补齐增量空洞。
     */
    private val rollingSummaryManager = RollingSummaryManager(
        metaStore = AppMetaStore(AppDatabase.getDatabase(appContext).appMetaDao()),
        mergeSummarizer = { oldSummary, deltaText, _, memoryContext, selfName ->
            summaryProvider?.summarizeRollingMerge(oldSummary, deltaText, memoryContext, selfName)
        },
        backgroundScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + AppDispatchers.io
        ),
        gapFetcher = { conversationId, afterId ->
            ServiceRegistry.getOrThrow(ChatRepository::class.java)
                .getMessageRangeSync(conversationId, afterId, ChatConstants.MAX_AI_CONTEXT_FETCH)
        }
    )

    private val autoContextManager = AutoContextManager(
        aiSummarizer = { messages, companionNameMap, memoryContext ->
            summarizeWithAi(messages, companionNameMap, memoryContext)
        },
        rollingSummaryManager = rollingSummaryManager
    )

    // 世界书注入已迁至 Rust Cordis Agent（`AgentFacade.runTurn` 回合前
    // `WorldbookRepository.syncActiveToRuntime` 同步 → Rust 侧内联到 system），
    // 故 `PromptInjectionTransformer` 已退役，不再参与管线。
    private val inputTransformers: List<MessageTransformer> = listOf(
        PlaceholderTransformer(),
        TimeReminderTransformer(),
    )

    @Volatile
    private var cachedBuiltinModel: String? = null

    private val retryController = AiRetryController()
    private val rateLimiter = AiRateLimiter()
    private val placeholderProvider: PlaceholderProvider? = ServiceRegistry.get(PlaceholderProvider::class.java)

    init {
        val database = AppDatabase.getDatabase(appContext)
        val deviceId = DeviceIdProvider.getDeviceId(appContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao(), database.apiProviderPresetDao())
        companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
        memoryProvider = com.yunian.ai.domain.ServiceRegistry.getOrThrow(com.yunian.ai.domain.MemoryProvider::class.java)
        memoryProvider.initialize()
        tokenUsageRepository = TokenUsageRepository(appContext)
        userRepository = ServiceRegistry.getOrThrow(UserRepository::class.java)
    }

    private suspend fun appendYanderePromptIfNeeded(systemPrompt: String, companion: CompanionModel): String {
        return try {
            val manager = ServiceRegistry.get(YandereModeManager::class.java)
                ?: return systemPrompt
            if (!appSettingsStore.getYandereModeEnabled()) return systemPrompt
            if (!manager.shouldTriggerThisRound()) return systemPrompt
            val role = ServiceRegistry.get(UserRepository::class.java)?.selectedRole?.value
                ?: CompanionRole.GIRLFRIEND
            val yanderePrompt = manager.buildYandereModeSystemPrompt(role)
            if (yanderePrompt.isBlank()) return systemPrompt
            SecureLog.d("AiService", "Yandere mode triggered for companion=${companion.name}, role=$role")
            "$systemPrompt\n\n$yanderePrompt"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.w("AiService", "appendYanderePromptIfNeeded failed: ${e.message}")
            systemPrompt
        }
    }

    private fun resolvePlaceholders(text: String, companion: CompanionModel, config: ApiConfig?): String {
        val provider = placeholderProvider ?: return text
        val userName = userRepository.userName.value.ifEmpty { "用户" }
        val context = PlaceholderContext(
            charName = companion.name,
            userName = userName,
            modelId = config?.model.orEmpty(),
            modelName = config?.model.orEmpty(),
            locale = java.util.Locale.getDefault().toString(),
            timezone = java.util.TimeZone.getDefault().id,
        )
        return provider.resolve(text, context)
    }

    private suspend fun resolveConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
    }

    /**
     * 角色级 API 隔离：角色绑定了专属配置（companions.apiConfigId）时强制使用该配置，
     * 其余情况回退全局启用配置。绑定配置被删除或无 Key 时同样回退全局，保证可用性。
     *
     * 背景：多角色共用同一 Key 时，部分服务商的账号级上下文缓存会造成人设串台；
     * 每角色独立 Key 从源头隔离。
     */
    private suspend fun resolveConfig(companionId: Long?): ApiConfig? {
        if (companionId != null && companionId > 0L) {
            try {
                val boundId = companionRepository.getCompanionById(companionId)?.apiConfigId
                if (boundId != null && boundId > 0L) {
                    val bound = apiConfigRepository.getConfigById(boundId)
                    val usable = bound != null &&
                        (bound.apiKey.isNotBlank() || bound.provider == ApiProvider.PARTNER)
                    if (usable) {
                        return bound
                    }
                    SecureLog.w(
                        "AiService",
                        "Companion=$companionId 绑定配置($boundId)不可用(${if (bound == null) "已删除" else "无Key"})，回退全局配置"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w("AiService", "resolveConfig(companion=$companionId) failed: ${e.message}")
            }
        }
        return resolveConfig()
    }

    private suspend fun tryFetchBuiltinModel(keys: List<String>): String? {
        return try {
            val partnerPreset = apiConfigRepository.getProviderPreset(ApiProvider.PARTNER)
            val partnerBaseUrl = partnerPreset?.baseUrl ?: ApiProvider.PARTNER.defaultBaseUrl
            val result = fetchModels(partnerBaseUrl, keys.first(), ApiProvider.PARTNER)
            result.getOrNull()?.let { models ->
                if (models.isNotEmpty()) {
                    val chatModels = models.filter { m ->
                        chatKeywords.any { m.contains(it, ignoreCase = true) }
                    }
                    val candidatePool = if (chatModels.size > 1) chatModels
                    else models.filter { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                    val selected = if (candidatePool.size > 1) {
                        familyBalancedRandom(candidatePool)
                    } else {
                        candidatePool.firstOrNull() ?: models.first()
                    }
                    SecureLog.api("BUILTIN", "Auto-selected model: $selected from ${models.size} models (chatModels=${chatModels.size})")
                    selected
                } else null
            }
        } catch (e: Exception) {
            SecureLog.w("AiService", "Auto-fetch builtin models failed: ${e.message}")
            null
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val fetchModelsExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "AiService-fetchModels").apply { isDaemon = true }
        }

        fun requiresFixedTemperature(model: String): Boolean {
            return model.contains("kimi-k2.6", ignoreCase = true) ||
                   model.contains("k2.6", ignoreCase = true)
        }

        private val modelFamilyKeywords = listOf(
            "deepseek", "qwen", "glm", "kimi", "moonshot",
            "gpt", "claude", "gemini", "yi-", "ernie", "hunyuan", "doubao"
        )
        private val chatKeywords = modelFamilyKeywords + listOf("chat", "completion", "instruct")

        private fun urandomInt(bound: Int): Int {
            val buf = ByteArray(4)
            java.io.FileInputStream("/dev/urandom").use { it.read(buf) }
            val raw = ((buf[0].toInt() and 0xFF) shl 24) or
                       ((buf[1].toInt() and 0xFF) shl 16) or
                       ((buf[2].toInt() and 0xFF) shl 8) or
                       (buf[3].toInt() and 0xFF)
            return (raw and Int.MAX_VALUE) % bound
        }

        fun familyBalancedRandom(candidatePool: List<String>): String {
            if (candidatePool.size <= 1) return candidatePool.first()

            val groups = LinkedHashMap<String, MutableList<String>>()
            for (m in candidatePool) {
                val family = modelFamilyKeywords.firstOrNull { m.contains(it, ignoreCase = true) } ?: "other"
                groups.getOrPut(family) { mutableListOf() }.add(m)
            }

            val families = groups.keys.toList()
            val chosenFamily = families[urandomInt(families.size)]
            val pool = groups[chosenFamily]!!
            return pool[urandomInt(pool.size)]
        }

        private val okHttpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()

            if (false) {
                builder.addInterceptor(
                    HttpLoggingInterceptor(RedactingLogger()).apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    }
                )
            }

            builder.addInterceptor(NetworkLogger())

            builder.addInterceptor(RetryInterceptor(maxRetries = 2, initialDelayMs = 300))

            builder.addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))

            RequestSecurityInterceptor.enforceTls(builder)

            builder
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))

                .callTimeout(TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectTimeout(TimeoutBudgets.HTTP_CONNECT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TimeoutBudgets.HTTP_READ_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TimeoutBudgets.HTTP_WRITE_MS, TimeUnit.MILLISECONDS)
                .pingInterval(TimeoutBudgets.HTTP_PING_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun executeAdaptive(
            config: ApiConfig,
            request: okhttp3.Request,
            client: OkHttpClient = getEffectiveClient(config)
        ): okhttp3.Response {
            return client.newCall(request).execute()
        }

        private fun getEffectiveClient(config: ApiConfig): OkHttpClient {
            if (config.provider == ApiProvider.PARTNER) {
                return partnerHttpClient
            }
            return okHttpClient
        }

        private val partnerHttpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
                .certificatePinner(CertificatePins.certificatePinner)
                .connectionPool(okhttp3.ConnectionPool(3, 5, TimeUnit.MINUTES))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val lightHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
                .connectTimeout(TimeoutBudgets.HTTP_CONNECT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(TimeoutBudgets.HTTP_WRITE_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        // 长文生成专用：人设 800~1500 字 LLM 需 1~2 分钟，20s 读超时必然失败
        // （聊天走流式客户端不受影响；此 client 共享连接池与拦截器，仅放宽读超时）
        private val generationHttpClient: OkHttpClient by lazy {
            lightHttpClient.newBuilder()
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        private val visionHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TimeoutBudgets.HTTP_CONNECT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TimeoutBudgets.HTTP_READ_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val balanceHttpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private val streamingHttpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            builder.addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .connectionPool(okhttp3.ConnectionPool(3, 5, TimeUnit.MINUTES))
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(TimeoutBudgets.HTTP_CONNECT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(NetworkConstants.STREAMING_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
                .writeTimeout(TimeoutBudgets.HTTP_WRITE_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val partnerStreamingHttpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
                .certificatePinner(CertificatePins.certificatePinner)
                .connectionPool(okhttp3.ConnectionPool(2, 5, TimeUnit.MINUTES))
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(NetworkConstants.PARTNER_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
                .readTimeout(NetworkConstants.STREAMING_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun getStreamingClient(config: ApiConfig): OkHttpClient {
            return if (config.provider == ApiProvider.PARTNER) partnerStreamingHttpClient else streamingHttpClient
        }

        private fun ensureNotHtml(body: String, response: okhttp3.Response) {
            val trimmed = body.trimStart()
            if (trimmed.startsWith("<!") || trimmed.startsWith("<html", ignoreCase = true)) {
                val hint = when {
                    response.code == 401 || response.code == 403 ->
                        " (请检查API密钥/APIPassword是否正确)"
                    response.code == 404 ->
                        " (请检查API地址和模型名是否正确)"
                    else -> " (HTTP ${response.code}，请检查API配置)"
                }
                throw Exception("服务器返回了网页而非API响应$hint")
            }
        }

        private fun shouldSignRequest(request: okhttp3.Request): Boolean {
            val host = request.url.host.lowercase()
            return request.header("X-LianYu-Session")?.isNotBlank() == true ||
                host == "api.lianyu.ai" || host.endsWith(".lianyu.ai")
        }

        private var context: Context? = null

        fun initialize(ctx: Context) {
            context = ctx.applicationContext
        }

        private val retrofit: Retrofit by lazy {
            Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(NetworkConstants.OPENAI_DEFAULT_BASE_URL)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        }

        private val openAiApi: OpenAiApi by lazy { retrofit.create(OpenAiApi::class.java) }
        private val anthropicApi: AnthropicApi by lazy { retrofit.create(AnthropicApi::class.java) }
        private val geminiApi: GeminiApi by lazy { retrofit.create(GeminiApi::class.java) }

        private val keyRoundRobinIndex = AtomicInteger(Random.nextInt(Int.MAX_VALUE))
        private val keyLastUsed = ConcurrentHashMap<String, Long>()
        private val keyCooldownUntil = ConcurrentHashMap<String, Long>()

        fun usesAnthropicProtocol(config: ApiConfig): Boolean {
            return config.provider == ApiProvider.ANTHROPIC ||
                (config.provider == ApiProvider.CUSTOM && config.formatHint == "anthropic")
        }

        fun supportsOpenAiModelList(config: ApiConfig): Boolean {
            return !usesAnthropicProtocol(config)
        }

        const val KEY_MIN_INTERVAL_MS = 800L
        const val KEY_FAILURE_COOLDOWN_MS = 5000L

        fun selectApiKey(config: ApiConfig): Pair<Int, List<String>> {
            val allKeys = config.getAllApiKeys()
            if (allKeys.size <= 1) return 0 to allKeys
            val now = System.currentTimeMillis()
            var attempts = 0
            while (attempts < allKeys.size * 2) {
                val idx = keyRoundRobinIndex.getAndIncrement() % allKeys.size
                val key = allKeys[idx]
                val cooldownUntil = keyCooldownUntil[key] ?: 0L
                if (now >= cooldownUntil && (keyLastUsed[key] ?: 0L) + KEY_MIN_INTERVAL_MS <= now) {
                    keyLastUsed[key] = now
                    SecureLog.d("AiService", "Key轮询: 使用 #${idx + 1}/${allKeys.size}")
                    return idx to allKeys
                }
                attempts++
            }
            val fallbackIdx = keyRoundRobinIndex.getAndIncrement() % allKeys.size
            keyLastUsed[allKeys[fallbackIdx]] = now
            return fallbackIdx to allKeys
        }

        fun markKeyFailed(key: String) {
            keyCooldownUntil[key] = System.currentTimeMillis() + KEY_FAILURE_COOLDOWN_MS
            SecureLog.w("AiService", "Key失败冷却5s: ${key.take(8)}...")
        }

        fun resetKeyState() {
            keyCooldownUntil.clear()
            keyLastUsed.clear()
        }
    }

    suspend fun sendMessage(companion: CompanionModel?, history: List<ChatMessage>, stickerProbability: Int = 30, ntpTimeEnabled: Boolean = false, extraSystemRules: String = ""): AiResponse {

        if (companion == null) return AiResponse("[TOAST]系统正在加载伴侣信息，请稍后再试")

        return SecureLog.timed("AiService", "sendMessage") {
            withContext(Dispatchers.IO) {
                val config = resolveConfig(companion.id)
                    ?: return@withContext AiResponse("[TOAST]请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。")

                if (config.model.isBlank()) {
                    return@withContext AiResponse("[TOAST]模型名未配置，请在「API设置」中重新测试连接以自动选择模型。")
                }

                val sortedHistory = history.sortedBy { it.timestamp }

                val sanitizedHistory = if (config.provider == ApiProvider.PARTNER) {
                    sortedHistory
                } else {
                    sortedHistory.map { msg ->
                        if (msg.isFromUser) msg.copy(content = com.yunian.ai.common.safety.DifferentialPrivacyFilter.sanitize(msg.content))
                        else msg
                    }
                }
                val lastUserMessage = sanitizedHistory.lastOrNull { it.isFromUser }?.content ?: ""
                val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
                val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, limit = 50)
                val stickerManager = StickerManager.getInstance(appContext)
                // 收口到 StickerManager 唯一装配入口：不按长度静默丢弃（修「规则丢失→内部文件名→被丢」），
                // 自定义表情优先占位、内置超预算才截断（见 StickerPromptNames）。
                val availableStickers = stickerManager.getStickerNamesForPrompt()
                val role = userRepository.selectedRole.value
                val phase = ConversationPhaseDetector.detect(sanitizedHistory)
                val allowEnvAnchor = resolveAllowEnvAnchor(companion.id, sanitizedHistory)
                // 自定义表情语义清单（E2 段）：未导入时为空 → 提示词逐字节零变化
                val customPromptStickers = stickerManager.getPromptStickers().filter { it.isCustom }
                val baseSystemPrompt = AiPromptBuilder.buildStableSystemPrompt(
                    companion,
                    availableStickers,
                    stickerProbability,
                    innerThoughtEnabled,
                    role,
                    customPromptStickers,
                )
                val turnContext = AiPromptBuilder.buildTurnContext(
                    lastUserMessage = lastUserMessage,
                    ntpTimeEnabled = ntpTimeEnabled,
                    phase = phase,
                    allowEnvAnchor = allowEnvAnchor,
                    history = sanitizedHistory,
                )
                val systemPrompt = appendYanderePromptIfNeeded(baseSystemPrompt, companion).let {
                    if (extraSystemRules.isNotBlank()) "$it\n\n$extraSystemRules" else it
                }.let { resolvePlaceholders(it, companion, config) }
                val contextConfig = AutoContextManager.ContextConfig(model = config.model, provider = config.provider, maxOutputTokens = config.maxTokens ?: 4096)
                var messages = autoContextManager.build(sanitizedHistory, systemPrompt, memoryContext, lastUserMessage, emptyMap(), contextConfig, turnContext = turnContext, scope = ConversationScope.Single(companion.id), selfName = companion.name)

                val placeholderProvider = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.PlaceholderProvider::class.java)
                if (placeholderProvider != null) {
                    val transformerContext = TransformerContext(
                        sessionId = companion.id,
                        isGroupChat = false,
                        modelId = config.model,
                        modelName = config.model,
                        characterName = companion.name,
                        userNickname = userRepository.userName.value.ifEmpty { "用户" },
                        placeholderProvider = placeholderProvider,
                        currentTimeMillis = System.currentTimeMillis()
                    )
                    messages = inputTransformers.runPipeline(transformerContext, messages, isInput = true)
                }

                SecureLog.api("SEND", "provider=${config.provider}, model=${config.model}, messages=${messages.size}, stickerProb=$stickerProbability, stickers=${availableStickers.size}, phase=$phase, allowEnv=$allowEnvAnchor")

                try {
                    val result = if (usesAnthropicProtocol(config)) {
                        callAnthropic(config, messages, systemPrompt)
                    } else {
                        callOpenAiCompatibleWithReasoning(config, messages)
                    }
                    val rawResponse = result.content
                    if (rawResponse.isBlank()) {
                        throw Exception("API返回空内容，请检查模型名是否正确")
                    }

                    recordTokenUsage(companion.id, resolveInputTokens(result.usage, messages), resolveOutputTokens(result.usage, rawResponse))

                    var cleaned = AiPromptBuilder.applyPersonaPostProcessing(rawResponse, sortedHistory)
                    SecureLog.api("SEND", "Response length=${cleaned.length}")

                    val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                    if (!safetyResult.isSafe) {
                        SecureLog.w("AiService", "Output safety violation: ${safetyResult.level} - ${safetyResult.reason}")

                        return@withContext AiResponse("抱歉，我无法继续这个话题。")
                    }

                    maybeMarkEnvAnchor(companion.id, cleaned)
                    AiResponse(cleaned, result.reasoning)
                } catch (e: Exception) {
                    SecureLog.e("AiService", "sendMessage failed", e)
                    throw Exception(formatApiException(e))
                }
            }
        }
    }

    suspend fun generateProactiveMessage(companion: CompanionModel, recentMessages: List<ChatMessage>, settings: ProactiveMessageSettings? = null): String? {
        return withContext(Dispatchers.IO) {
            val config = resolveConfig(companion.id)
            if (config == null) {
                SecureLog.w("AiService", "No active API config, skipping proactive message")
                return@withContext null
            }
            if (config.model.isBlank()) {
                SecureLog.w("AiService", "Model not configured, skipping proactive message")
                return@withContext null
            }

            val sortedMessages = recentMessages.sortedBy { it.timestamp }
            val lastUserMessage = sortedMessages.lastOrNull { it.isFromUser }?.content ?: ""
            val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, limit = 50)
            val allowEnvAnchor = resolveAllowEnvAnchor(companion.id, sortedMessages)

            val systemPrompt = buildProactiveSystemPrompt(companion, memoryContext, settings, allowEnvAnchor)
            val contextMessages = AiPromptBuilder.buildProactiveContext(sortedMessages, companion)
            val envUserHint = if (allowEnvAnchor) {
                "7. 本条主动最多轻提一次环境（时间/睡/吃/到家），也可完全不提；禁止展开成任务清单"
            } else {
                "7. 环境关心冷却中：禁止再提睡/吃/到家/报时/天气；只做话题延续或情绪轻触"
            }

            val messages = listOf(
                Message("system", systemPrompt),
                Message("user", contextMessages),
                // 决策指令抽至 AiPromptBuilder（含「换行=下一条」few-shot 示例），便于单测锁定口径
                Message("user", AiPromptBuilder.buildProactiveDecisionInstruction(companion.name, envUserHint))
            )

            try {
                val rawResponse = if (usesAnthropicProtocol(config)) {
                    callAnthropic(config, messages, systemPrompt).content
                } else {
                    callOpenAiCompatible(config, messages)
                }

                val semantic = AiPromptBuilder.parseProactiveGenerationResult(rawResponse)
                    ?: return@withContext null
                // 统一后处理（内部 preserveRaw = true）：保留 AI 自己敲的换行
                // （换行即「想连发下一条」的信号），并避免长文本被后处理截断；
                // 按行检查 NO_PROACTIVE_MARKER；多行文本交由发送端 BubbleTextSplitter 拆成多条气泡连发。
                val finalText = AiPromptBuilder.postProcessProactiveReply(semantic, sortedMessages)
                    ?: return@withContext null

                val safetyResult = ContentFilter.checkOutputSafety(finalText)
                if (!safetyResult.isSafe) {
                    SecureLog.w("AiService", "Proactive output safety violation: ${safetyResult.level} - ${safetyResult.reason}")

                    return@withContext null
                }

                maybeMarkEnvAnchor(companion.id, finalText)
                SecureLog.d(
                    "AiService",
                    "Proactive generated companion=${companion.id} allowEnv=$allowEnvAnchor envCare=${EnvAnchorCooldown.looksLikeEnvCare(finalText)}",
                )
                finalText
            } catch (e: Exception) {
                SecureLog.w("AiService", "Proactive message failed: ${e.message}")
                null
            }
        }
    }

    suspend fun generateFollowUpReminder(companion: CompanionModel, recentMessages: List<ChatMessage>, settings: ProactiveMessageSettings? = null): String? {
        return withContext(Dispatchers.IO) {
            val config = resolveConfig(companion.id)
            if (config == null) {
                SecureLog.w("AiService", "No active API config, skipping follow-up reminder")
                return@withContext null
            }
            if (config.model.isBlank()) {
                SecureLog.w("AiService", "Model not configured, skipping follow-up reminder")
                return@withContext null
            }

            val sortedMessages = recentMessages.sortedBy { it.timestamp }
            val lastUserMessage = sortedMessages.lastOrNull { it.isFromUser }?.content ?: ""

            val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, limit = 20)
            val allowEnvAnchor = resolveAllowEnvAnchor(companion.id, sortedMessages)

            val systemPrompt = AiPromptBuilder.buildFollowUpReminderSystemPrompt(companion, memoryContext, settings, allowEnvAnchor)
            val contextMessages = AiPromptBuilder.buildProactiveContext(sortedMessages, companion)

            val messages = listOf(
                Message("system", systemPrompt),
                Message("user", contextMessages),
                // 决策指令抽至 AiPromptBuilder：已去掉「只发 1 条 / 10~30 字」硬限制，允许多气泡连发
                Message("user", AiPromptBuilder.buildFollowUpReminderInstruction())
            )

            try {
                val rawResponse = if (usesAnthropicProtocol(config)) {
                    callAnthropic(config, messages, systemPrompt).content
                } else {
                    callOpenAiCompatible(config, messages)
                }
                val semantic = AiPromptBuilder.parseProactiveGenerationResult(rawResponse)
                    ?: return@withContext null
                // 与问候路径同一后处理（preserveRaw）：保留换行（换行=连发下一条），不再压缩成单行，
                // 多行文本交由发送端 BubbleTextSplitter 拆成多条气泡。
                val finalText = AiPromptBuilder.postProcessProactiveReply(semantic, sortedMessages)
                    ?: return@withContext null

                val safetyResult = ContentFilter.checkOutputSafety(finalText)
                if (!safetyResult.isSafe) {
                    SecureLog.w("AiService", "Follow-up reminder safety violation: ${safetyResult.level} - ${safetyResult.reason}")
                    return@withContext null
                }

                maybeMarkEnvAnchor(companion.id, finalText)
                finalText
            } catch (e: Exception) {
                SecureLog.w("AiService", "Follow-up reminder failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun callOpenAiCompatibleForJudge(judgePrompt: String): String {
        val config = resolveConfig()
            ?: return """{"shouldMention":false,"target":"NONE","confidence":0.0}"""

        val messages = listOf(
            Message("system", "你是@提及判断器。只返回JSON格式结果。"),
            Message("user", judgePrompt)
        )

        return try {
            callOpenAiCompatibleLight(config, messages, temperature = 0.1, maxTokens = 100)
        } catch (e: Exception) {
            SecureLog.w("AiService", "Judge call failed after all keys: ${e.message}")
            """{"shouldMention":false,"target":"NONE","confidence":0.0}"""
        }
    }

    private suspend fun callOpenAiCompatibleForGeneration(generationPrompt: String): String {
        val config = resolveConfig()
            ?: return "[TOAST]请先配置并启用可用的API。"

        val messages = listOf(
            Message("system", "你是专业的人设/角色设定生成器。"),
            Message("user", generationPrompt)
        )

        return try {
            // 4096：prompt 要求 800~1500 字人设，中文约 1.5 token/字，2000 会截断
            callOpenAiCompatibleLight(config, messages, temperature = 0.7, maxTokens = 4096, client = generationHttpClient)
        } catch (e: java.net.SocketTimeoutException) {
            SecureLog.w("AiService", "Generation call timed out: ${e.message}")
            throw Exception("AI 生成超时：内容较长，请重试一次", e)
        } catch (e: Exception) {
            // 不吞错：调用方（人设生成 VM 带 onError 回调；WorkflowEngine 有外层 catch）
            // 需要拿到具体失败原因（key 失效/模型 404/超时等），否则用户看到的是「点了没反应」
            SecureLog.w("AiService", "Generation call failed after all keys: ${e.message}")
            throw Exception("AI 生成请求失败：${e.message ?: "未知错误"}", e)
        }
    }

    private suspend fun summarizeWithAi(
        messages: List<ChatMessage>,
        companionNameMap: Map<Long, String>,
        memoryContext: String
    ): String? {
        if (messages.isEmpty()) return null

        val conversationText = buildString {
            messages.forEach { msg ->
                val role = if (msg.isFromUser) "用户" else (companionNameMap[msg.companionId] ?: "AI")
                val content = msg.content
                    .replace(Regex("\\[.*?\\]"), "")
                    .replace(Regex("（.*?）"), "")
                    .trim()
                if (content.isNotBlank()) {
                    appendLine("$role: $content")
                }
            }
        }

        if (conversationText.isBlank()) return null

        val provider = summaryProvider
        if (provider != null && provider.isSummarySupported()) {
            return try {
                provider.summarize(
                    conversationText,
                    memoryContext,
                    com.yunian.ai.database.repository.SummaryPurpose.HISTORY
                )
            } catch (e: Exception) {
                SecureLog.w("AiService", "summarizeWithAi via SummaryProvider failed: ${e.message}")
                null
            }
        }

        SecureLog.w("AiService", "summarizeWithAi: SummaryProvider not available")
        return null
    }

    private suspend fun callOpenAiCompatibleLight(
        config: ApiConfig,
        messages: List<Message>,
        temperature: Double,
        maxTokens: Int,
        client: OkHttpClient = lightHttpClient
    ): String {
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val allKeys = resolveKeysWithPartnerFallback(config).second
        var lastException: Exception? = null

        val lightClient = client

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val jsonArray = org.json.JSONArray()
                for (msg in messages) {
                    val msgObj = org.json.JSONObject()
                    msgObj.put("role", msg.role)
                    msgObj.put("content", msg.content)
                    jsonArray.put(msgObj)
                }
                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                jsonBody.put("stream", false)
                if (!requiresFixedTemperature(config.model)) {
                    // temperature: Double —— 经助手取 2 位小数，防 Float 派生值带伪影
                    jsonBody.put("temperature", temperature.toApiTemperature())
                }

                val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                    "max_completion_tokens"
                } else {
                    "max_tokens"
                }
                jsonBody.put(maxTokensParam, maxTokens)

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                addProviderAuthHeaders(requestBuilder, config, currentKey)
                val request = requestBuilder
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = executeAdaptive(config, request, lightClient)
                val body = response.body?.string() ?: throw Exception("Empty response")
                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }
                ensureNotHtml(body, response)
                val parsed = ChatBodyAdapter.decodeCompletionBody(body)
                if (parsed.error != null) throw Exception(parsed.error.message ?: "API error")

                SecureLog.api("LIGHT", "Key ${keyIndex + 1}/${allKeys.size} success!")
                return parsed.choices?.firstOrNull()?.message?.content ?: ""
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                SecureLog.w("AiService", "Light call Key ${keyIndex + 1}/${allKeys.size} timeout: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            } catch (e: Exception) {
                lastException = e
                SecureLog.w("AiService", "Light call Key ${keyIndex + 1}/${allKeys.size} failed: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    data class BalanceInfo(
        val totalLimit: Double?,
        val totalUsed: Double?,
        val totalAvailable: Double?,
        val remainingBalance: Double?,
        val rawSubscription: String?,
        val rawUsage: String?
    )

    suspend fun getActiveConfig(): ApiConfig? {
        return resolveConfig()
    }

    suspend fun queryBalanceWithConfig(config: ApiConfig): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        try {
            queryBalanceInternal(config)
        } catch (e: Exception) {
            SecureLog.e("AiService", "queryBalanceWithConfig failed", e)
            Result.failure(e)
        }
    }

    suspend fun queryBalance(configId: Long? = null): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        try {
            val config = if (configId != null) {
                apiConfigRepository.getConfigById(configId)
            } else {
                resolveConfig()
            } ?: return@withContext Result.failure(Exception("未找到API配置"))
            queryBalanceInternal(config)
        } catch (e: Exception) {
            SecureLog.e("AiService", "queryBalance failed", e)
            Result.failure(e)
        }
    }

    private suspend fun queryBalanceInternal(config: ApiConfig): Result<BalanceInfo> {
        var keysToTry = config.getUserApiKeys().takeIf { it.isNotEmpty() }
            ?: config.getAllApiKeys()

        if (keysToTry.isEmpty() && config.provider == ApiProvider.PARTNER) {
            SecureLog.d("AiService", "PARTNER queryBalance: fetching keys from remote server...")
            val remoteKeys = com.yunian.ai.common.RemoteKeyProvider.fetchKeysAsync(appContext, forceRefresh = true)
            if (remoteKeys.isNotEmpty()) {
                keysToTry = remoteKeys
                SecureLog.d("AiService", "Using ${remoteKeys.size} remote keys for balance query")
            } else {
                return Result.failure(Exception("无法从服务器获取密钥"))
            }
        }

        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl).trimEnd('/')

        val balanceClient = if (config.provider == ApiProvider.PARTNER) {
            partnerHttpClient
        } else {
            balanceHttpClient
        }

        var totalLimit: Double? = null
        var totalUsed: Double? = null
        var totalAvailable: Double? = null
        var rawSub: String? = null
        var rawUsage: String? = null
        var lastError: Exception? = null

        for (key in keysToTry) {
            try {
                val subEndpoints = listOf("/dashboard/billing/subscription", "/v1/dashboard/billing/subscription")
                for (subEndpoint in subEndpoints) {
                    try {
                        val subReq = okhttp3.Request.Builder()
                            .url("$baseUrl$subEndpoint")
                            .addHeader("Authorization", "Bearer $key")
                            .get().build()
                        val subRes = executeAdaptive(config, subReq, balanceClient)
                        val subBody = subRes.body?.string() ?: ""
                        rawSub = subBody
                        if (subRes.isSuccessful && subBody.isNotBlank()) {
                            val subObj = runCatching { org.json.JSONObject(subBody) }.getOrNull()
                            if (subObj != null) {
                                totalLimit = subObj.optDouble("hard_limit_usd", subObj.optDouble("total_granted", totalLimit ?: 0.0))
                                    .takeIf { it > 0 }
                                totalUsed = subObj.optDouble("total_used", 0.0)
                                totalAvailable = subObj.optDouble("total_available", 0.0).takeIf { it > 0 }
                            }
                            break
                        }
                    } catch (_: Exception) {}
                }

                val usageEndpoints = listOf("/dashboard/billing/usage", "/v1/dashboard/billing/usage")
                for (usageEndpoint in usageEndpoints) {
                    try {
                        val usageReq = okhttp3.Request.Builder()
                            .url("$baseUrl$usageEndpoint")
                            .addHeader("Authorization", "Bearer $key")
                            .get().build()
                        val usageRes = executeAdaptive(config, usageReq, balanceClient)
                        val usageBody = usageRes.body?.string() ?: ""
                        rawUsage = usageBody
                        if (usageRes.isSuccessful && usageBody.isNotBlank()) {
                            val usageObj = runCatching { org.json.JSONObject(usageBody) }.getOrNull()
                            if (usageObj != null) {
                                val usageTotal = usageObj.optDouble("total_usage", -1.0)
                                if (usageTotal >= 0) totalUsed = usageTotal / 100.0
                            }
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (totalLimit != null || totalAvailable != null) {
                    SecureLog.api("BALANCE", "Query success with user key")
                    break
                }
            } catch (e: Exception) {
                lastError = e
                continue
            }
        }

        val remaining = when {
            totalAvailable != null -> totalAvailable
            totalLimit != null && totalUsed != null -> totalLimit - totalUsed
            else -> null
        }

        SecureLog.api("BALANCE", "Query result: limit=$totalLimit used=$totalUsed available=$totalAvailable remaining=$remaining")
        return Result.success(BalanceInfo(totalLimit, totalUsed, totalAvailable, remaining, rawSub, rawUsage))
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String, provider: ApiProvider? = null): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedBaseUrl = normalizeOpenAiBaseUrl(baseUrl)
                val url = normalizedBaseUrl.trimEnd('/') + "/models"
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                if (provider == ApiProvider.PARTNER) {
                    val session = RemoteKeyProvider.ensureSession(appContext, forceRefresh = false)
                        ?: throw Exception("Clove API 会话不可用，请重新测试连接")
                    requestBuilder.addHeader("X-LianYu-Session", session.token)
                    requestBuilder.addHeader("X-LianYu-Client-Id", session.clientId)
                } else if (provider != null && prefersApiKeyHeader(provider)) {
                    requestBuilder.addHeader("api-key", apiKey)
                } else {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                val request = requestBuilder.get().build()

                SecureLog.api("MODELS", "Fetching models from ${url.take(60)}...")

                val fetchClient = when {
                    provider == ApiProvider.PARTNER -> partnerHttpClient
                    else -> okHttpClient
                }

                val response = runCatching {

                    val future = fetchModelsExecutor.submit<okhttp3.Response> {

                        fetchClient.newCall(request).execute()
                    }
                    future.get(25, java.util.concurrent.TimeUnit.SECONDS)
                }.getOrElse { e ->
                    throw if (e is java.util.concurrent.TimeoutException)
                        java.net.SocketTimeoutException("Request timeout after 25s (DNS/proxy may be unreachable)")
                    else if (e is java.util.concurrent.ExecutionException) e.cause ?: e
                    else e
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    SecureLog.api("MODELS", "HTTP ${response.code}: ${errorMsg ?: "no error body"}")
                    return@withContext Result.failure(Exception(errorMsg ?: "HTTP " + response.code))
                }

                if (body.trimStart().startsWith("<!") || body.trimStart().startsWith("<html", ignoreCase = true)) {
                    return@withContext Result.failure(Exception("该API不支持模型列表查询"))
                }

                val modelsResponse = json.decodeFromString<ModelsListResponse>(body)
                if (modelsResponse.error != null) {
                    SecureLog.api("MODELS", "API error: ${modelsResponse.error.message}")
                    return@withContext Result.failure(Exception(modelsResponse.error.message ?: "Unknown error"))
                }

                val models = modelsResponse.data?.mapNotNull { it.id } ?: emptyList()
                SecureLog.api("MODELS", "Found ${models.size} models")
                Result.success(models)
            } catch (e: Exception) {
                SecureLog.e("AiService", "fetchModels failed", e)
                Result.failure(e)
            }
        }
    }

    private fun normalizeOpenAiBaseUrl(baseUrl: String): String {

        return baseUrl.trim().trimEnd('/')
    }

    private fun usesMaxCompletionTokens(provider: ApiProvider): Boolean {
        return provider == ApiProvider.XIAOMI
    }

    private fun prefersApiKeyHeader(provider: ApiProvider): Boolean {
        return provider == ApiProvider.XIAOMI
    }

    private suspend fun addProviderAuthHeaders(
        requestBuilder: okhttp3.Request.Builder,
        config: ApiConfig,
        credential: String
    ) {
        if (config.provider == ApiProvider.PARTNER) {
            val session = RemoteKeyProvider.ensureSession(appContext, forceRefresh = false)
                ?: throw Exception("Clove API 会话不可用，请重新测试连接")
            requestBuilder.addHeader("X-LianYu-Session", session.token)
            requestBuilder.addHeader("X-LianYu-Client-Id", session.clientId)
        } else if (prefersApiKeyHeader(config.provider)) {
            requestBuilder.addHeader("api-key", credential)
        } else {
            requestBuilder.addHeader("Authorization", "Bearer $credential")
        }
    }

    fun shouldProactivelyMessage(companion: CompanionModel, recentMessages: List<ChatMessage>): Boolean {
        return AiPromptBuilder.shouldProactivelyMessage(companion, recentMessages)
    }

    fun shouldProactivelyMessage(
        companion: CompanionModel,
        recentMessages: List<ChatMessage>,
        settings: ProactiveMessageSettings?
    ): Boolean {
        return AiPromptBuilder.shouldProactivelyMessage(companion, recentMessages, settings)
    }

    private fun extractDirectReply(text: String): String {
        val trimmed = text.trim()

        val quoteMatches = Regex("""[\"“](.+?)[\"”]""", RegexOption.DOT_MATCHES_ALL).findAll(trimmed).toList()
        if (quoteMatches.isNotEmpty()) {
            val quoted = quoteMatches.joinToString("\n") { it.groupValues[1].trim() }
            if (quoted.isNotBlank() && quoted.length >= 2) return quoted
        }

        val paragraphs = trimmed.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }
        if (paragraphs.size >= 2) {
            val last = paragraphs.last()
            val first = paragraphs.first()
            if (last.length <= 80 && first.length > last.length * 2) {
                return last
            }
        }

        val metaMarkers = listOf(
            "用户说", "用户问", "用户想", "用户希望", "我得", "我要", "我需要", "我应该",
            "这是", "这是在", "顺着", "氛围", "接话", "回复", "回答", "思考过程",
            "内心独白", "不能让任何人", "知道你是AI", "你是AI", "作为AI", "模型"
        )
        val sentences = trimmed.split(Regex("""[。！？!?]""")).map { it.trim() }.filter { it.isNotBlank() }
        val filtered = sentences.filter { sentence ->
            metaMarkers.none { marker -> sentence.contains(marker) }
        }
        return if (filtered.isNotEmpty()) filtered.joinToString("。") else trimmed
    }

    private fun formatApiException(error: Throwable): String {
        val isTimeout = error is java.net.SocketTimeoutException ||
                error.message?.contains("timeout", ignoreCase = true) == true ||
                error.message?.contains("timed out", ignoreCase = true) == true

        if (isTimeout) {
            return "[TOAST]网络连接超时，请检查网络后重试"
        }

        val message = when (error) {
            is HttpException -> {
                val errorBody = error.response()?.errorBody()?.string()
                val parsedMessage = errorBody?.let { body ->
                    runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                        ?: runCatching { json.decodeFromString<AnthropicResponse>(body).error?.message }.getOrNull()
                        ?: runCatching { json.decodeFromString<GeminiResponse>(body).error?.message }.getOrNull()
                }
                parsedMessage ?: "HTTP ${error.code()} ${error.message()}"
            }
            else -> error.message
        }?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

        return "[TOAST]API调用失败：$message"
    }

    fun buildSystemPromptForLocal(
        companion: CompanionModel,
        memoryContext: String = "",
        lastUserMessage: String = "",
        availableStickers: List<String> = emptyList(),
        stickerProbability: Int = 30,
        innerThoughtEnabled: Boolean = false,
        ntpTimeEnabled: Boolean = false,
        role: CompanionRole = CompanionRole.GIRLFRIEND,
        phase: ConversationPhase = ConversationPhase.TOPIC,
        allowEnvAnchor: Boolean = true,
    ): String =
        AiPromptBuilder.buildSystemPromptForLocal(
            companion,
            memoryContext,
            lastUserMessage,
            availableStickers,
            stickerProbability,
            innerThoughtEnabled,
            ntpTimeEnabled,
            role,
            phase,
            allowEnvAnchor,
        )

    private fun buildProactiveSystemPrompt(
        companion: CompanionModel,
        memoryContext: String = "",
        settings: ProactiveMessageSettings? = null,
        allowEnvAnchor: Boolean = true,
    ): String {
        return AiPromptBuilder.buildProactiveSystemPrompt(
            companion = companion,
            memoryContext = memoryContext,
            settings = settings,
            role = CompanionRole.GIRLFRIEND,
            allowEnvAnchor = allowEnvAnchor,
        )
    }

    private suspend fun resolveAllowEnvAnchor(
        companionId: Long,
        history: List<ChatMessage>,
    ): Boolean {
        val recentAi = history
            .asReversed()
            .asSequence()
            .filter { !it.isFromUser }
            .take(ChatConstants.ENV_ANCHOR_RECENT_LOOKBACK)
            .map { it.content }
            .toList()
        return envAnchorStore.allowEnvAnchor(companionId, recentAi)
    }

    private suspend fun maybeMarkEnvAnchor(companionId: Long, text: String) {
        if (EnvAnchorCooldown.looksLikeEnvCare(text)) {
            envAnchorStore.markEnvAnchor(companionId)
            SecureLog.d("AiService", "Marked env anchor companion=$companionId")
        }
    }

    suspend fun callOpenAiCompatibleForTest(config: ApiConfig, messages: List<Message>): String {

        val content = callOpenAiCompatibleLight(
            config = config,
            messages = messages,
            temperature = 0.0,
            maxTokens = 1
        )
        if (content.isBlank()) {

            return content
        }
        return stripThinkingContent(content)
    }

    private suspend fun callOpenAiCompatible(config: ApiConfig, messages: List<Message>): String {
        return callOpenAiCompatibleWithReasoning(config, messages).content
    }

    private suspend fun resolveKeysWithPartnerFallback(config: ApiConfig): Pair<Int, List<String>> {
        val (startIdx, keys) = selectApiKey(config)
        if (config.provider == ApiProvider.PARTNER && !com.yunian.ai.common.RemoteKeyProvider.isBuiltinCloudAccessAllowed()) {
            throw IllegalStateException("内置免费云端服务当前不可用")
        }
        if (keys.isEmpty() && config.provider == ApiProvider.PARTNER) {
            SecureLog.d("AiService", "PARTNER keys empty, fetching from RemoteKeyProvider...")
            val remoteKeys = com.yunian.ai.common.RemoteKeyProvider.fetchKeysAsync(appContext, forceRefresh = false)
            if (remoteKeys.isNotEmpty()) {
                SecureLog.d("AiService", "Fetched ${remoteKeys.size} remote keys for PARTNER send path")
                return 0 to remoteKeys
            }

            com.yunian.ai.common.RemoteKeyProvider.lastCloudError?.let { cloudError ->
                throw IllegalStateException(cloudError.friendlyMessage())
            }
            SecureLog.w("AiService", "RemoteKeyProvider returned no keys for PARTNER")
        }
        return startIdx to keys
    }

    private suspend fun callOpenAiCompatibleWithReasoning(config: ApiConfig, messages: List<Message>): ChatCallResult {

        return callOpenAiCompatibleWithTools(config, messages, toolsJson = null)
    }

    private fun openAiCompatibleSseLineFlow(
        config: ApiConfig,
        messages: List<Message>,
    ): Flow<String> = flow {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val (startIdx, allKeys) = resolveKeysWithPartnerFallback(config)
        if (allKeys.isEmpty()) throw Exception("没有可用的 API Key")

        val jsonArray = org.json.JSONArray()
        for (msg in messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)
            msgObj.putOpt("content", msg.content)
            msg.tool_calls?.let { toolCalls ->
                val tcArray = org.json.JSONArray()
                for (tc in toolCalls) {
                    val tcObj = org.json.JSONObject()
                    tcObj.put("id", tc.id)
                    tcObj.put("type", tc.type)
                    val fnObj = org.json.JSONObject()
                    fnObj.put("name", tc.function.name)
                    fnObj.put("arguments", tc.function.arguments)
                    tcObj.put("function", fnObj)
                    tcArray.put(tcObj)
                }
                msgObj.put("tool_calls", tcArray)
            }
            msg.tool_call_id?.let { msgObj.put("tool_call_id", it) }
            jsonArray.put(msgObj)
        }

        var lastException: Exception? = null
        for (i in allKeys.indices) {
            val keyIndex = (startIdx + i) % allKeys.size
            val currentKey = allKeys[keyIndex]
            var call: okhttp3.Call? = null
            try {
                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                jsonBody.put("stream", true)

                jsonBody.put("stream_options", org.json.JSONObject().put("include_usage", true))
                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toApiTemperature())
                }
                // 复读惩罚（provider 门控）：抑制「换种说法再讲一遍」的语义级复述。
                // 仅标准 OpenAI 语义 provider 注入，其余不注入以免未知字段被 400（见 ApiPenalty.kt）。
                jsonBody.applyRepetitionPenalty(config.provider)
                // 仅当用户显式配置了 Max Tokens 才发送该字段：默认写死 800 会被推理模型
                // 整个消耗在思考过程上，导致 content 为空（"模型仅返回了思考过程"）。
                // 行为说明：不配置就交给服务端使用模型默认输出上限。
                config.maxTokens?.takeIf { it > 0 }?.let { maxTokens ->
                    val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                        "max_completion_tokens"
                    } else {
                        "max_tokens"
                    }
                    jsonBody.put(maxTokensParam, maxTokens)
                }

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                addProviderAuthHeaders(requestBuilder, config, currentKey)
                val request = requestBuilder
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val client = getStreamingClient(config)
                call = client.newCall(request)
                val response = call.execute()
                val body = response.body
                if (body == null) {
                    response.close()
                    throw Exception("Empty response")
                }

                if (!response.isSuccessful) {
                    val errBody = runCatching { body.string() }.getOrDefault("")
                    response.close()
                    val errorMsg = if (errBody.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(errBody).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val peek = body.source().peek()
                val firstBytes = peek.readUtf8(minOf(16L, peek.request(16L).let { if (it) 16L else peek.buffer.size }))
                if (firstBytes.trimStart().startsWith("<!") || firstBytes.trimStart().startsWith("<html", ignoreCase = true)) {
                    response.close()
                    throw Exception("服务器返回了网页而非API响应 (HTTP ${response.code})，请检查API密钥/地址是否正确")
                }

                // 反向适配：接口不支持流式时会把完整 JSON 一次性返回。
                // 此时没有任何 data: 分片，流式适配器会收到 0 个事件并最终报“API返回空内容”，
                // 因此这里把它合成单条 SSE 事件再交给下游。
                val headPeek = body.source().peek()
                headPeek.request(1024L)
                val headText = headPeek.readUtf8(minOf(1024L, headPeek.buffer.size.toLong()))
                val headTrimmed = headText.trimStart()
                if (headTrimmed.startsWith("{") || headTrimmed.startsWith("[")) {
                    val fullBody = body.string()
                    response.close()
                    ChatBodyAdapter.plainJsonToSseLines(fullBody).forEach { emit(it) }
                    return@flow
                }

                SecureLog.api("HTTP", "stream code=${response.code}, protocol=${response.protocol}")
                try {
                    body.source().use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            emit(line)
                        }
                    }
                } finally {
                    response.close()
                }
                return@flow
            } catch (e: CancellationException) {
                call?.cancel()
                throw e
            } catch (e: Exception) {
                call?.cancel()
                lastException = e
                if (e.message?.let { msg -> msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429") } == true) {
                    markKeyFailed(currentKey)
                }
                SecureLog.w("AiService", "Stream Key #${keyIndex + 1}/${allKeys.size} 失败: ${e.message}")
                if (i < allKeys.size - 1) continue else throw lastException
            }
        }
        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    private suspend fun callOpenAiCompatibleWithTools(
        config: ApiConfig,
        messages: List<Message>,
        toolsJson: String?
    ): ChatCallResult {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val (startIdx, allKeys) = resolveKeysWithPartnerFallback(config)
        var lastException: Exception? = null

        val jsonArray = org.json.JSONArray()
        for (msg in messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)

            msgObj.putOpt("content", msg.content)

            msg.tool_calls?.let { toolCalls ->
                val tcArray = org.json.JSONArray()
                for (tc in toolCalls) {
                    val tcObj = org.json.JSONObject()
                    tcObj.put("id", tc.id)
                    tcObj.put("type", tc.type)
                    val fnObj = org.json.JSONObject()
                    fnObj.put("name", tc.function.name)
                    fnObj.put("arguments", tc.function.arguments)
                    tcObj.put("function", fnObj)
                    tcArray.put(tcObj)
                }
                msgObj.put("tool_calls", tcArray)
            }

            msg.tool_call_id?.let { msgObj.put("tool_call_id", it) }
            jsonArray.put(msgObj)
        }

        for (i in 0 until allKeys.size) {
            val keyIndex = (startIdx + i) % allKeys.size
            val currentKey = allKeys[keyIndex]
            try {
                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                jsonBody.put("stream", false)
                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toApiTemperature())
                }
                // 复读惩罚（provider 门控）：非流式聊天/工具生成路径同样注入（见 ApiPenalty.kt）。
                jsonBody.applyRepetitionPenalty(config.provider)
                // 仅当用户显式配置了 Max Tokens 才发送该字段：默认写死 800 会被推理模型
                // 整个消耗在思考过程上，导致 content 为空（"模型仅返回了思考过程"）。
                // 行为说明：不配置就交给服务端使用模型默认输出上限。
                config.maxTokens?.takeIf { it > 0 }?.let { maxTokens ->
                    val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                        "max_completion_tokens"
                    } else {
                        "max_tokens"
                    }
                    jsonBody.put(maxTokensParam, maxTokens)
                }

                if (!toolsJson.isNullOrBlank() && toolsJson != "[]") {
                    jsonBody.put("tools", org.json.JSONArray(toolsJson))
                    jsonBody.put("tool_choice", "auto")
                }

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                addProviderAuthHeaders(requestBuilder, config, currentKey)
                val request = requestBuilder
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val client = getEffectiveClient(config)
                // 工具轮次上下文更大、模型更慢：沿用客户端的 30s 总预算会让靠后轮次超时，
                // 并被上层误报成「网络连接超时」。此处对带工具的请求单独放宽预算。
                val hasTools = !toolsJson.isNullOrBlank() && toolsJson != "[]"
                val call = client.newCall(request)
                if (hasTools) {
                    call.timeout().timeout(
                        TimeoutBudgets.HTTP_TOOL_CALL_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
                val response = call.execute()
                SecureLog.api("HTTP", "code=${response.code}, protocol=${response.protocol}")
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                ensureNotHtml(body, response)
                val parsed = ChatBodyAdapter.decodeCompletionBody(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                val choice = parsed.choices?.firstOrNull()
                val message = choice?.message
                val rawContent = message?.content
                val finishReason = choice?.finish_reason

                val fieldReasoning = extractReasoningFromBody(body, message)
                val (cleanedContent, tagReasoning) = if (!rawContent.isNullOrBlank()) {
                    ResponsePostProcessor.extractThinkingContent(rawContent)
                } else {
                    "" to null
                }
                val reasoning = listOfNotNull(fieldReasoning, tagReasoning)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("\n\n")
                    .ifBlank { null }

                SecureLog.api(
                    "RESPONSE",
                    "len=${rawContent?.length ?: 0}, reasoningLen=${reasoning?.length ?: 0}, finish=$finishReason"
                )

                val toolCalls = message?.tool_calls?.map { tc ->
                    com.yunian.ai.domain.AiToolCall(
                        id = tc.id,
                        name = tc.function.name,
                        arguments = tc.function.arguments
                    )
                }

                if (!toolCalls.isNullOrEmpty()) {
                    return ChatCallResult("", reasoning, toolCalls, finishReason ?: "tool_calls", parsed.usage)
                }

                val content = cleanedContent
                if (content.isBlank()) {
                    throw Exception(blankContentReason(finishReason, reasoning))
                }

                return ChatCallResult(content, reasoning, null, finishReason, parsed.usage)
            } catch (e: Exception) {
                lastException = e
                if (e.message?.let { msg -> msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429") } == true) {
                    markKeyFailed(currentKey)
                }
                SecureLog.w("AiService", "Chat Key #${keyIndex + 1}/${allKeys.size} 失败: ${e.message}")
                if (i < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    private data class ChatCallResult(
        val content: String,
        val reasoning: String? = null,
        val toolCalls: List<com.yunian.ai.domain.AiToolCall>? = null,
        val finishReason: String? = null,
        val usage: Usage? = null,
    )

    private fun stripThinkingContent(content: String): String =
        ResponsePostProcessor.stripThinkingContent(content)

    /**
     * 正文为空时给出可操作的原因。
     *
     * 最常见的是推理模型把输出上限整段花在思考过程上（finish_reason=length），
     * 此时提示调大 Max Tokens，而不是让用户对着「仅返回了思考过程」反复重试。
     */
    private fun blankContentReason(finishReason: String?, reasoning: String?): String = when {
        finishReason.equals("length", ignoreCase = true) ->
            "模型把输出上限全部用在思考过程上了（max_tokens 偏小）。请在「API 设置」中把 Max Tokens 调大，或留空以使用模型默认上限"
        reasoning.isNullOrBlank() ->
            "API返回空内容，请检查模型名是否正确"
        else ->
            "模型仅返回了思考过程，未生成实际回复，请重试"
    }

    private suspend fun extractReasoningFromBody(
        body: String,
        message: Message?
    ): String? {
        val configuredField = runCatching { appSettingsStore.getReasoningResponseField() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val candidates = linkedSetOf<String>().apply {
            if (configuredField.isNotBlank()) add(configuredField)
            add("reasoning_content")
            add("reasoning")
            add("thinking")
            add("thought")
        }

        message?.reasoning_content?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        return runCatching {
            val root = org.json.JSONObject(body)
            val msgObj = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: return@runCatching null
            for (field in candidates) {
                val value = msgObj.optString(field, "").trim()
                if (value.isNotBlank()) return@runCatching value
            }
            null
        }.getOrNull()
    }

    suspend fun callAnthropicForTest(config: ApiConfig, messages: List<Message>, systemPrompt: String): String {
        val anthropicMessages = messages.filter { it.role != "system" }.map {
            AnthropicMessage(
                role = if (it.role == "user") "user" else "assistant",
                content = it.content ?: ""
            )
        }

        val request = AnthropicRequest(
            model = config.model,
            messages = anthropicMessages,
            system = systemPrompt,
            max_tokens = 5,
            temperature = config.temperature
        )

        val baseUrl = config.baseUrl.trim().removeSuffix("/")
        val url = "$baseUrl/messages"

        val response = anthropicApi.chatCompletion(
            url = url,
            apiKey = config.apiKey,
            request = request
        )

        if (response.error != null) {
            throw Exception(response.error.message ?: "API返回错误")
        }

        return response.content?.firstOrNull()?.text
            ?: throw Exception("API返回空内容")
    }

    private suspend fun callAnthropic(config: ApiConfig, messages: List<Message>, systemPrompt: String): ChatCallResult {
        val anthropicMessages = messages.filter { it.role != "system" }.map {
            AnthropicMessage(
                role = if (it.role == "user") "user" else "assistant",
                content = it.content ?: ""
            )
        }

        val request = AnthropicRequest(
            model = config.model,
            messages = anthropicMessages,
            system = systemPrompt,
            max_tokens = config.maxTokens ?: 800,
            temperature = config.temperature
        )

        val baseUrl = config.baseUrl.trim().removeSuffix("/")
        val url = "$baseUrl/messages"

        val allKeys = resolveKeysWithPartnerFallback(config).second
        if (allKeys.isEmpty()) throw Exception("没有可用的 API Key")
        var lastException: Exception? = null

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val response = anthropicApi.chatCompletion(
                    url = url,
                    apiKey = currentKey,
                    request = request
                )

                if (response.error != null) {
                    throw Exception(response.error.message ?: "API返回错误")
                }

                val text = response.content?.firstOrNull()?.text
                    ?: throw Exception("API返回空内容")

                return ChatCallResult(
                    content = stripThinkingContent(text),
                    usage = response.usage?.let {
                        Usage(
                            prompt_tokens = it.input_tokens,
                            completion_tokens = it.output_tokens,
                            total_tokens = (it.input_tokens ?: 0L) + (it.output_tokens ?: 0L)
                        )
                    }
                )
            } catch (e: Exception) {
                lastException = e
                SecureLog.w("AiService", "Anthropic Key #${keyIndex + 1}/${allKeys.size} 失败: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    suspend fun callGeminiForTest(config: ApiConfig, messages: List<Message>, systemPrompt: String): String {
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val jsonArray = org.json.JSONArray()
        for (msg in messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            jsonArray.put(msgObj)
        }
        val jsonBody = org.json.JSONObject()
        jsonBody.put("model", config.model)
        jsonBody.put("messages", jsonArray)
        jsonBody.put("stream", false)
        if (!requiresFixedTemperature(config.model)) {
            jsonBody.put("temperature", 0.7.toApiTemperature())
        }

        val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
        jsonBody.put(maxTokensParam, 5)

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
        addProviderAuthHeaders(requestBuilder, config, config.apiKey)
        val request = requestBuilder
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeAdaptive(config, request)
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorMsg = runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                ?: "HTTP ${response.code}"
            throw Exception(errorMsg)
        }

        val parsed = ChatBodyAdapter.decodeCompletionBody(body)
        if (parsed.error != null) {
            throw Exception(parsed.error.message ?: "API返回错误")
        }

        return parsed.choices?.firstOrNull()?.message?.content
            ?: throw Exception("API返回空内容")
    }

    private suspend fun recordTokenUsage(companionId: Long, inputTokens: Long, outputTokens: Long) {
        try {
            tokenUsageRepository.recordTokenUsage(
                companionId = companionId,
                inputTokens = inputTokens,
                outputTokens = outputTokens
            )

            SecureLog.api("TOKEN", "Recorded usage for companion=$companionId, in=$inputTokens, out=$outputTokens")
        } catch (e: Exception) {
            SecureLog.w("AiService", "Failed to record token usage: ${e.message}")
        }
    }

    private fun resolveInputTokens(usage: Usage?, messages: List<Message>): Long =
        usage?.prompt_tokens?.takeIf { it > 0 } ?: TokenEstimator.estimate(messages).toLong()

    private fun resolveOutputTokens(usage: Usage?, responseText: String): Long =
        usage?.completion_tokens?.takeIf { it > 0 }
            ?: TokenEstimator.estimate(responseText).toLong().coerceAtLeast(1L)

    fun getTokenUsageRepository(): TokenUsageRepository = tokenUsageRepository

    suspend fun sendMessageWithImage(
        companion: CompanionModel?,
        history: List<ChatMessage>,
        imagePath: String,
        stickerProbability: Int = 30,
        ntpTimeEnabled: Boolean = false
    ): AiResponse {
        SecureLog.i("VISION", "========== sendMessageWithImage CALLED ==========")
        SecureLog.i("VISION", "imagePath=$imagePath, companion=${companion?.name ?: "NULL"}")

        if (companion == null) {
            SecureLog.e("VISION", "ERROR: companion is null!")
            return AiResponse("[TOAST]系统正在加载伴侣信息，请稍后再试")
        }

        return SecureLog.timed("AiService", "sendMessageWithImage") {
            withContext(Dispatchers.IO) {
                var config = resolveConfig(companion.id)
                SecureLog.i("VISION", "resolveConfig result: ${if (config != null) "OK (provider=${config.provider}, model=${config.model})" else "NULL"}")

                if (config == null) {
                    SecureLog.e("VISION", "ERROR: config is null!")
                    return@withContext AiResponse("[TOAST]请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。")
                }

                if (config.model.isBlank()) {
                    SecureLog.e("VISION", "ERROR: model is blank!")
                    return@withContext AiResponse("[TOAST]模型名未配置，请在「API设置」中重新测试连接以自动选择模型。")
                }

                val isVisionEnabled = try {
                    appSettingsStore.getVisionEnabled()
                } catch (e: Exception) {
                    SecureLog.w("AiService", "Failed to get vision setting, defaulting to enabled: ${e.message}")
                    true
                }

                SecureLog.i("VISION", "visionEnabled=$isVisionEnabled")

                if (!isVisionEnabled) {
                    SecureLog.w("VISION", "WARNING: Vision is DISABLED, returning early")
                    return@withContext AiResponse("[TOAST]视觉识别功能已关闭，请在「API设置」->「视觉识别设置」中开启")
                }

                val visionModelSetting = try {
                    appSettingsStore.getVisionModel()
                } catch (e: Exception) {
                    AppSettingsStore.VisionModels.VISION_AUTO
                }

                val visionProviderSetting = try {
                    appSettingsStore.getVisionProvider()
                } catch (e: Exception) {
                    "auto"
                }

                val visionApiUrlSetting = try {
                    appSettingsStore.getVisionApiUrl()
                } catch (e: Exception) {
                    ""
                }

                val visionApiKeySetting = try {
                    appSettingsStore.getVisionApiKey()
                } catch (e: Exception) {
                    ""
                }

                SecureLog.i("VISION", "Settings: modelSetting=$visionModelSetting, providerSetting=$visionProviderSetting, url=${visionApiUrlSetting.take(30)}..., key=${visionApiKeySetting.take(10)}...")
                SecureLog.i("VISION", "Original config: provider=${config.provider}, baseUrl=${config.baseUrl}, model=${config.model}, key=${config.apiKey.take(10)}...")

                if (visionProviderSetting != "auto" && (visionApiUrlSetting.isNotBlank() || visionApiKeySetting.isNotBlank())) {
                    val resolvedProvider = when (visionProviderSetting.uppercase()) {
                        "OPENAI" -> ApiProvider.OPENAI
                        "ANTHROPIC" -> ApiProvider.ANTHROPIC
                        "GEMINI" -> ApiProvider.GEMINI
                        "KIMI" -> ApiProvider.KIMI
                        "DEEPSEEK" -> ApiProvider.DEEPSEEK
                        "DASHSCOPE" -> ApiProvider.DASHSCOPE
                        "ZHIPU" -> ApiProvider.ZHIPU
                        "CUSTOM" -> ApiProvider.CUSTOM
                        else -> config.provider
                    }

                    val resolvedModel = AppSettingsStore.VisionModels.resolveVisionModel(visionModelSetting, resolvedProvider.name)
                    SecureLog.i("VISION", "Resolved: provider=$resolvedProvider, model=$resolvedModel (from setting='$visionModelSetting')")

                    val finalApiKey = visionApiKeySetting.ifBlank { config.apiKey }
                    val finalBaseUrl = visionApiUrlSetting.ifBlank { config.baseUrl }

                    SecureLog.i("VISION", "Final config: provider=$resolvedProvider, baseUrl=$finalBaseUrl, model=$resolvedModel, key=${finalApiKey.take(10)}...")

                    if (finalApiKey.isBlank()) {
                        return@withContext AiResponse("[TOAST]API密钥为空，请检查视觉模型设置中的API Key")
                    }

                    if (finalBaseUrl.isBlank()) {
                        return@withContext AiResponse("[TOAST]API地址为空，请检查视觉模型设置中的Base URL")
                    }

                    config = ApiConfig(
                        provider = resolvedProvider,
                        apiKey = finalApiKey,
                        extraApiKeys = config.extraApiKeys,
                        baseUrl = finalBaseUrl,
                        model = resolvedModel,
                        id = config.id
                    )

                    SecureLog.i("VISION", "Using independent API config: provider=${config.provider}, baseUrl=${config.baseUrl}, model=${config.model}")
                } else {
                    SecureLog.i("VISION", "Using main API config (provider=auto mode)")

                    when (visionModelSetting) {
                        AppSettingsStore.VisionModels.VISION_AUTO -> {

                            SecureLog.i("VISION", "Keeping original main API config: provider=${config.provider}, model=${config.model}, baseUrl=${config.baseUrl}")
                        }
                        else -> {

                            val resolvedModel = visionModelSetting
                            if (resolvedModel != config.model) {
                                config = config.copy(model = resolvedModel)
                                SecureLog.i("VISION", "User-specified vision model override: ${config.model}")
                            }
                        }
                    }
                }

                val sortedHistory = history.sortedBy { it.timestamp }
                val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
                val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
                val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, limit = 50)
                val stickerManager = StickerManager.getInstance(appContext)
                // 收口到 StickerManager 唯一装配入口：不按长度静默丢弃（修「规则丢失→内部文件名→被丢」），
                // 自定义表情优先占位、内置超预算才截断（见 StickerPromptNames）。
                val availableStickers = stickerManager.getStickerNamesForPrompt()
                val phase = ConversationPhaseDetector.detect(sortedHistory)
                val allowEnvAnchor = resolveAllowEnvAnchor(companion.id, sortedHistory)
                // 自定义表情语义清单（E2 段）：未导入时为空 → 提示词逐字节零变化
                val customPromptStickers = stickerManager.getPromptStickers().filter { it.isCustom }
                val baseSystemPrompt = AiPromptBuilder.buildStableSystemPrompt(
                    companion,
                    availableStickers,
                    stickerProbability,
                    innerThoughtEnabled,
                    CompanionRole.GIRLFRIEND,
                    customPromptStickers,
                )
                val rawSystemPrompt = appendYanderePromptIfNeeded(baseSystemPrompt, companion) + "\n\n" +
                    AiPromptBuilder.buildTurnContext(
                        lastUserMessage = lastUserMessage,
                        ntpTimeEnabled = ntpTimeEnabled,
                        phase = phase,
                        allowEnvAnchor = allowEnvAnchor,
                        history = sortedHistory,
                    ) +
                    if (memoryContext.isNotBlank()) "\n\n关于用户的记忆：\n$memoryContext\n" else ""

                SecureLog.api("VISION", "provider=${config.provider}, model=${config.model}, image=$imagePath, phase=$phase, allowEnv=$allowEnvAnchor")
val systemPrompt = resolvePlaceholders(rawSystemPrompt, companion, config)

                try {
                    SecureLog.i("VISION", "Starting image encoding: $imagePath")
                    val imageBase64 = encodeImageToBase64(imagePath)
                    val mimeType = getImageMimeType(imagePath)
                    SecureLog.i("VISION", "Image encoded successfully: size=${imageBase64.length} chars, mimeType=$mimeType")

                    val visionClient = visionHttpClient

                    val rawResponse = when (config.provider) {
                        ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.IFLYTEK, ApiProvider.PARTNER -> {
                            callOpenAiCompatibleVision(config, sortedHistory, systemPrompt, lastUserMessage, imageBase64, mimeType, visionClient)
                        }
                        ApiProvider.ANTHROPIC -> {
                            callAnthropicVision(config, sortedHistory, systemPrompt, imageBase64, mimeType, visionClient)
                        }
                    }

                    if (rawResponse.content.isBlank()) {
                        throw Exception("API返回空内容，请检查模型是否支持视觉功能")
                    }

                    recordTokenUsage(
                        companion.id,
                        resolveInputTokens(rawResponse.usage, listOf(Message("system", systemPrompt)) + sortedHistory.map { Message(if (it.isFromUser) "user" else "assistant", it.content) }),
                        resolveOutputTokens(rawResponse.usage, rawResponse.content)
                    )

                    val cleaned = AiPromptBuilder.applyPersonaPostProcessing(rawResponse.content, sortedHistory)
                    SecureLog.api("VISION", "Response length=${cleaned.length}")

                    val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                    if (!safetyResult.isSafe) {
                        SecureLog.w("AiService", "sendMessageWithImage output blocked: ${safetyResult.reason}")

                        return@withContext AiResponse("抱歉，我无法继续这个话题。")
                    }

                    maybeMarkEnvAnchor(companion.id, cleaned)
                    AiResponse(cleaned)
                } catch (e: java.net.SocketTimeoutException) {
                    SecureLog.e("AiService", "sendMessageWithImage timeout", e)
                    AiResponse("[TOAST]图片识别超时，请检查网络连接后重试（图片可能过大）")
                } catch (e: Exception) {
                    SecureLog.e("AiService", "sendMessageWithImage failed", e)
                    val errorMessage = when {
                        e.message?.contains("vision", ignoreCase = true) == true ||
                        e.message?.contains("image", ignoreCase = true) == true ->
                            "[TOAST]当前模型不支持视觉功能，请在「视觉识别设置」中选择支持图片识别的模型"
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("timed out", ignoreCase = true) == true ->
                            "[TOAST]图片识别请求超时，请尝试发送更小的图片"
                        e.message?.contains("429", ignoreCase = true) == true ||
                        e.message?.contains("rate limit", ignoreCase = true) == true ->
                            "[TOAST]请求过于频繁，请稍后再试"
                        e.message?.contains("401", ignoreCase = true) == true ||
                        e.message?.contains("403", ignoreCase = true) == true ->
                            "[TOAST]API认证失败，请检查密钥是否有效"
                        else -> formatApiException(e)
                    }
                    AiResponse(errorMessage)
                }
            }
        }
    }

    private fun encodeImageToBase64(imagePath: String): String {
        return try {
            val file = java.io.File(imagePath)
            if (!file.exists()) throw Exception("图片文件不存在: $imagePath")

            val bytes = file.readBytes()
            if (bytes.isEmpty()) throw Exception("图片文件为空")

            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            SecureLog.e("AiService", "encodeImageToBase64 failed", e)
            throw Exception("图片编码失败: ${e.message}")
        }
    }

    private fun getImageMimeType(imagePath: String): String {
        return when (imagePath.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private suspend fun callOpenAiCompatibleVision(
        config: ApiConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        lastUserMessage: String,
        imageBase64: String,
        mimeType: String,
        client: OkHttpClient = okHttpClient
    ): ChatCallResult {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val allKeys = resolveKeysWithPartnerFallback(config).second
        if (allKeys.isEmpty()) {
            throw Exception("API Key 为空，请检查视觉模型配置")
        }
        var lastException: Exception? = null

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val messagesJson = org.json.JSONArray()

                messagesJson.put(buildSystemMessageJson(systemPrompt))

                val recentHistory = history.takeLast(12)
                recentHistory.forEach { msg ->
                    if (msg.isFromUser && msg.type == MessageType.IMAGE) {
                        val userMessageJson = org.json.JSONObject()
                        userMessageJson.put("role", "user")

                        val contentArray = org.json.JSONArray()
                        val textPart = org.json.JSONObject()
                        textPart.put("type", "text")
                        textPart.put("text", "请仔细观察这张图片，描述你看到的内容，并根据上下文进行回复。")
                        contentArray.put(textPart)

                        val imagePart = org.json.JSONObject()
                        imagePart.put("type", "image_url")
                        val imageUrlObj = org.json.JSONObject()
                        imageUrlObj.put("url", "data:$mimeType;base64,$imageBase64")
                        imagePart.put("image_url", imageUrlObj)
                        contentArray.put(imagePart)

                        userMessageJson.put("content", contentArray)
                        messagesJson.put(userMessageJson)
                    } else {
                        val msgObj = org.json.JSONObject()
                        msgObj.put("role", if (msg.isFromUser) "user" else "assistant")
                        msgObj.put("content", msg.content)
                        messagesJson.put(msgObj)
                    }
                }

                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", messagesJson)
                jsonBody.put("stream", false)

                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toApiTemperature())
                }
                // 复读惩罚（provider 门控）：识图生成同属聊天生成，注入（见 ApiPenalty.kt）。
                jsonBody.applyRepetitionPenalty(config.provider)
                // 同聊天路径：未显式配置则不发送 max_tokens，避免推理模型的思考过程挤占额度
                config.maxTokens?.takeIf { it > 0 }?.let { maxTokens ->
                    val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                        "max_completion_tokens"
                    } else {
                        "max_tokens"
                    }
                    jsonBody.put(maxTokensParam, maxTokens)
                }

                val requestBodyStr = jsonBody.toString()
                SecureLog.i("VISION", "Request URL: $url")
                SecureLog.i("VISION", "Request model: ${config.model}")
                SecureLog.i("VISION", "Request body size: ${requestBodyStr.length} chars")

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                addProviderAuthHeaders(requestBuilder, config, currentKey)
                val request = requestBuilder
                    .post(requestBodyStr.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = executeAdaptive(config, request, client)
                val body = response.body?.string() ?: throw Exception("Empty response")
                SecureLog.i("VISION", "Response code: ${response.code}, body length: ${body.length}")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                ensureNotHtml(body, response)
                val parsed = ChatBodyAdapter.decodeCompletionBody(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                val message = parsed.choices?.firstOrNull()?.message
                var content = message?.content ?: throw Exception("API返回空内容")
                content = stripThinkingContent(content)
                return ChatCallResult(content = content, usage = parsed.usage)
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                SecureLog.w("AiService", "Vision Key ${keyIndex + 1}/${allKeys.size} timeout: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw e
            } catch (e: Exception) {
                lastException = e
                SecureLog.w("AiService", "Vision Key ${keyIndex + 1}/${allKeys.size} failed: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    private suspend fun callAnthropicVision(
        config: ApiConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        imageBase64: String,
        mimeType: String,
        client: OkHttpClient = okHttpClient
    ): ChatCallResult {
        val anthropicMessages = org.json.JSONArray()

        val recentHistory = history.takeLast(12)
        recentHistory.forEach { msg ->
            if (msg.isFromUser && msg.type == MessageType.IMAGE) {
                val userMsgObj = org.json.JSONObject()
                userMsgObj.put("role", "user")

                val contentArray = org.json.JSONArray()

                val textPart = org.json.JSONObject()
                textPart.put("type", "text")
                textPart.put("text", "请仔细观察这张图片，描述你看到的内容，并根据上下文进行回复。")
                contentArray.put(textPart)

                val imagePart = org.json.JSONObject()
                imagePart.put("type", "image")
                val sourceObj = org.json.JSONObject()
                sourceObj.put("type", "base64")
                sourceObj.put("media_type", mimeType)
                sourceObj.put("data", imageBase64)
                imagePart.put("source", sourceObj)
                contentArray.put(imagePart)

                userMsgObj.put("content", contentArray)
                anthropicMessages.put(userMsgObj)
            } else if (msg.isFromUser || !msg.isFromUser) {
                val msgObj = org.json.JSONObject()
                msgObj.put("role", if (msg.isFromUser) "user" else "assistant")
                msgObj.put("content", msg.content)
                anthropicMessages.put(msgObj)
            }
        }

        val requestBody = org.json.JSONObject()
        requestBody.put("model", config.model)
        requestBody.put("messages", anthropicMessages)
        requestBody.put("system", systemPrompt)
        requestBody.put("max_tokens", config.maxTokens ?: 800)
        requestBody.put("temperature", config.temperature.toApiTemperature())

        val baseUrl = config.baseUrl.trim().removeSuffix("/")
        val url = "$baseUrl/messages"

        val allKeys = resolveKeysWithPartnerFallback(config).second
        if (allKeys.isEmpty()) throw Exception("没有可用的 API Key")
        var lastException: Exception? = null

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", currentKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = executeAdaptive(config, request, client)
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = runCatching {
                        val errorJson = org.json.JSONObject(body)
                        errorJson.getJSONObject("error")?.getString("message")
                    }.getOrNull() ?: "HTTP ${response.code}"
                    throw Exception(errorMsg)
                }

                val responseJson = org.json.JSONObject(body)
                if (responseJson.has("error")) {
                    throw Exception(responseJson.getJSONObject("error").getString("message") ?: "API返回错误")
                }

                val contents = responseJson.getJSONArray("content")
                if (contents.length() > 0) {
                    val usage = responseJson.optJSONObject("usage")?.let {
                        Usage(
                            prompt_tokens = it.optLong("input_tokens", 0L).takeIf { t -> t > 0 },
                            completion_tokens = it.optLong("output_tokens", 0L).takeIf { t -> t > 0 }
                        )
                    }
                    return ChatCallResult(
                        content = contents.getJSONObject(0).getString("text") ?: throw Exception("API返回空内容"),
                        usage = usage
                    )
                }

                throw Exception("API返回空内容")
            } catch (e: Exception) {
                lastException = e
                SecureLog.w("AiService", "Anthropic Vision Key #${keyIndex + 1}/${allKeys.size} 失败: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    private fun buildSystemMessageJson(systemPrompt: String): org.json.JSONObject {
        val systemMsg = org.json.JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", systemPrompt)
        return systemMsg
    }

    override suspend fun sendMessage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean,
        extraSystemRules: String
    ): AiResponse {
        val entity = companion.toCompanionEntity()
        val messages = sanitizeDomainHistory(history)
        return sendMessage(entity, messages, stickerProbability, ntpTimeEnabled, extraSystemRules)
    }

    override suspend fun sendMessage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean,
        tools: List<AiTool>?,
        extraSystemRules: String
    ): AiResponse {
        if (tools.isNullOrEmpty()) {
            return sendMessage(companion, history, stickerProbability, ntpTimeEnabled, extraSystemRules)
        }
        val entity = companion.toCompanionEntity()
        val messages = sanitizeDomainHistory(history)
        return sendMessageWithTools(entity, messages, stickerProbability, ntpTimeEnabled, tools, extraSystemRules)
    }

    private fun sanitizeDomainHistory(history: List<AiChatMessage>): List<ChatMessage> {
        return com.yunian.ai.domain.AiDialogueHistoryPolicy
            .sanitizeForModel(history)
            .map { it.toChatMessage() }
    }

    private suspend fun sendMessageWithTools(
        companion: CompanionModel?,
        history: List<ChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean,
        tools: List<AiTool>,
        extraSystemRules: String = ""
    ): AiResponse {
        if (companion == null) return AiResponse("[TOAST]系统正在加载伴侣信息，请稍后再试")

        return SecureLog.timed("AiService", "sendMessageWithTools") {
            withContext(Dispatchers.IO) {
                val config = resolveConfig(companion.id)
                    ?: return@withContext AiResponse("[TOAST]请先配置并启用可用的API。")
                if (config.model.isBlank()) {
                    return@withContext AiResponse("[TOAST]模型名未配置。")
                }

                val sortedHistory = history.sortedBy { it.timestamp }
                val sanitizedHistory = if (config.provider == ApiProvider.PARTNER) {
                    sortedHistory
                } else {
                    sortedHistory.map { msg ->
                        if (msg.isFromUser) msg.copy(content = com.yunian.ai.common.safety.DifferentialPrivacyFilter.sanitize(msg.content))
                        else msg
                    }
                }
                val lastUserMessage = sanitizedHistory.lastOrNull { it.isFromUser }?.content ?: ""
                val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
                val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, limit = 50)
                val role = userRepository.selectedRole.value
                val phase = ConversationPhaseDetector.detect(sanitizedHistory)
                val allowEnvAnchor = resolveAllowEnvAnchor(companion.id, sanitizedHistory)
                val baseSystemPrompt = AiPromptBuilder.buildStableSystemPrompt(
                    companion,
                    emptyList(),
                    stickerProbability,
                    innerThoughtEnabled,
                    role,
                )
                val turnContext = AiPromptBuilder.buildTurnContext(
                    lastUserMessage = lastUserMessage,
                    ntpTimeEnabled = ntpTimeEnabled,
                    phase = phase,
                    allowEnvAnchor = allowEnvAnchor,
                    history = sanitizedHistory,
                )
                val systemPrompt = appendYanderePromptIfNeeded(baseSystemPrompt, companion).let {
                    if (extraSystemRules.isNotBlank()) "$it\n\n$extraSystemRules" else it
                }.let { resolvePlaceholders(it, companion, config) }
                val contextConfig = AutoContextManager.ContextConfig(model = config.model, provider = config.provider, maxOutputTokens = config.maxTokens ?: 4096)
                val messages = autoContextManager.build(sanitizedHistory, systemPrompt, memoryContext, lastUserMessage, emptyMap(), contextConfig, turnContext = turnContext, scope = ConversationScope.Single(companion.id), selfName = companion.name)

                SecureLog.api("SEND", "provider=${config.provider}, model=${config.model}, messages=${messages.size}, tools=${tools.size}, phase=$phase, allowEnv=$allowEnvAnchor")

                try {
                    val toolsJson = com.yunian.ai.domain.ToolRegistry.toolDefinitionsJson()
                    val result = when (config.provider) {
                        ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.IFLYTEK, ApiProvider.PARTNER -> {
                            callOpenAiCompatibleWithTools(config, messages, toolsJson)
                        }
                        ApiProvider.ANTHROPIC -> {

                            callAnthropic(config, messages, systemPrompt)
                        }
                    }
                    val rawResponse = result.content
                    val reasoning = result.reasoning
                    val toolCalls = result.toolCalls
                    val finishReason = result.finishReason

                    recordTokenUsage(
                        companion.id,
                        resolveInputTokens(result.usage, messages),
                        resolveOutputTokens(result.usage, rawResponse + (toolCalls?.joinToString("") { it.arguments } ?: ""))
                    )

                    if (!toolCalls.isNullOrEmpty()) {
                        SecureLog.api("TOOL_CALL", "count=${toolCalls.size}, names=${toolCalls.map { it.name }}")
                        return@withContext AiResponse(
                            content = "",
                            reasoningContent = reasoning,
                            toolCalls = toolCalls,
                            finishReason = finishReason
                        )
                    }

                    if (rawResponse.isBlank()) {
                        throw Exception("API返回空内容，请检查模型名是否正确")
                    }

                    val cleaned = AiPromptBuilder.applyPersonaPostProcessing(rawResponse, sortedHistory)
                    val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                    if (!safetyResult.isSafe) {
                        return@withContext AiResponse("抱歉，我无法继续这个话题。")
                    }

                    maybeMarkEnvAnchor(companion.id, cleaned)
                    AiResponse(cleaned, reasoning, null, finishReason)
                } catch (e: Exception) {
                    SecureLog.e("AiService", "sendMessageWithTools failed", e)
                    val formatted = formatApiException(e)
                    // 工具轮次耗时过长会被 formatApiException 归类成「网络连接超时」，
                    // 但那通常是轮次过多/上下文过大而非真的断网，给出可操作的准确提示。
                    throw Exception(
                        if (formatted.contains("网络连接超时")) {
                            "[TOAST]本轮工具调用耗时过长已中止。可以让我一次只做一件事，或稍后重试。"
                        } else {
                            formatted
                        }
                    )
                }
            }
        }
    }

    override suspend fun sendMessageWithImage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        imagePath: String,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean
    ): AiResponse {
        val entity = companion.toCompanionEntity()
        val messages = history.map { it.toChatMessage() }
        return sendMessageWithImage(entity, messages, imagePath, stickerProbability, ntpTimeEnabled)
    }

    private fun AiCompanionInfo.toCompanionEntity(): CompanionModel = CompanionModel(
        id = id,
        name = name,
        personality = personality,
        age = age,
        backstory = backstory,
        speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun AiChatMessage.toChatMessage(): ChatMessage {
        val normalized = com.yunian.ai.domain.AiDialogueHistoryPolicy.normalizeRole(this)

        val fromUser = when (normalized.role) {
            com.yunian.ai.domain.AiMessageRole.USER,
            com.yunian.ai.domain.AiMessageRole.TOOL -> true
            com.yunian.ai.domain.AiMessageRole.ASSISTANT,
            com.yunian.ai.domain.AiMessageRole.SYSTEM,
            null -> normalized.isFromUser
        }
        return ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = fromUser,
            timestamp = timestamp,
            type = when (type) {
                AiMessageType.TEXT -> MessageType.TEXT
                AiMessageType.IMAGE -> MessageType.IMAGE
            }
        )
    }

    override fun shouldProactivelyMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): Boolean {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return shouldProactivelyMessage(entity, messages)
    }

    override fun shouldProactivelyMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        settings: ProactiveMessageSettings?
    ): Boolean {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return shouldProactivelyMessage(entity, messages, settings)
    }

    override suspend fun generateProactiveMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): String? {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return generateProactiveMessage(entity, messages)
    }

    override suspend fun generateProactiveMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        settings: ProactiveMessageSettings?
    ): String? {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return generateProactiveMessage(entity, messages, settings)
    }

    override suspend fun generateFollowUpReminder(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        settings: ProactiveMessageSettings?
    ): String? {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return generateFollowUpReminder(entity, messages, settings)
    }

    override suspend fun generateFollowUpQuestion(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        lastAiContent: String
    ): String? {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return generateFollowUpQuestion(entity, messages, lastAiContent)
    }

    suspend fun generateFollowUpQuestion(
        companion: CompanionModel,
        recentMessages: List<ChatMessage>,
        lastAiContent: String
    ): String? = withContext(Dispatchers.IO) {
        val config = resolveConfig(companion.id)
        if (config == null || config.model.isBlank()) return@withContext null

        val sortedMessages = recentMessages.sortedBy { it.timestamp }
        val lastUserMsg = sortedMessages.lastOrNull { it.isFromUser }?.content ?: ""

        val messages = mutableListOf<Message>()
        val recentContext = sortedMessages.takeLast(10)
        for (msg in recentContext) {
            messages.add(Message(
                role = if (msg.isFromUser) "user" else "assistant",
                content = msg.content
            ))
        }

        messages.add(Message("user", buildString {
            appendLine("你是${companion.name}，用户的女朋友/男朋友，一个真实的人。你们正在微信上聊天，需要让对话自然地继续。")
            appendLine()
            appendLine("你刚回复了：\"${lastAiContent.take(100)}\"")
            appendLine("用户最后说了：\"${lastUserMsg.take(100)}\"")
            appendLine()
            appendLine("现在考虑是否追加一条追问。规则：")
            appendLine("1. 先判断：你刚才的回复是否已把话接完、是否已把话题抛回给用户？如果是，只输出「无需追问」，不要追加。")
            appendLine("2. 追问只能基于【用户最后一句】的真实内容向用户发问，禁止曲解、禁止无中生有编造问题、禁止把对话中「我/你」的角色搞反；严禁复述、延伸或总结你自己刚才说的话（那是自问自答）。")
            appendLine("3. 需要追问时：5-15字，口语化，像真人随口追问，必须针对用户说的具体内容。")
            appendLine("4. 必须是真正对用户发的问句（带问号），不是对自己叙述的补充；不要用陈述句冒充追问。")
            appendLine("5. 带语气词（呀/呢/啦/嘛/哼/嘿嘿/诶/哇），禁止万能开场白（在干嘛/想你了/好久不见）。")
            appendLine("6. 直接输出追问内容，不要解释不要思考。")
        }))

        try {
            val rawResponse = when (config.provider) {
                ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.IFLYTEK, ApiProvider.PARTNER -> {
                    callOpenAiCompatible(config, messages)
                }
                ApiProvider.ANTHROPIC -> {
                    callAnthropic(config, messages, "").content
                }
            }
            val cleaned = rawResponse
                .replace(Regex("\\r\\n|\\r|\\n+"), "")
                .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                .replace(Regex("(?is)<think[^>]*>[\\s\\S]*"), "")
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("【.*?】"), "")
                .replace(Regex("\\*.*?\\*"), "")
                .replace(Regex("<.*?>"), "")
                .trim()

            if (cleaned.length < 2) return@withContext null

            if (cleaned.contains("无需追问")) return@withContext null

            if (!cleaned.contains('?') && !cleaned.contains('？')) return@withContext null

            val safetyResult = ContentFilter.checkOutputSafety(cleaned)
            if (!safetyResult.isSafe) return@withContext null

            cleaned
        } catch (e: Exception) {
            SecureLog.w("AiService", "Follow-up question failed: ${e.message}")
            null
        }
    }

    override suspend fun callJudge(prompt: String): String {
        return callOpenAiCompatibleForJudge(prompt)
    }

    override suspend fun callGeneration(prompt: String): String {
        return callOpenAiCompatibleForGeneration(prompt)
    }

}
