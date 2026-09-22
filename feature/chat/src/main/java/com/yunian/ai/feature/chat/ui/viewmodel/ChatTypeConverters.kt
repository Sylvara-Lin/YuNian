package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiMessageType

internal fun CompanionEntity.toAiCompanionInfo(): AiCompanionInfo = AiCompanionInfo(
    id = id, name = name, personality = personality,
    age = age, backstory = backstory, speakingStyle = speakingStyle,
    systemPrompt = systemPrompt
)

internal fun ChatMessage.toAiChatMessage(): AiChatMessage = AiChatMessage(
    isFromUser = isFromUser, content = contentForModel(), timestamp = timestamp,
    type = when (type) {
        MessageType.IMAGE -> AiMessageType.IMAGE
        else -> AiMessageType.TEXT
    },
    companionId = companionId
)

/**
 * 送给模型的这条历史消息的文本。
 *
 * 图片消息的 content 是系统保留标签 "[图片]"，模型完全看不到画面内容，
 * 于是用户说「再生成一张」时它会另起炉灶（货不对板）。
 * 这里把 searchContent（生图时用的画面描述）作为**系统注记**附给模型看，
 * 但 **content 字段本身不变** —— 渲染层仍按保留标签渲染成图片气泡。
 *
 * 注意：早期版本用的是 `[图片]（画面：xxx）`，这个写法会被模型原样抄进回复，
 * 于是画面描述泄漏成了独立文本气泡（BUG-1 的根因诱导源）。
 * 现在改成"系统注记 + 显式禁止模仿"的措辞，模型不再有可照抄的模板。
 */
internal fun ChatMessage.contentForModel(): String =
    if (type == MessageType.IMAGE && searchContent.isNotBlank()) {
        "$content（系统注记：该图画面描述为 $searchContent；" +
            "此注记仅用于你理解图片内容，禁止在回复中输出任何「画面：」或括号包裹的画面描述）"
    } else {
        content
    }

internal fun List<ChatMessage>.toAiChatMessages(): List<AiChatMessage> =

    filter { it.type != MessageType.REASONING }.map { it.toAiChatMessage() }
