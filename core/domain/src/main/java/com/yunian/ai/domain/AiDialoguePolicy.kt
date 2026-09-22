package com.yunian.ai.domain

enum class AiMessageRole {
    USER,
    ASSISTANT,

    TOOL,
    SYSTEM
}

object AiOperationalMessages {
    private val exactOperational = setOf(
        "请先配置API：我 → API设置 → 添加密钥",
        "请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。",
        "请先配置并启用可用的API。",
        "模型名未配置，请在「API设置」中重新测试连接以自动选择模型。",
        "模型名未配置。",
        "系统正在加载伴侣信息，请稍后再试",
        "抱歉，找不到角色信息。",
        "抱歉，我无法继续这个话题。"
    )

    private val prefixOperational = listOf(
        "请先配置API",
        "请先配置并启用可用的API",
        "模型名未配置",
        "系统正在加载",
        "API返回空内容",
        "网络连接超时",
        "API认证失败",
        "请求过于频繁",
        "图片识别",
        "视觉识别功能已关闭",
        "API密钥为空",
        "API地址为空",
        "当前模型不支持视觉",
        "账号已被封禁",
        "内容违规",
        "内容已拦截",
        "安全检查异常",
        "消息队列已满",
        "消息发送失败",
        "回复被打断",
        "发送失败"
    )

    private const val TOAST_PREFIX = "[TOAST]"

    fun stripToastPrefix(raw: String): String =
        raw.removePrefix(TOAST_PREFIX).trim()

    fun isToastPrefixed(raw: String): Boolean =
        raw.startsWith(TOAST_PREFIX)

    fun isOperationalContent(content: String): Boolean {
        val text = stripToastPrefix(content).trim()
        if (text.isEmpty()) return false
        if (text in exactOperational) return true
        if (prefixOperational.any { text.startsWith(it) }) return true

        if (text.startsWith("工具执行失败") || text.startsWith("工具执行超时")) return true
        return false
    }

    fun asToastMessage(content: String): String? {
        val text = stripToastPrefix(content)
        if (text.isBlank()) return null
        return if (isToastPrefixed(content) || isOperationalContent(text)) text else null
    }
}

object AiDialogueHistoryPolicy {

    fun sanitizeForModel(history: List<AiChatMessage>): List<AiChatMessage> {
        if (history.isEmpty()) return emptyList()

        val filtered = history
            .asSequence()
            .map { normalizeRole(it) }
            .filter { msg ->
                val content = msg.content.replace("\u200B", "").trim()
                if (content.isEmpty()) return@filter false
                if (AiOperationalMessages.isOperationalContent(content)) return@filter false

                if (content.startsWith("[工具调用结果]") && msg.role != AiMessageRole.TOOL) {
                    return@filter msg.role == AiMessageRole.USER
                }
                true
            }
            .toList()

        if (filtered.isEmpty()) return emptyList()

        val merged = mutableListOf<AiChatMessage>()
        for (msg in filtered) {
            val last = merged.lastOrNull()
            if (last != null && last.effectiveRole() == msg.effectiveRole() && last.effectiveRole() != AiMessageRole.TOOL) {
                merged[merged.lastIndex] = last.copy(
                    content = last.content.trimEnd() + "\n" + msg.content.trimStart(),
                    timestamp = maxOf(last.timestamp, msg.timestamp)
                )
            } else {
                merged.add(msg)
            }
        }
        return merged
    }

    fun normalizeRole(msg: AiChatMessage): AiChatMessage {
        val content = msg.content
        val role = when {
            msg.role != null -> msg.role
            content.startsWith("[工具调用结果]") -> AiMessageRole.TOOL
            msg.isFromUser -> AiMessageRole.USER
            else -> AiMessageRole.ASSISTANT
        }
        return msg.copy(
            role = role,
            isFromUser = role == AiMessageRole.USER || role == AiMessageRole.TOOL
        )
    }

    private fun AiChatMessage.effectiveRole(): AiMessageRole =
        role ?: if (isFromUser) AiMessageRole.USER else AiMessageRole.ASSISTANT

    fun toolResultMessage(
        toolName: String,
        result: String,
        companionId: Long = 0L,
        timestamp: Long = System.currentTimeMillis()
    ): AiChatMessage = AiChatMessage(
        isFromUser = true,
        content = "[工具调用结果] $toolName:\n$result",
        timestamp = timestamp,
        type = AiMessageType.TEXT,
        companionId = companionId,
        role = AiMessageRole.TOOL,
        toolName = toolName
    )
}
