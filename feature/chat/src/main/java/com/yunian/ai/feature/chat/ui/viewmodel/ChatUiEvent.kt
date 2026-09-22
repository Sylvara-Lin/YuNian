package com.yunian.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable

@Stable
sealed class ChatUiEvent {

    data class Info(val message: String) : ChatUiEvent()

    data class Error(val message: String) : ChatUiEvent()

    data class ContentBlocked(val reason: String) : ChatUiEvent()

    data object StreamCompleted : ChatUiEvent()

    data class MessageReadyToNavigate(val messageId: Long) : ChatUiEvent()
}
