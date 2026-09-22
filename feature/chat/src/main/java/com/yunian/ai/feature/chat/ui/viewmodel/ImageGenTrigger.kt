package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * AI 生图触发逻辑。
 *
 * 本文件刻意与 Android 框架解耦：核心判定全部是纯函数（[ImageGenTriggerLogic]），
 * 由 JVM 单测覆盖；[ImageGenCoordinator] 只负责编排（读配置 → 判定 → 生图 → 落库），
 * 所有副作用通过注入的 lambda 完成，因此同样可测。
 */

// ------------------------------------------------------------------ 进行中状态

/**
 * 「哪些会话正在生图」——进程级单例。
 *
 * 不能把这个状态挂在 [ChatGenerationManager] 上：那个实例是引用计数 + 可回收重建的，
 * 而生图协程跑在进程级的 ApplicationScope 上。一旦 manager 被重建，
 * UI 观察到的就是新实例的 StateFlow，于是出现「图片出来了但等待动画没显示」。
 */
object ImageGenGenerationStatus {

    private val _activeCompanionIds = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>>(emptySet())
    val activeCompanionIds: kotlinx.coroutines.flow.StateFlow<Set<Long>> = _activeCompanionIds

    fun markStarted(companionId: Long) {
        _activeCompanionIds.value = _activeCompanionIds.value + companionId
    }

    fun markFinished(companionId: Long) {
        _activeCompanionIds.value = _activeCompanionIds.value - companionId
    }
}

// ------------------------------------------------------------------ 配置模型

/** 全局生图配置（来自 AppSettingsStore）。 */
data class ImageGenGlobalConfig(
    val enabled: Boolean = false,
    val connectionMode: String = CONNECTION_MODE_AUTO,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val size: String = ImageGenerationProvider.DEFAULT_IMAGE_SIZE,
    val count: Int = 1,
    val probability: Int = 0,
    val keywords: List<String> = emptyList(),
    val cooldownMinutes: Int = 3,
    val promptTemplate: String = "{content}"
) {
    companion object {
        const val CONNECTION_MODE_AUTO = "auto"
    }
}

/** 单聊覆盖（来自 CompanionChatDetailSettings），默认不覆盖全局。 */
data class ImageGenCompanionOverride(
    val enabled: Boolean = false,
    val probability: Int = 0,
    val keywords: List<String> = emptyList()
)

/** 解析后的生效配置。 */
data class ImageGenEffectiveConfig(
    val ready: Boolean,
    val probability: Int,
    val keywords: List<String>,
    val cooldownMs: Long
)

enum class ImageGenTriggerReason {
    /** 关键词命中，无视概率直接触发 */
    KEYWORD,

    /** 概率抽签命中 */
    PROBABILITY,

    /** 总开关关闭 */
    DISABLED,

    /** 连接信息或模型未配置完整 */
    NOT_CONFIGURED,

    /** 概率与关键词都未命中 */
    NOT_MATCHED,

    /** 命中触发条件，但仍在冷却期内 */
    COOLDOWN
}

data class ImageGenDecision(
    val triggered: Boolean,
    val reason: ImageGenTriggerReason,
    val matchedKeyword: String? = null,
    /** 实际送去生图的画面描述，便于排查「货不对板」 */
    val prompt: String? = null
)

// ------------------------------------------------------------------ 纯逻辑

object ImageGenTriggerLogic {

    private const val PROMPT_MAX_LENGTH = 800

    /**
     * 解析生效配置：`auto` 模式取主 API 的地址与密钥，模型始终用生图模块自己的模型。
     *
     * @param mainConnection 主 API 的 (baseUrl, apiKey)，未配置时为 null
     */
    fun resolveEffective(
        global: ImageGenGlobalConfig,
        override: ImageGenCompanionOverride,
        mainConnection: Pair<String, String>?
    ): ImageGenEffectiveConfig {
        val useAuto = global.connectionMode == ImageGenGlobalConfig.CONNECTION_MODE_AUTO
        val baseUrl = if (useAuto) mainConnection?.first.orEmpty() else global.baseUrl
        val apiKey = if (useAuto) mainConnection?.second.orEmpty() else global.apiKey

        val ready = global.enabled &&
            baseUrl.isNotBlank() &&
            apiKey.isNotBlank() &&
            global.model.isNotBlank()

        val probability = if (override.enabled) override.probability else global.probability
        val keywords = if (override.enabled) override.keywords else global.keywords

        return ImageGenEffectiveConfig(
            ready = ready,
            probability = probability.coerceIn(0, 100),
            keywords = keywords,
            cooldownMs = global.cooldownMinutes.coerceAtLeast(0) * 60_000L
        )
    }

