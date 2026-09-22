package com.yunian.ai.feature.chat.imagegen

import android.content.Context
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.GeneratedImageRecord
import com.yunian.ai.domain.imagegen.ImageGenService
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatMediaRef
import com.yunian.ai.domain.wechat.WeChatProactiveSync
import com.yunian.ai.feature.chat.data.ChatDetailSettingsStore
import com.yunian.ai.feature.chat.ui.viewmodel.ImageGenCompanionOverride
import com.yunian.ai.feature.chat.ui.viewmodel.ImageGenCoordinator
import com.yunian.ai.feature.chat.ui.viewmodel.ImageGenDeps
import com.yunian.ai.feature.chat.ui.viewmodel.ImageGenGenerationStatus
import com.yunian.ai.feature.chat.ui.viewmodel.ImageGenGlobalConfig
import com.yunian.ai.feature.chat.ui.viewmodel.ImageGenTriggerLogic
import java.io.File

/**
 * [ImageGenService] 的唯一实现（由 `:app` 注册进 `ServiceRegistry`）。
 *
 * 存在的意义：微信 / QQ 桥接链路（feature:wechat / feature:qqbot）不能依赖 feature:chat，
 * 但又必须复用完全相同的判定逻辑（关键词 → 概率 → 冷却）。这里把 feature:chat 的
 * [ImageGenCoordinator] 包一层暴露出去，桥接侧零复制。
 *
 * 任何失败都被吞掉并返回空列表：生图失败绝不允许影响聊天主流程。
 */
class ImageGenServiceImpl(appContext: Context) : ImageGenService {

    private val appSettingsStore = AppSettingsStore(appContext.applicationContext)
    private val chatDetailSettingsStore = ChatDetailSettingsStore(appContext.applicationContext)
    private val apiConfigRepository: ApiConfigRepository by lazy {
        ServiceRegistry.getOrThrow(ApiConfigRepository::class.java)
    }
    private val messageWriter: MessageWriteCoordinator by lazy {
        ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
    }

    override suspend fun generateForReply(
        companionId: Long,
        userText: String,
        aiText: String,
        mirrorToWeChat: Boolean,
        onMessage: ((String, Boolean) -> Unit)?
    ): List<GeneratedImageRecord> {
        val enabled = runCatching { appSettingsStore.getImageGenEnabled() }.getOrDefault(false)
        if (!enabled) {
            SecureLog.i(TAG, "skip: image gen disabled companionId=$companionId")
            return emptyList()
        }

        val records = mutableListOf<GeneratedImageRecord>()
        return try {
            val detailSettings = runCatching { chatDetailSettingsStore.getSettings(companionId) }
                .getOrNull()
            val coordinator = ImageGenCoordinator(
                companionId = companionId,
                deps = ImageGenDeps(
                    provider = ServiceRegistry.getOrThrow(ImageGenerationProvider::class.java),
                    loadGlobalConfig = { loadGlobalConfig() },
                    loadLastGenAt = { appSettingsStore.getImageGenLastAt(companionId) },
                    saveLastGenAt = { appSettingsStore.setImageGenLastAt(companionId, it) },
                    resolveMainConnection = {
                        apiConfigRepository.getActiveEnabledConfig()?.let { it.baseUrl to it.apiKey }
                    },
                    writeMessage = { message ->
                        writeImageMessage(
                            message = message,
                            records = records,
                            mirrorToWeChat = mirrorToWeChat,
                        )
                    },
                    emitMessage = { text, isError -> onMessage?.invoke(text, isError) },
                    // 生图真正开始时点亮/结束时熄灭等待动画（进程级状态，UI 不依赖本实例存活）
                    onGenerationStart = { ImageGenGenerationStatus.markStarted(companionId) },
                    onGenerationFinish = { ImageGenGenerationStatus.markFinished(companionId) },
                ),
                override = ImageGenCompanionOverride(
                    enabled = detailSettings?.imageGenOverrideEnabled ?: false,
                    probability = detailSettings?.imageGenTriggerProbability ?: 0,
                    keywords = AppSettingsStore.ImageGenDefaults
                        .parseKeywords(detailSettings?.imageGenKeywords.orEmpty()),
                ),
            )
            val decision = coordinator.maybeTriggerImage(userText = userText, aiText = aiText)
            // 用 Log.i 级别：正式包 Log.d 被 proguard 剥离。
            // 无论触发与否都记录原因，否则「为什么没出图」无法从日志判断。
            if (decision.triggered) {
                SecureLog.i(
                    TAG,
                    "image gen ok: companionId=$companionId reason=${decision.reason} " +
                        "written=${records.size} prompt=${decision.prompt?.take(120)}",
                )
            } else {
                SecureLog.i(TAG, "image gen skipped: companionId=$companionId ${decision.reason}")
            }
            records.toList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e(TAG, "image gen failed: companionId=$companionId", e)
            emptyList()
        } finally {
            // 兜底：任何异常路径都不能让等待动画一直转下去
            ImageGenGenerationStatus.markFinished(companionId)
        }
    }

