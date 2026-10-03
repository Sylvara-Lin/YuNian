package com.yunian.ai

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.yunian.ai.common.AppForegroundTracker
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.DeviceIdProvider
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.common.PerformanceTrace
import com.yunian.ai.common.RomUtils
import com.yunian.ai.common.perf.PerfBoost
import com.yunian.ai.common.SaltStore
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.embedding.VectorLibrary
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.DefaultCompanionSeeder
import com.yunian.ai.database.SecurityDataSeeder
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.GroupMessageRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.EmbeddingProvider
import com.yunian.ai.database.repository.SummaryProvider
import com.yunian.ai.database.repository.DiaryProvider
import com.yunian.ai.database.repository.UnifiedMemoryRepository
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.database.timeline.RoomTimelineStore
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.YandereModeManager
import com.yunian.ai.domain.CompanionProvider
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.BuiltinCloudAccessPolicy
import com.yunian.ai.domain.CapabilityGrantStore
import com.yunian.ai.domain.imagegen.ImageGenService
import com.yunian.ai.domain.plugin.PluginEnablementStore
import com.yunian.ai.agent.plugin.PluginBlueprintStatus
import com.yunian.ai.domain.LorebookProvider
import com.yunian.ai.domain.McpManager
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.PlaceholderProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.UserProfileProvider
import com.yunian.ai.domain.timeline.TimelinePayloadCodecRegistry
import com.yunian.ai.domain.timeline.TimelineStore
import com.yunian.ai.domain.wechat.WeChatDialoguePort
import com.yunian.ai.domain.wechat.WeChatIdentityMapPort
import com.yunian.ai.domain.wechat.WeChatOutboundPort
import com.yunian.ai.wechat.WeChatDialoguePortImpl
import com.yunian.ai.wechat.WeChatIdentityMapPortImpl
import com.yunian.ai.wechat.WeChatOutboundPortImpl