    /** 返回命中的关键词（空串/空列表返回 null）。 */
    fun matchKeyword(text: String, keywords: List<String>): String? {
        if (text.isBlank()) return null
        return keywords.firstOrNull { it.isNotBlank() && text.contains(it, ignoreCase = true) }
    }

    /**
     * 触发判定。
     *
     * 顺序：总开关 → 配置完整性 → 关键词（优先，无视概率）→ 概率抽签 → 冷却闸门。
     *
     * @param randomRoll 0–99 的随机数，注入以便测试
     */
    fun decide(
        global: ImageGenGlobalConfig,
        effective: ImageGenEffectiveConfig,
        userText: String,
        aiText: String,
        lastGenAtMs: Long,
        nowMs: Long,
        randomRoll: Int
    ): ImageGenDecision {
        if (!global.enabled) {
            return ImageGenDecision(false, ImageGenTriggerReason.DISABLED)
        }
        if (!effective.ready) {
            return ImageGenDecision(false, ImageGenTriggerReason.NOT_CONFIGURED)
        }

        val keyword = matchKeyword(userText, effective.keywords)
            ?: matchKeyword(aiText, effective.keywords)

        val triggered = keyword != null ||
            (effective.probability > 0 && randomRoll < effective.probability)

        if (!triggered) {
            return ImageGenDecision(false, ImageGenTriggerReason.NOT_MATCHED)
        }

        val inCooldown = effective.cooldownMs > 0 &&
            lastGenAtMs > 0 &&
            nowMs - lastGenAtMs < effective.cooldownMs
        if (inCooldown) {
            return ImageGenDecision(false, ImageGenTriggerReason.COOLDOWN, keyword)
        }

        return ImageGenDecision(
            triggered = true,
            reason = if (keyword != null) ImageGenTriggerReason.KEYWORD else ImageGenTriggerReason.PROBABILITY,
            matchedKeyword = keyword
        )
    }

    /**
     * 提取 AI 标注的生图描述，无标注返回 null。
     *
     * 支持 `[[生图: x]]`、`（画面：x）`、`【画面：x】`、独占一行的 `画面：x` 等全部变体；
     * 实现统一收敛在 [ImageGenProtocol]，本方法只是转发，避免多处各写一份正则。
     */
    fun extractTaggedPrompt(text: String): String? = ImageGenProtocol.extractPrompt(text)

    /**
     * 剥离生图标签 / 画面描述，避免原文泄漏到聊天气泡、会话摘要与微信镜像。
     *
     * 实现见 [ImageGenProtocol.sanitizeForDisplay]（全链路唯一清洗函数）。
     */
    fun stripTags(text: String): String = ImageGenProtocol.sanitizeForDisplay(text)

    /** 整条回复是否只剩画面描述（此时应显示占位文案而非原文）。 */
    fun isPromptOnly(text: String): Boolean = ImageGenProtocol.isPromptOnly(text)

    /**
     * 组装最终 prompt。
     *
     * 优先级：AI 标签描述 > 用户消息（关键词触发时）> AI 回复正文。
     */
    fun buildPrompt(
        aiText: String,
        userText: String,
        matchedKeyword: String?,
        template: String
    ): String {
        val tagged = extractTaggedPrompt(aiText)
        val content = when {
            tagged != null -> tagged
            matchedKeyword != null -> userText.replace(matchedKeyword, " ").trim()
                .takeIf { it.isNotBlank() } ?: stripTags(aiText)
            else -> stripTags(aiText)
        }.take(PROMPT_MAX_LENGTH)

        if (content.isBlank()) return ""
        val safeTemplate = template.trim()
        return when {
            safeTemplate.isEmpty() -> content
            safeTemplate.contains(PROMPT_PLACEHOLDER) ->
                safeTemplate.replace(PROMPT_PLACEHOLDER, content).trim()
            else -> "$safeTemplate$content".trim()
        }
    }

    /**
     * 注入给模型的生图协议说明；总开关关闭时返回空串（零行为变化）。
     *
     * 实现见 [ImageGenProtocol.systemRules]（微信 / QQ 桥接链路复用同一份文案）。
     */
    fun systemRules(enabled: Boolean, hasKeywordTrigger: Boolean): String =
        ImageGenProtocol.systemRules(enabled = enabled, hasKeywordTrigger = hasKeywordTrigger)

    private const val PROMPT_PLACEHOLDER = "{content}"
}

// ------------------------------------------------------------------ 编排