    /** 读取全局生图配置；读取失败时返回"关闭"配置，由 [ImageGenTriggerLogic] 判为未就绪。 */
    private suspend fun loadGlobalConfig(): ImageGenGlobalConfig = ImageGenGlobalConfig(
        enabled = appSettingsStore.getImageGenEnabled(),
        connectionMode = appSettingsStore.getImageGenProvider(),
        baseUrl = appSettingsStore.getImageGenBaseUrl(),
        apiKey = appSettingsStore.getImageGenApiKey(),
        model = appSettingsStore.getImageGenModel(),
        size = appSettingsStore.getImageGenSize(),
        count = appSettingsStore.getImageGenCount(),
        probability = appSettingsStore.getImageGenTriggerProbability(),
        keywords = appSettingsStore.getImageGenKeywords(),
        cooldownMinutes = appSettingsStore.getImageGenCooldownMinutes(),
        promptTemplate = appSettingsStore.getImageGenPromptTemplate(),
    )

    /**
     * 落库图片消息，并在需要时把它镜像到微信。
     *
     * 镜像必须带 [WeChatMediaRef]：早期只传文字时，出站侧看到的是保留标签 `[图片]`，
     * 会被当成表情包名去查表（查不到 → 整条丢弃），于是微信端永远收不到生图（BUG-3）。
     */
    private suspend fun writeImageMessage(
        message: ChatMessage,
        records: MutableList<GeneratedImageRecord>,
        mirrorToWeChat: Boolean,
    ): Long {
        val messageId = messageWriter.enqueueChat(message)
        if (messageId <= 0L) {
            SecureLog.w(TAG, "image message persist failed companionId=${message.companionId}")
            return messageId
        }
        records.add(
            GeneratedImageRecord(
                messageId = messageId,
                filePath = message.linkString,
                prompt = message.searchContent,
            )
        )
        if (mirrorToWeChat && message.type == MessageType.IMAGE && message.linkString.isNotBlank()) {
            WeChatProactiveSync.enqueue(
                companionId = message.companionId,
                messageId = messageId,
                media = buildMediaRef(message),
            )
        }
        return messageId
    }

    private fun buildMediaRef(message: ChatMessage): WeChatMediaRef? {
        val path = message.linkString
        if (path.isBlank()) return null
        val file = runCatching { File(path) }.getOrNull()
        if (file == null || !file.exists() || !file.isFile) {
            SecureLog.w(TAG, "image file missing, skip mirror path=${path.take(120)}")
            return null
        }
        return WeChatMediaRef(
            kind = WeChatContentKind.IMAGE,
            localPath = file.absolutePath,
            fileName = file.name,
            // 画面描述是内部信息，不发到微信聊天里
            description = null,
            byteSize = file.length(),
        )
    }

    private companion object {
        private const val TAG = "ImageGenService"
    }
}
