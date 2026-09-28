package com.yunian.ai.domain

data class ProactiveMessageSettings(

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
