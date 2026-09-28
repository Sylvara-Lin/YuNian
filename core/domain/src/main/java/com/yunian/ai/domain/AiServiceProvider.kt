package com.yunian.ai.domain

data class AiResponse(
    val content: String,
    val reasoningContent: String? = null,

    val toolCalls: List<AiToolCall>? = null,

    val finishReason: String? = null
)

data class AiToolCall(
    val id: String,
    val name: String,

    val arguments: String
)

data class AiCompanionInfo(
    val id: Long,
    val name: String,
    val personality: String,
    val age: Int? = null,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val systemPrompt: String? = null
)

enum class AiMessageType {
    TEXT, IMAGE
}

data class AiChatMessage(
    val isFromUser: Boolean,
    val content: String,
    val timestamp: Long,
    val type: AiMessageType = AiMessageType.TEXT,
    val companionId: Long = 0,

    val role: AiMessageRole? = null,

    val toolName: String? = null
)

interface AiServiceProvider {

    suspend fun sendMessage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int = 0,
        ntpTimeEnabled: Boolean = false,
        extraSystemRules: String = ""
    ): AiResponse

    suspend fun sendMessage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean,
        tools: List<AiTool>?,
        extraSystemRules: String = ""
    ): AiResponse

    suspend fun sendMessageWithImage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        imagePath: String,
        stickerProbability: Int = 0,
        ntpTimeEnabled: Boolean = false
    ): AiResponse

    fun shouldProactivelyMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): Boolean

    fun shouldProactivelyMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        settings: ProactiveMessageSettings?
    ): Boolean {

        return shouldProactivelyMessage(companion, recentMessages)
    }

    suspend fun generateProactiveMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): String?

    suspend fun generateProactiveMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        settings: ProactiveMessageSettings?
    ): String? {

        return generateProactiveMessage(companion, recentMessages)
    }

    suspend fun generateFollowUpReminder(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        settings: ProactiveMessageSettings?
    ): String? {

        return generateProactiveMessage(companion, recentMessages, settings)
    }

    suspend fun generateFollowUpQuestion(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        lastAiContent: String
    ): String?

    suspend fun callJudge(prompt: String): String

    suspend fun callGeneration(prompt: String): String
}
