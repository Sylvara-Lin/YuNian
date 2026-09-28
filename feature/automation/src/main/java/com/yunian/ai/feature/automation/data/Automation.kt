package com.yunian.ai.feature.automation.data

import kotlinx.serialization.Serializable

enum class AutomationType { ONCE, DAILY, WEEKLY }

enum class WorkflowNodeType {

    START,

    END,

    ACTION,

    AI_GENERATE,

    CONDITION
}

@Serializable
data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,

    val title: String = "",

    val prompt: String = "",

    val outputVar: String = "",

    val message: String = "",

    val actionType: String = "companion",

    val conditionExpr: String = ""
)

@Serializable
data class WorkflowEdge(
    val id: String,
    val source: String,
    val target: String,

    val label: String = ""
)

@Serializable
data class AutomationStats(
    val fireCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastFiredAt: Long = 0L,

    val lastScheduledFiredAt: Long = 0L,

    val lastResult: String? = null,

    val lastMessage: String = ""
)

@Serializable
data class Automation(
    val id: String,
    val title: String,
    val companionId: Long,
    val type: AutomationType,
    val triggerAtMillis: Long,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val dayOfWeek: Int? = null,

    val message: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),

    val isWorkflow: Boolean = false,

    val nodes: List<WorkflowNode> = emptyList(),

    val edges: List<WorkflowEdge> = emptyList(),

    val stats: AutomationStats = AutomationStats()
)