/** [ImageGenCoordinator] 的依赖集合，全部以 lambda 注入以便测试。 */
class ImageGenDeps(
    val provider: ImageGenerationProvider,
    val loadGlobalConfig: suspend () -> ImageGenGlobalConfig,
    val loadLastGenAt: suspend () -> Long,
    val saveLastGenAt: suspend (Long) -> Unit,
    /** 主 API 的 (baseUrl, apiKey)，未配置时返回 null */
    val resolveMainConnection: suspend () -> Pair<String, String>?,
    val writeMessage: suspend (ChatMessage) -> Long,
    /** (提示文案, 是否为错误) */
    val emitMessage: (String, Boolean) -> Unit,
    /** 判定通过、即将开始生图时回调（用于点亮等待动画） */
    val onGenerationStart: () -> Unit = {},
    /** 生图结束（含失败）时回调（用于关闭等待动画） */
    val onGenerationFinish: () -> Unit = {},
    val randomRoll: () -> Int = { Random.nextInt(100) }
)

/**
 * 生图编排：读配置 → 判定 → 提示 → 生图 → 落库。
 *
 * 调用方必须保证整体被 try/catch 包住（本类内部也会兜底），
 * 任何失败都不允许影响聊天主流程。
 */
class ImageGenCoordinator(
    private val companionId: Long,
    private val deps: ImageGenDeps,
    private val override: ImageGenCompanionOverride = ImageGenCompanionOverride()
) {

    /**
     * @return 判定结果，便于调用方日志与调试（失败不影响聊天）
     */
    suspend fun maybeTriggerImage(userText: String, aiText: String): ImageGenDecision {
        val global = runCatching { deps.loadGlobalConfig() }
            .getOrElse { return ImageGenDecision(false, ImageGenTriggerReason.NOT_CONFIGURED) }

        if (!global.enabled) {
            return ImageGenDecision(false, ImageGenTriggerReason.DISABLED)
        }

        val mainConnection = runCatching { deps.resolveMainConnection() }.getOrNull()
        val effective = ImageGenTriggerLogic.resolveEffective(global, override, mainConnection)
        val now = System.currentTimeMillis()
        val lastGenAt = runCatching { deps.loadLastGenAt() }.getOrDefault(0L)

        val decision = ImageGenTriggerLogic.decide(
            global = global,
            effective = effective,
            userText = userText,
            aiText = aiText,
            lastGenAtMs = lastGenAt,
            nowMs = now,
            randomRoll = deps.randomRoll()
        )
        if (!decision.triggered) return decision

        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = aiText,
            userText = userText,
            matchedKeyword = decision.matchedKeyword,
            template = global.promptTemplate
        )
        if (prompt.isBlank()) {
            return ImageGenDecision(false, ImageGenTriggerReason.NOT_CONFIGURED)
        }

        val baseUrl = if (global.connectionMode == ImageGenGlobalConfig.CONNECTION_MODE_AUTO) {
            mainConnection?.first.orEmpty()
        } else {
            global.baseUrl
        }
        val apiKey = if (global.connectionMode == ImageGenGlobalConfig.CONNECTION_MODE_AUTO) {
            mainConnection?.second.orEmpty()
        } else {
            global.apiKey
        }

        // 先落冷却，避免失败后立刻重试造成的连环消耗
        runCatching { deps.saveLastGenAt(now) }

        // 只有真正开始生图才点亮等待动画 —— 否则每一轮 AI 回复都会闪一下指示器。
        // 触发瞬间的提示也不再走 toast：聊天页有持续的液态玻璃动画，toast 反而一闪而过。
        deps.onGenerationStart()
        val running = decision.copy(prompt = prompt)
        try {
            val result = runCatching {
                deps.provider.generateImage(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = global.model,
                    prompt = prompt,
                    size = global.size,
                    count = global.count
                ).getOrThrow()
            }

            return result.fold(
                onSuccess = { images ->
                    var written = 0
                    images.forEach { image ->
                        val messageId = runCatching {
                            deps.writeMessage(
                                ChatMessage(
                                    companionId = companionId,
                                    // 必须使用系统保留标签，自定义 [xxx] 会被当成表情包标记
                                    content = IMAGE_MESSAGE_CONTENT,
                                    isFromUser = false,
                                    timestamp = System.currentTimeMillis(),
                                    type = MessageType.IMAGE,
                                    linkString = image.filePath,
                                    searchContent = prompt
                                )
                            )
                        }.getOrDefault(-1L)
                        if (messageId > 0) written++
                    }
                    if (written > 0) {
                        deps.emitMessage("配图已生成", false)
                        running
                    } else {
                        deps.emitMessage("配图生成失败：图片未能写入聊天记录", true)
                        running.copy(triggered = false)
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    deps.emitMessage(
                        "配图生成失败：${error.message ?: error.javaClass.simpleName}",
                        true
                    )
                    running.copy(triggered = false)
                }
            )
        } finally {
            deps.onGenerationFinish()
        }
    }

    companion object {
        /** 系统保留标签，不可改成自定义内容（否则渲染成表情包异常图标） */
        const val IMAGE_MESSAGE_CONTENT = "[图片]"
    }
}