import com.yunian.ai.feature.automation.data.AutomationStore
import com.yunian.ai.feature.chat.tools.SearchTools
import com.yunian.ai.feature.notification.NotificationHelper
import com.yunian.ai.push.PushManager
import com.yunian.ai.feature.wechat.service.WeChatChannelKeeper
import com.yunian.ai.feature.wechat.service.WeChatNotificationHelper
import com.yunian.ai.network.AiService
import com.yunian.ai.network.NtpTimeProvider
import com.yunian.ai.security.NativeBridge
import com.yunian.ai.security.SecurityState
import android.content.ComponentCallbacks2
import com.yunian.ai.uicommon.component.ChatBackgroundCache
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class YuNianApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.CriticalInit)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizeBytes(128 * 1024 * 1024).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "coil_images"))
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .build()

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun attachBaseContext(base: Context) {
        // 冷启动时基：进程内最早可达点（加固壳 preflight 之后、业务代码最早处）。
        PerformanceTrace.startLaunch()
        PerformanceTrace.markStartupStage("app_attach_begin")
        // Thin-shell 路径下本类才是被壳反射创建的真实 Application；安装幂等。
        runCatching { com.yunian.ai.common.crash.CrashReporter.install(base) }
        super.attachBaseContext(base)
    }

        override fun onCreate() {
        PerformanceTrace.markStartupStage("app_oncreate_begin")

        java.security.Security.setProperty("networkaddress.cache.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        System.setProperty("sun.net.spi.nameservice.nameservers", "8.8.8.8")
        System.setProperty("sun.net.spi.nameservice.domain", ".")
        PerformanceTrace.markStartupStage("oncreate_props_done")
        super.onCreate()
        PerformanceTrace.markStartupStage("oncreate_super_done")
        instance = this

        // 周期性落盘 breadcrumbs，供 Java/native/ANR 异常退出后的下次启动诊断。
        runCatching { com.yunian.ai.common.crash.CrashBreadcrumbPersister.install(this) }

        AppForegroundTracker.init()
        PerformanceTrace.markStartupStage("app_fgt_init_done")
        initBusiness(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStoredLanguage(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val imageLoader = Coil.imageLoader(this)
        when (level) {

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                imageLoader.memoryCache?.clear()
                com.yunian.ai.database.cache.MessageCache.clearAll()
                ChatBackgroundCache.clear()
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                imageLoader.memoryCache?.clear()
                ChatBackgroundCache.clear()
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                imageLoader.memoryCache?.clear()
            }
            else -> {}
        }
    }

    override fun onTerminate() {
        bgScope.launch {
            ContentFilter.destroy()
            SaltStore.shutdown()

            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
            com.yunian.ai.database.cache.HomeListCache.clear()
        }
        ServiceRegistry.clear()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: YuNianApplication
            private set

        private val bgScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
        fun initBusiness(app: Application) {
            PerformanceTrace.markStartupStage("initbusiness_begin")
            SaltStore.init(app)
            PerformanceTrace.markStartupStage("ib_salt")
            SecureLog.init(com.yunian.ai.BuildConfig.DEBUG)
            PerformanceTrace.markStartupStage("ib_securelog")
            // ADPF（Performance Hint API）：注入应用上下文并捕获主线程 native tid。
            // 运行在主线程，幂等，内部全部 runCatching；API < 31 / 服务缺失时自动降级 no-op。
            runCatching { PerfBoost.init(app) }
            PerformanceTrace.markStartupStage("ib_perfboost")
            applyStoredLanguage(app)
            PerformanceTrace.markStartupStage("ib_language")
            AiService.initialize(app)
            PerformanceTrace.markStartupStage("ib_aiservice")
            NtpTimeProvider.initialize(app)
            PerformanceTrace.markStartupStage("ib_ntp")
            clearUpdateIgnore(app)
            PerformanceTrace.markStartupStage("ib_clear_update")

            com.yunian.ai.common.ApplicationScopeProvider.init(bgScope)
            PerformanceTrace.markStartupStage("ib_scopeprovider")
            PerformanceTrace.markStartupStage("initbusiness_sync_done")

            // T02 · 预热硬件档位：把 tier 冷探测（getprop ×N + sysfs 读，典型数十毫秒）移出主线程。
            // PageTransitions 在「转场起帧那一帧」读取 HardwareInfo.tier，若此时才首次探测会阻塞主线程；
            // 启动即后台预热后，首次导航只命中缓存。幂等，且不改变同设备档位。
            bgScope.launch { HardwareInfo.warmUp() }

            bgScope.launch {
                // ── 放行屏障（T01′）──────────────────────────────────────────────────────
                //   放行前完成 provider、技能适配器、Agent 签名器和工具蓝图接线：
                //     · HomeViewModel 构造时 getOrThrow(CompanionRepository/ChatRepository)；
                //     · AppDatabase.getDatabase(app) 打开 DB。
                //   AgentRuntime 首次构造会缓存 SkillSelector，必须晚于技能适配器注册。
                //   否则冷启动首轮会永久使用默认技能存储，或漏掉签名器/自动化工具。
                //   不再与后面那串重 IO（repairUserAvatar / verifyAndRecover / seed / warm / hydrateRecent）
                //   绑成同一个门控——原先这里白等 ~800ms 才让首页出现。
                //   异常处理与原有语义保持一致：无论注册是否成功都放行（finally），避免 UI 永久卡 loading。
                try {
                    registerServiceProviders(app)
                    initAgentRuntime(app)
                    // 蓝图装载的失败**不再静默**：loadDefaultBlueprint 内部已经把结局写进
                    // PluginBlueprintStatus（进程级可查询）；这里再兜一层，覆盖「连它自己都没接住」
                    // 的意外异常——同样落进同一个可查询状态，而不是只剩一行日志。
                    runCatching { loadDefaultBlueprint(app) }
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            PluginBlueprintStatus.recordFailure(
                                reason = "loadDefaultBlueprint 抛出未捕获异常",
                                blueprintId = null,
                                failure = failure,
                            )
                            SecureLog.e("YuNianApplication", "loadDefaultBlueprint failed", failure)
                        }
                    // 停用覆盖：必须在蓝图装载**之后**（蓝图决定装载什么，本机停用记录只做减法）。
                    // 此处已在 bgScope（AppDispatchers.io）协程内，suspend 直接顺序调用即可：
                    // 不阻塞主线程、不新建协程作用域 / 线程、不是常驻后台循环，
                    // 且与 loadDefaultBlueprint 同协程顺序执行 ⇒ 不存在「覆盖与装载竞态」。
                    applyDisabledPluginOverlay()
                    PerformanceTrace.markStartupStage("ib_blueprint")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.e("YuNianApplication", "Service initialization failed", e)
                } finally {
                    PerformanceTrace.markStartupStage("service_registry_ready")
                    ServiceRegistry.markInitialized()
                }

                // ── 以下全部在「放行」之后，不再阻挡首帧 ──────────────────────────────────
                // 世界书同步也会创建 AgentRuntime，必须等待技能适配器完成注册。
                bgScope.launch { initWorldbookAgent(app) }

                // T01′：Automation 重排（原 registerServiceProviders 内的
                //   runBlocking { AutomationStore.list() + AutomationScheduler.rescheduleAll }）
                //   移出放行屏障，放到独立协程执行 → 不再占用屏障耗时。
                //   保持原有执行语义（仍要执行 + runCatching + 失败上报）；
                //   此刻 AutomationStore 已在 registerServiceProviders 中注册完毕。
                bgScope.launch {
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            val automations = ServiceRegistry.getOrThrow(AutomationStore::class.java).list()
                            com.yunian.ai.feature.automation.AutomationScheduler.rescheduleAll(app, automations)
                        }
                    }.onFailure {
                        SecureLog.e("YuNianApplication", "Automation rescheduleAll failed", it)
                    }
                }

                runCatching {
                    ServiceRegistry.getOrThrow(UserRepository::class.java).repairUserAvatar(app)
                }.onFailure {
                    SecureLog.e("YuNianApplication", "Repair user avatar failed", it)
                }
                runCatching { AppDatabase.verifyAndRecover(app) }
                    .onFailure { SecureLog.e("YuNianApplication", "Database verification failed", it) }
                // seedDefaultCompanion 内部有 Mutex 幂等保护；此处补异常隔离，
                // 保证后续项不会因它抛异常而被跳过（与拆分前由外层 catch 兜底的行为一致）。
                try {
                    seedDefaultCompanion(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecureLog.e("YuNianApplication", "Seed default companion failed", e)
                }
                runCatching {
                    com.yunian.ai.database.cache.HomeListCache.warm(AppDatabase.getDatabase(app))
                }.onFailure {
                    SecureLog.e("YuNianApplication", "HomeListCache warm failed", it)
                }
                runCatching {
                    val lastOpenedId = LastOpenedCompanionStore.get(app)
                    if (lastOpenedId > 0L) {
                        ServiceRegistry.getOrThrow(ChatRepository::class.java)
                            .hydrateRecent(lastOpenedId, com.yunian.ai.common.ChatConstants.CHAT_PAGE_SIZE)
                    }
                }.onFailure {
                    SecureLog.e("YuNianApplication", "Pre-warm last-opened chat cache failed", it)
                }
                bgScope.launch { warmRecentChatCaches(app) }
                initYandereMode(app)
            }

            bgScope.launch { ContentFilter.initialize(app) }
            bgScope.launch { preloadBackground(app) }
            bgScope.launch { initWeChat(app) }
            bgScope.launch { initSecurityData(app) }
            bgScope.launch { autoBackupDatabase(app) }
            bgScope.launch { initVectorLibrary(app) }
            // 安全基线（合并后事实）：L3 本地语义安全链已随 feature:localmodel 一并退役
            // （N0 / BayesianClassifier / ContentSafetyVerifier / SafetyClassifier 接口全部删除）。
            // 内容判定统一由 ContentFilter 的正则关键词 + 向量库（initVectorLibrary）承担。
            PerformanceTrace.markStartupStage("ib_safety_l3_retired")

            com.yunian.ai.database.cleanup.DataCleanupManager.schedulePeriodicCleanup(app)
            PerformanceTrace.markStartupStage("ib_cleanup_schedule")
            bgScope.launch { com.yunian.ai.database.cleanup.DataCleanupManager.cleanupIfNeeded(app) }
            PerformanceTrace.markStartupStage("initbusiness_done")
        }

        private suspend fun initYandereMode(app: Application) {
            try {
                if (AppSettingsStore(app).getYandereModeEnabled()) {
                    ServiceRegistry.getOrThrow(YandereModeManager::class.java).start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("YuNianApplication", "initYandereMode failed", e)
            }
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun seedDefaultCompanion(app: Application) {
            DefaultCompanionSeeder.seedIfNeeded(app)
        }

        private suspend fun warmRecentChatCaches(app: Application) {
            runCatching {
                val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
                val groupRepository = ServiceRegistry.getOrThrow(GroupMessageRepository::class.java)
                val lastOpenedId = LastOpenedCompanionStore.get(app)
                val summaries = com.yunian.ai.database.cache.HomeListCache.snapshotChatSummaries()
                    .sortedByDescending { it.lastMessageTimestamp }
                val companionIds = buildList {
                    if (lastOpenedId > 0L) add(lastOpenedId)
                    summaries.asSequence()
                        .map { it.sessionId }
                        .filter { it > 0L && it != lastOpenedId }
                        .forEach { add(it) }
                }.distinct().take(2)
                companionIds.forEach { companionId ->
                    runCatching {
                        chatRepository.hydrateRecent(companionId, com.yunian.ai.common.ChatConstants.CHAT_PAGE_SIZE)
                    }.onFailure {
                        SecureLog.e("YuNianApplication", "hydrate chat $companionId failed", it)
                    }
                }

                val groupId = AppDatabase.getDatabase(app)
                    .conversationSummaryDao()
                    .getSummariesByTypeSync("group")
                    .maxByOrNull { it.lastMessageTimestamp }
                    ?.sessionId
                if (groupId != null && groupId > 0L) {
                    runCatching {
                        groupRepository.hydrateRecent(
                            groupId,
                            com.yunian.ai.common.ChatConstants.GROUP_CHAT_MESSAGE_LIMIT
                        )
                    }.onFailure {
                        SecureLog.e("YuNianApplication", "hydrate group $groupId failed", it)
                    }
                }
            }.onFailure {
                SecureLog.e("YuNianApplication", "warmRecentChatCaches failed", it)
            }
        }

        private suspend fun initWeChat(app: Application) {

            NotificationHelper.createNotificationChannel(app)
            WeChatNotificationHelper.createChannel(app)
            SecureLog.d("YuNianApplication", "ROM: ${RomUtils.getRomDisplayName()} ${RomUtils.romVersion}")

            runCatching { PushManager.init(app) }

            runCatching { WeChatChannelKeeper.ensureRunning(app) }
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {

            runCatching { AppDatabase.autoBackupIfNeeded(app.applicationContext) }
            runCatching { AppDatabase.clearOldBackups(app.applicationContext) }
        }

        private fun initVectorLibrary(app: Application) {
            runCatching {
                app.assets.open("safety/violation_vectors.bin").use { it.readBytes() }
                    .let { VectorLibrary.Loader().load(it) }
                    ?.let { ContentFilter.setVectorLibrary(it) }
            }
        }

        /**
         * 阶段 5.9 · 世界书 Agent 化启动接线。
         *
         * 两件事，均幂等：
         * 1. 一次性迁移 —— 本地结构化 `lorebooks` / `lorebook_entries` → `worldbooks`（ST World Info JSON）。
         *    迁移器内部有 `AppMetaStore` 标志位保护，失败不写标志，下次启动自动重试。
         *    源表冻结保留，作为回滚数据源（计划 §5.4）。
         * 2. 全局默认书注入 —— 把当前 active 的全局世界书推给 Rust `AgentRuntime`。
         *    伴侣级世界书由 `ChatGenerationManager` 每回合按 companionId 实时合成后再覆盖（§5.3b），
         *    这里只是保证「未进入任何会话前」运行时也有正确的世界书上下文。
         *
         * 注：`AgentFacade.setWorldbook` 是幂等覆盖写，重复调用无副作用。
         */
        private suspend fun initWorldbookAgent(app: Application) {
            val repo = com.yunian.ai.agent.worldbook.WorldbookRepository(app)
            runCatching {
                when (val r = com.yunian.ai.agent.worldbook.WorldbookMigrator(app).run()) {
                    is com.yunian.ai.agent.worldbook.WorldbookMigrator.Result.Migrated ->
                        SecureLog.i("YuNianApplication", "世界书迁移完成：${r.books} 本 / ${r.entries} 条")
                    com.yunian.ai.agent.worldbook.WorldbookMigrator.Result.Skipped -> Unit
                    com.yunian.ai.agent.worldbook.WorldbookMigrator.Result.Empty ->
                        SecureLog.i("YuNianApplication", "世界书迁移：无存量数据")
                    is com.yunian.ai.agent.worldbook.WorldbookMigrator.Result.Failed ->
                        SecureLog.e("YuNianApplication", "世界书迁移失败：${r.reason}")
                }
            }.onFailure {
                SecureLog.e("YuNianApplication", "Worldbook migration crashed", it)
            }
            runCatching { repo.syncActiveToRuntime() }
                .onFailure { SecureLog.e("YuNianApplication", "Worldbook runtime sync failed", it) }
        }

        /**
         * 阶段 5.10 · Agent 运行时启动接线（Rust 决策层）。
         *
         * 两件事，均幂等，由 provider 注册协程在放行前执行（首次 `dlopen("liblianyu_agent.so")`
         * 是数十毫秒级阻塞操作，禁止上主线程）：
         *
         * 1. `installRequestSigner` —— PARTNER(suflow.cloud) 请求的服务端验签回调。
         *    服务端校验 `X-LianYu-Sig-Version` 头，缺失会被拒；必须在首次 `runTurn` 前就位。
         *    同时必须晚于 SkillStoreAdapter 安装，避免提前缓存默认 SkillSelector。
         * 2. `registerGlobalTools` —— 多 Agent 编排的委派/汇聚工具定义注册进 Rust 全局注册表
         *    （决策在 Rust，执行端在 `AgentToolHost` 的特判分支）。
         */
        private fun initAgentRuntime(app: Application) {
                runCatching { com.yunian.ai.agent.AgentFacade.installRequestSigner(app) }
                    .onFailure { SecureLog.e("YuNianApplication", "installRequestSigner failed", it) }

                runCatching {
                    com.yunian.ai.agent.AgentFacade.registerGlobalTools(
                        app,
                        listOf(
                            com.yunian.ai.agent.uniffi.ToolDefinition(
                                name = "delegate_task",
                                description = "将子任务委派给子 Agent（角色：analyst 分析 / helper 助手）。" +
                                    "返回 delegation_id，稍后用 fetch_delegation_result 查询结果。",
                                parametersJson = "{\"type\":\"object\",\"properties\":{" +
                                    "\"role\":{\"type\":\"string\",\"description\":\"analyst|helper\"}," +
                                    "\"prompt\":{\"type\":\"string\",\"description\":\"任务说明\"}}," +
                                    "\"required\":[\"prompt\"],\"additionalProperties\":false}",
                                category = com.yunian.ai.agent.uniffi.ToolCategory.GENERAL,
                                toolsets = listOf("agent"),
                                available = true,
                            ),
                            com.yunian.ai.agent.uniffi.ToolDefinition(
                                name = "fetch_delegation_result",
                                description = "查询委派任务的执行结果（delegate_task 返回的 delegation_id）。",
                                parametersJson = "{\"type\":\"object\",\"properties\":{" +
                                    "\"delegation_id\":{\"type\":\"integer\"}}," +
                                    "\"required\":[\"delegation_id\"],\"additionalProperties\":false}",
                                category = com.yunian.ai.agent.uniffi.ToolCategory.GENERAL,
                                toolsets = listOf("agent"),
                                available = true,
                            ),
                        ),
                    )
                }.onFailure { SecureLog.e("YuNianApplication", "registerGlobalTools failed", it) }
        }

        private fun registerServiceProviders(app: Application) {
            val appSettings = AppSettingsStore(app)
            ServiceRegistry.registerSingleton(BuiltinCloudAccessPolicy::class.java) {
                object : BuiltinCloudAccessPolicy {
                    override fun isBuiltinCloudAccessAllowed(): Boolean {

                        SecurityState.updateRiskLevel()
                        val snap = SecurityState.snapshot()
                        val locked = NativeBridge.zeroTrustIsLocked()
                        return snap.isTrustedForSensitiveOps && locked == 0
                    }

                    override fun denialReason(): String? {
                        return SecurityState.snapshot().reason ?: "zero trust verification incomplete"
                    }
                }
            }

            val database = AppDatabase.getDatabase(app)
            ServiceRegistry.registerSingleton(CompanionRepository::class.java) {
                CompanionRepository(database.companionDao())
            }
            ServiceRegistry.registerSingleton(ApiConfigRepository::class.java) {
                ApiConfigRepository(database.apiConfigDao(), database.apiProviderPresetDao())
            }
            ServiceRegistry.registerSingleton(ChatRepository::class.java) {
                ChatRepository(database.messageDao(), database.conversationSummaryDao(), database)
            }
            ServiceRegistry.registerSingleton(GroupMessageRepository::class.java) {
                GroupMessageRepository(database.messageDao(), database.conversationSummaryDao(), database)
            }
            ServiceRegistry.registerSingleton(MessageWriteCoordinator::class.java) {
                MessageWriteCoordinator(
                    ServiceRegistry.getOrThrow(ChatRepository::class.java),
                    ServiceRegistry.getOrThrow(GroupMessageRepository::class.java),
                    bgScope
                )
            }

            TimelinePayloadCodecRegistry.registerBuiltins()
            ServiceRegistry.registerSingleton(TimelineStore::class.java) {
                RoomTimelineStore(database.messageDao(), database)
            }

            ServiceRegistry.registerSingleton(EmbeddingProvider::class.java) {
                com.yunian.ai.network.EmbeddingService(app)
            }

            ServiceRegistry.registerSingleton(SummaryProvider::class.java) {
                com.yunian.ai.network.SummaryService(app)
            }

            ServiceRegistry.registerSingleton(DiaryProvider::class.java) {
                com.yunian.ai.network.DiaryService(app)
            }

            ServiceRegistry.registerSingleton(UnifiedMemoryRepository::class.java) {
                UnifiedMemoryRepository(
                    AppDatabase.getDatabase(app).unifiedMemoryDao(),
                    DeviceIdProvider.getDeviceId(app),
                    ServiceRegistry.getOrThrow(EmbeddingProvider::class.java),
                    ServiceRegistry.getOrThrow(SummaryProvider::class.java)
                )
            }
            ServiceRegistry.registerSingleton(UserRepository::class.java) {
                UserRepository(app)
            }

            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {

                com.yunian.ai.feature.profile.UserProfileProviderImpl(
                    app,
                    ServiceRegistry.getOrThrow(UserRepository::class.java)
                )
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.yunian.ai.feature.companion.CompanionProviderImpl(app)
            }

            ServiceRegistry.registerSingleton(MemoryProvider::class.java) {
                com.yunian.ai.feature.memory.engine.UnifiedMemoryProvider(
                    app,
                    DeviceIdProvider.getDeviceId(app),
                    ServiceRegistry.getOrThrow(EmbeddingProvider::class.java),
                    ServiceRegistry.getOrThrow(SummaryProvider::class.java)
                )
            }

            ServiceRegistry.registerSingleton(PlaceholderProvider::class.java) {
                com.yunian.ai.common.PlaceholderResolver(app)
            }

            ServiceRegistry.registerSingleton(LorebookProvider::class.java) {
                // 阶段 5g：数据源已切到 master 的 `worldbooks` 表，构造需 Context
                com.yunian.ai.feature.worldbook.repository.WorldbookRepository(app)
            }

            ServiceRegistry.registerSingleton(McpManager::class.java) {
                com.yunian.ai.feature.mcp.McpManagerImpl(appSettings)
            }

            ServiceRegistry.registerSingleton(SkillManager::class.java) {
                com.yunian.ai.feature.skills.repository.SkillManagerImpl(app)
            }
            ServiceRegistry.registerSingleton(AiServiceProvider::class.java) {
                AiService(app)
            }

            // AI 生图：独立 OkHttpClient，与聊天/流式链路隔离（生图耗时长，不共享超时）
            ServiceRegistry.registerSingleton(ImageGenerationProvider::class.java) {
                com.yunian.ai.network.ImageGenerationService(app)
            }

            // AI 生图编排：feature:chat 实现，微信/QQ 桥接链路通过它复用同一套判定逻辑
            ServiceRegistry.registerSingleton(ImageGenService::class.java) {
                com.yunian.ai.feature.chat.imagegen.ImageGenServiceImpl(app)
            }

            ServiceRegistry.registerSingleton(WeChatDialoguePort::class.java) {
                WeChatDialoguePortImpl(app)
            }

            ServiceRegistry.registerSingleton(WeChatIdentityMapPort::class.java) {
                WeChatIdentityMapPortImpl(app)
            }

            ServiceRegistry.registerSingleton(WeChatOutboundPort::class.java) {
                WeChatOutboundPortImpl(app)
            }
            ServiceRegistry.registerSingleton(YandereModeManager::class.java) {
                YandereModeManager(app)
            }

            // 统一 AI 对话中间层（core:agent 实现）：
            // 微信 / QQ 桥接层只做消息收发，AI 回合 / 安全 / 落库 / 记忆全部收敛到它。
            // 先建实例、后两处复用：① ServiceRegistry 的懒加载单例工厂 ② 插件宿主预置的
            // PluginServices.AGENT。两处**必须是同一个实例**（否则通道插件看到的 Agent 与
            // 桥接层用的不是同一个），因此这里提前构造并用局部变量传递。
            // 构造本身只存 appContext（其余依赖都是 get()/by lazy 惰性解析），提前构造无副作用。
            val dialogueCoordinator = com.yunian.ai.agent.AgentDialogueCoordinator(app)
            ServiceRegistry.registerSingleton(DialogueCoordinator::class.java) { dialogueCoordinator }

            ServiceRegistry.registerSingleton(AppMetaStore::class.java) {
                AppMetaStore(AppDatabase.getDatabase(app).appMetaDao())
            }

            // 工具授权（伴侣 × 工具，**不含通道维度**）：显式允许的工具不再触发确认门，
            // 显式禁止的工具即使自身没声明需要确认也会被强制走确认门。
            // 装配期折叠在 AgentFacade.toolDefinitionsFor（core:agent），
            // 未绑定 / 读取失败时 fail-closed —— 所有工具维持既有类别，行为与引入授权前一致。
            // 持久化走 AppMetaStore(KV 单键)，不新增 Room 表 / 字段（schema 冻结红线）。
            ServiceRegistry.registerSingleton(CapabilityGrantStore::class.java) {
                com.yunian.ai.agent.CapabilityGrantStoreImpl(
                    ServiceRegistry.getOrThrow(AppMetaStore::class.java)
                )
            }

            // 插件启停状态（**跨重启保留**）：用户停用的插件必须在重启后仍然是停用的。
            // 蓝图是只读资产（loadDefaultBlueprint 只读不写），所以停用状态另存一份 KV，
            // 语义是**减法**——只记「被显式停用」的 id，不在集合里的一律视为启用。
            // 持久化走 AppMetaStore(KV 单键)，不新增 Room 表 / 字段（schema 冻结红线）。
            // 读失败由实现方 fail-safe 成空集（= 全部启用），见 PluginEnablementStoreImpl。
            ServiceRegistry.registerSingleton(PluginEnablementStore::class.java) {
                PluginEnablementStoreImpl(
                    ServiceRegistry.getOrThrow(AppMetaStore::class.java)
                )
            }

            // ── Cordis 双层插件模板：PluginHost（代码插件 builtin） ──
            // 通道注册中心（「通道即插件」的查询面）：先构造注册中心，再把它作为框架服务
            // 预置给宿主（插件才能 ctx.inject(CHANNELS)），最后 bindHost 完成双向接线。
            // 注册中心的内容来源是宿主 pluginsOf(PluginKind.ADAPTER)，不是任何旁路全局表。
            val channelRegistry = com.yunian.ai.agent.channel.ChannelRegistryImpl()
            // 框架服务预置（对齐 Cordis 宿主 ctx.<service>）：appContext / tools / channels / agent
            // agent：上面已注册的同一个 DialogueCoordinator 实例——声明 requires = [AGENT] 的
            // 通道插件因此可以 ctx.inject<DialogueCoordinator>(AGENT) 拿到生产实例；
            // 未预置时装载 fail-closed（依赖校验拒绝），与其余服务键语义一致。
            val pluginHost = com.yunian.ai.agent.plugin.PluginHostImpl(
                mapOf(
                    com.yunian.ai.domain.plugin.PluginServices.APP_CONTEXT to app,
                    com.yunian.ai.domain.plugin.PluginServices.TOOLS to ToolRegistry,
                    com.yunian.ai.domain.plugin.PluginServices.CHANNELS to channelRegistry,
                    com.yunian.ai.domain.plugin.PluginServices.AGENT to dialogueCoordinator,
                )
            )
            channelRegistry.bindHost(pluginHost)
            // 注册首批代码插件：技能（内置聊天协议）、表情包偏好、
            // 通道（QQ 机器人，kind = ADAPTER）
            pluginHost.register(com.yunian.ai.agent.plugin.BuiltinChatSkillPlugin())
            pluginHost.register(com.yunian.ai.agent.plugin.StickerPreferencePlugin())
            // 通道插件（ADAPTER）：只包装既有 QQBotMessageRepository，装配期不启动任何会话。
            // ⚠️ 事实更正：adapterFactory 在 QQBotChannelPlugin.setup 里会被**立即**调用，
            // 所以下面这个 lambda 在**装载期**就执行、当场构造仓库（连带 TokenStore / ApiClient）；
            // 此前注释称「惰性求值、start() 才碰 QQBotServiceLocator」是错的。
            // 仍成立的结论：构造仓库不发起连接、不启动心跳 / 重连、不收发消息
            //（构造体无 init 块，connect() 才是建连入口），故 QQ 的连接 / 心跳 / 重连 / 收发时序不受影响。
            // 主动发送（P4-1）：**不**扩展 ChannelOutbound、**不**走 ChannelSession.send()
            // （§17.6 裁定：send(outbound) 是传输契约、把通道当被调用方，与投影模型形状不符）。
            // 请求/应答语义走插件事件 ChannelOutboundEvents.REQUEST，本插件是**订阅方**；
            // 发起方是 core:agent 的 message.send 插件（它不认识任何具体通道）。
            pluginHost.register(
                com.yunian.ai.feature.qqbot.channel.QQBotChannelPlugin(
                    adapterFactory = {
                        com.yunian.ai.feature.qqbot.channel.QQBotChannelAdapter.ofRepository(
                            com.yunian.ai.feature.qqbot.service.QQBotServiceLocator
                                .messageRepository(app)
                        )
                    },
                    // 与适配器操作**同一个**仓库实例（同一个连接状态、同一个 msg_seq 计数器）：
                    // 另起一个仓库会造出第二个连接状态来源。
                    proactiveSenderSupplier = {
                        com.yunian.ai.feature.qqbot.channel.QQBotProactiveSender.fromRepository(
                            com.yunian.ai.feature.qqbot.service.QQBotServiceLocator
                                .messageRepository(app)
                        )
                    },
                )
            )
            // 通道插件（ADAPTER）：微信（P4-2，与 QQ 侧对称）。
            // 只注册适配器 + 订阅出站事件：setup **不**启动轮询、**不**发心跳、**不**碰重连，
            // 微信的 FGS / WakeLock / watchdog / 重启 Worker 保活时序一行未动。
            // 主动发送（不扩展 ChannelOutbound、不走 ChannelSession.send，见 §17.6）：
            // 请求/应答经插件事件 ChannelOutboundEvents.REQUEST，本插件是**订阅方**。
            // W1（修正版，用户裁定）：ilink 协议**只允许**给绑定的那个微信账号发消息——
            // 「发给别人」和「群发」在协议上根本不存在，所以 target 为空**没有歧义**，
            // 就是发给绑定的微信用户（用户说「你给我微信发条消息」时模型会省略 target，这是主路径）。
            // 旧实现把「target 为空」当成「需要显式收件人」直接失败，让主路径**必然失败**——已修正。
            // target 非空但**不是**绑定用户 → 如实失败：绝不静默忽略用户的显式指定，也绝不改写后照发。
            pluginHost.register(
                com.yunian.ai.feature.wechat.channel.WeChatChannelPlugin(
                    senderSupplier = {
                        com.yunian.ai.feature.wechat.channel.weChatChannelSender(app)
                    },
                )
            )
            // 消息发送**发起方**插件（kind = TOOL）：注册 send_channel_message 工具，
            // 派发 ChannelOutboundEvents.REQUEST 由通道插件认领。
            // 它**不认识任何具体通道**，因此通道插件卸载后工具仍在（派发得到「该通道未启用」），
            // 而不是随通道一起消失——这正是「可自由启用/停用通道」的正确语义。
            pluginHost.register(com.yunian.ai.agent.plugin.MessageSendPlugin())
            ServiceRegistry.registerSingleton(com.yunian.ai.domain.plugin.PluginHost::class.java) { pluginHost }
            // 通道注册中心同时作为跨模块可查询服务（feature 模块按 ChannelRegistry 契约取用）。
            ServiceRegistry.registerSingleton(com.yunian.ai.domain.channel.ChannelRegistry::class.java) {
                channelRegistry
            }
            // 插件装载由默认蓝图统一驱动（assets/blueprints/default.json，initBusiness 内执行）；
            // 因此此处不再直接调用各工具集的 registerAll（一律改由蓝图插件装载）。

            com.yunian.ai.feature.memory.MemoryRecallTools.registerAll(
                ServiceRegistry.getOrThrow(MemoryProvider::class.java)
            )

            com.yunian.ai.feature.chat.tools.SearchTools.registerAll(appSettings)

            ToolRegistry.register(com.yunian.ai.agent.tools.UserProfileTool())

            bgScope.launch {
                com.yunian.ai.feature.mcp.McpToolRegistrar(
                    ServiceRegistry.getOrThrow(McpManager::class.java)
                ).syncTools()
            }

            // 瑞幸咖啡功能已下线（2026-10-03）：清除历史版本遗留在应用私有目录的 DataStore
            // 凭据文件（luckin_coffee_prefs 曾存放 MCP Token）。幂等——文件不存在即静默返回；
            // best-effort——失败只记日志，绝不阻断启动；后台执行——不占主线程，
            // 独立 launch 而非并入上面的 syncTools，避免给既有启动时序增加耦合。
            bgScope.launch {
                runCatching {
                    java.io.File(app.filesDir, "datastore/luckin_coffee_prefs.preferences_pb")
                        .takeIf { it.exists() }
                        ?.delete()
                }.onFailure {
                    SecureLog.e("YuNianApplication", "清理瑞幸咖啡残留凭据失败", it)
                }
            }

            // ── Q6 技能体系收敛：把本地技能资产桥接给 Rust SkillSelector ──
            // 必须在首个 Agent 回合（首次 SkillSelector 创建）之前注入：
            // 之后 Rust 才能按「L1 目录 / L2 load_skill」渐进式披露本地 assets/skills
            // 与 filesDir/external_skills（含技能市场新装技能），从而退役 use_skill（Q6 已完成）。
            runCatching {
                com.yunian.ai.agent.AgentFacade.installSkillStoreProvider(
                    com.yunian.ai.feature.skills.repository.SkillStoreAdapter(
                        ServiceRegistry.getOrThrow(SkillManager::class.java)
                    )
                )
            }.onFailure { SecureLog.e("YuNianApplication", "installSkillStoreProvider failed", it) }

            com.yunian.ai.feature.skills.tools.registerSkillTools(
                ServiceRegistry.getOrThrow(SkillManager::class.java)
            )
            // 技能市场：AI 自主搜技能并安装（SkillHub 国内直连优先，GitHub/jsDelivr 兜底）
            com.yunian.ai.feature.skills.tools.registerSkillMarketTools(
                app,
                ServiceRegistry.getOrThrow(SkillManager::class.java)
            )
            // 设备接管工具（打开应用/网页、剪贴板、闹钟、通知、电量、时间）
            com.yunian.ai.feature.skills.tools.registerDeviceTools(app)
            // Shizuku 特权通道状态检测
            com.yunian.ai.feature.skills.tools.registerShizukuTools(app)
            com.yunian.ai.feature.chat.tools.ConversationTools.registerAll(
                AppDatabase.getDatabase(app)
            )

            ServiceRegistry.registerSingleton(AutomationStore::class.java) {
                com.yunian.ai.feature.automation.data.AutomationStore(app)
            }
            // 5 个自动化工具改由 automation.core 插件装配（默认蓝图装载），
            // 与 ui.assists 同一范式：逐工具注册 effect 注销副作用。
            // ⚠️ 调度层（AutomationScheduler / AutomationFireWorker）不在插件范围内。
            pluginHost.register(
                com.yunian.ai.feature.automation.AutomationPlugin(
                    ServiceRegistry.getOrThrow(AutomationStore::class.java),
                    app,
                )
            )
            // AI 控制手机改由 ui.assists 插件装配（默认蓝图装载，与本区块其它插件同一范式）：
            // 9 个无障碍工具随之进/出注册表，用户可在插件设置里关掉「无障碍自动化」。
            // 这里注入的是**真机实现** AssistsAccessibilityBridge（纯 Kotlin 接缝，见
            // feature/skills/.../accessibility/AccessibilityBridge.kt）；插件本身不认识 assists。
            // 时序：本方法由 initBusiness 在 loadDefaultBlueprint **之前**调用（L227 → L232），
            // 因此这里同步注册即可保证「蓝图装载时该插件已在宿主中」（与上下位置无关）。
            pluginHost.register(
                com.yunian.ai.feature.skills.plugin.AssistsUiPlugin(
                    com.yunian.ai.feature.skills.accessibility.AssistsAccessibilityBridge,
                )
            )

            ServiceRegistry.registerSingleton(com.yunian.ai.domain.AutomationTickProvider::class.java) {
                com.yunian.ai.feature.automation.AutomationTickProviderImpl(app)
            }

            // T01′：原位于此处的
            //   `runBlocking { AutomationStore.list() + AutomationScheduler.rescheduleAll(app, ...) }`
            // 已移出「放行屏障」——改到 initBusiness 的 bgScope 中、ServiceRegistry.markInitialized()
            // 之后的独立协程执行（保持「仍执行 + runCatching + 失败上报」语义，仅不再占用首帧前的屏障耗时）。
        }

        /**
         * Cordis 蓝图装载：读 `assets/blueprints/default.json` → 解析 → PluginHost.loadBlueprint。
         *
         * 语义与 cordis.yml 一致：plugins 为基准列表（缺省启用），patches 覆盖配置，
         * inserts 追加新插件；装载顺序即列表顺序（sticker 引擎依赖此顺序）。
         * 幂等：PluginHost 内部对已装载插件做 config 比对，配置未变则跳过。
         *
         * ## 失败语义（D4 / B2：**不再静默**）
         * 资产读取、解析及宿主缺失在此记录；意外异常交给调用处记录，取消必须向上传播。
         * 正常返回不等于所有插件均成功，结局查询 [PluginBlueprintStatus]：
         * - Applied 表示遍历完成，单插件失败仍在 skipped 的 failed: 项中，后续项继续加载；
         * - 资产读不到 / JSON 非法 / 宿主未注册 / 宿主返回 Failed → recordFailure；
         * - 宿主抛异常可能已产生部分装载副作用，但没有返回进度列表。
         * Failed.loaded 默认空表示未提供已知装载项，不证明宿主为空；当前存活集合查询 loadedIds()。
         * hasFailed() 只判断整次失败，partial failure 应检查 Applied.skipped。
         */
        private fun loadDefaultBlueprint(app: Application) {
            // ── 失败可见性（D4 / B2）──────────────────────────────────────────────
            // 常规失败就地记录；取消向上传播，其他意外交给调用处兜底记录。
            // 该记录描述本次尝试，不替代宿主当前 loadedIds() 快照。
            val json = try {
                app.assets.open(com.yunian.ai.agent.plugin.PluginBlueprintStatus.DEFAULT_BLUEPRINT_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                PluginBlueprintStatus.recordFailure(
                    reason = "蓝图资产读取失败（${com.yunian.ai.agent.plugin.PluginBlueprintStatus.DEFAULT_BLUEPRINT_ASSET}）",
                    failure = failure,
                )
                SecureLog.e("YuNianApplication", "Blueprint asset read failed", failure)
                return
            }

            val blueprint = try {
                com.yunian.ai.agent.plugin.PluginBlueprintParser.parse(json)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                PluginBlueprintStatus.recordFailure(
                    reason = "蓝图 JSON 解析失败",
                    failure = failure,
                )
                SecureLog.e("YuNianApplication", "Blueprint JSON parse failed", failure)
                return
            }

            val host = ServiceRegistry.get(com.yunian.ai.domain.plugin.PluginHost::class.java)
            if (host == null) {
                // 宿主未注册，本次未执行蓝图；不推断其他加载路径的状态。
                PluginBlueprintStatus.recordFailure(
                    reason = "PluginHost 未注册，蓝图未装载",
                    blueprintId = blueprint.id,
                )
                SecureLog.w("YuNianApplication", "PluginHost not registered, skip blueprint")
                return
            }

            when (val result = host.loadBlueprint(blueprint)) {
                is com.yunian.ai.domain.plugin.BlueprintLoadResult.Applied -> {
                    PluginBlueprintStatus.recordApplied(
                        blueprintId = blueprint.id,
                        loaded = result.loaded,
                        skipped = result.skipped,
                    )
                    SecureLog.i(
                        "YuNianApplication",
                        "Blueprint ${blueprint.id} applied: loaded=${result.loaded} skipped=${result.skipped}",
                    )
                }
                is com.yunian.ai.domain.plugin.BlueprintLoadResult.Failed -> {
                    PluginBlueprintStatus.recordFailure(
                        reason = "宿主拒绝装载蓝图: ${result.reason}",
                        blueprintId = blueprint.id,
                    )
                    SecureLog.e("YuNianApplication", "Blueprint ${blueprint.id} failed: ${result.reason}")
                }
            }
            // 核心插件底座可见性：记录 cordis-rs 宿主快照（回合统计 / 插件健康）
            runCatching {
                SecureLog.i("YuNianApplication", "Core plugins: ${com.yunian.ai.agent.AgentFacade.corePluginSnapshot(app)}")
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                SecureLog.w("YuNianApplication", "corePluginSnapshot failed: ${failure.message}")
            }
        }

        /**
         * 插件停用覆盖：把「用户在本机设置页显式停用的插件」从已装载集合里减掉。
         *
         * ## 为什么是「装载之后追加一步」而不是改蓝图
         * 蓝图（`assets/blueprints/default.json`）是**只读资产**——本方法不碰
         * [loadDefaultBlueprint] 的读取路径，只做减法：对 `disabledIds()` 里的每个 id
         * 调一次 `PluginHost.unload`。于是「蓝图 = 出厂默认装载集合」与
         * 「KV = 用户本机的停用集合」两件事各自单一职责。
         *
         * ## 协程 / 线程（不阻塞主线程）
         * 调用点是 `initBusiness` 里 `bgScope.launch { ... }` 的**放行屏障段**，运行在
         * [AppDispatchers.io] 上，因此这里可以直接调 suspend 的 `disabledIds()`：
         * - `initBusiness` 本身在主线程只做 `bgScope.launch` 就返回，**主线程不等它**；
         * - **不新建协程作用域 / 线程**，也**不是常驻后台循环**——一次读 + N 次 unload，跑完即止；
         * - 与 [loadDefaultBlueprint] 在**同一个协程里顺序执行**，所以不存在「覆盖与装载竞态」：
         *   覆盖一定看得见蓝图装载完的结果（这是刻意选的，比另起协程更确定）；
         * - 代价明确记录：屏障因此多一次 `app_meta` 主键查询 + N 次「未装载时直接返回 false」的
         *   unload（`PluginHostImpl.unload` 对未装载 id 不做任何事）。
         *
         * ## 失败语义（fail-safe，与契约同向）
         * 存储未注册 / 读失败 / 单个卸载抛异常，一律降级为「什么都不做」
         * （= 保持蓝图原样 = 全部启用），**绝不反过来把插件全停掉**，
         * 也不会让放行屏障失败。只有协程取消照常向上传播。
         */
        private suspend fun applyDisabledPluginOverlay() {
            val store = runCatching {
                ServiceRegistry.get(PluginEnablementStore::class.java)
            }.getOrNull()
            if (store == null) {
                SecureLog.w(
                    "YuNianApplication",
                    "PluginEnablementStore not registered, skip disablement overlay",
                )
                return
            }

            val disabled = try {
                store.disabledIds()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // 契约要求实现方自己 fail-safe；这里再兜一层：任何实现抛异常都只当「没有停用」。
                SecureLog.e(
                    "YuNianApplication",
                    "disabledIds failed, treating as nothing disabled",
                    failure,
                )
                return
            }
            if (disabled.isEmpty()) return

            val host = runCatching {
                ServiceRegistry.get(com.yunian.ai.domain.plugin.PluginHost::class.java)
            }.getOrNull()
            if (host == null) {
                SecureLog.w("YuNianApplication", "PluginHost not registered, skip disablement overlay")
                return
            }

            var unloaded = 0
            for (id in disabled) {
                val ok = try {
                    host.unload(id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // 单个插件的卸载失败不得影响其余 id，更不得让放行屏障失败。
                    SecureLog.e("YuNianApplication", "unload failed for disabled plugin: $id", failure)
                    false
                }
                if (ok) unloaded++
            }
            SecureLog.i(
                "YuNianApplication",
                "Plugin disablement overlay applied: disabled=${disabled.size} unloaded=$unloaded",
            )
        }

        private fun clearUpdateIgnore(app: Application) {
            app.getSharedPreferences("update_config", android.content.Context.MODE_PRIVATE)
                .edit().remove("ignored_version").apply()
        }

        private fun applyStoredLanguage(app: Application) {
            Locale.setDefault(
                when (app.getSharedPreferences("language_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("language", "zh-CN") ?: "zh-CN"
                ) {
                    "zh-TW" -> Locale.TRADITIONAL_CHINESE
                    "en" -> Locale.ENGLISH
                    "ja" -> Locale.JAPANESE
                    "ko" -> Locale.KOREAN
                    else -> Locale.SIMPLIFIED_CHINESE
                }
            )
        }
    }
}
