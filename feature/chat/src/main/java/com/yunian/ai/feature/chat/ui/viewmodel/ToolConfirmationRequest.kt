package com.yunian.ai.feature.chat.ui.viewmodel

data class ToolConfirmationRequest(
    val id: Long,
    val toolName: String,
    val summary: String,
    val argumentsJson: String
)
