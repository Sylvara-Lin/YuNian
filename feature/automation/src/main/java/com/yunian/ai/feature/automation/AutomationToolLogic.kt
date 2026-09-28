package com.yunian.ai.feature.automation

import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationType
import com.yunian.ai.feature.automation.data.WorkflowEdge
import com.yunian.ai.feature.automation.data.WorkflowNode
import com.yunian.ai.feature.automation.data.WorkflowNodeType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object AutomationToolLogic {

    private val json = Json { ignoreUnknownKeys = true }

    data class CreateParams(
        val title: String,
        val companionId: Long,
        val type: AutomationType,
        val triggerAtMillis: Long,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val dayOfWeekCalendar: Int?,
        val message: String
    )

    data class CreateWorkflowParams(
        val title: String,
        val companionId: Long,
        val type: AutomationType,
        val triggerAtMillis: Long,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val dayOfWeekCalendar: Int?,
        val description: String,
        val nodes: List<WorkflowNode>,
        val edges: List<WorkflowEdge>
    )

    fun parseCreateParams(argsJson: String): CreateParams? {
        return runCatching {
            val obj = json.parseToJsonElement(argsJson).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val companionId = obj["companionId"]?.jsonPrimitive?.longOrNull ?: 0L
            if (companionId <= 0L) return null
            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "once" -> AutomationType.ONCE
                "daily" -> AutomationType.DAILY
                "weekly" -> AutomationType.WEEKLY
                else -> return null
            }
            val triggerAt = obj["triggerAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val hour = obj["hour"]?.jsonPrimitive?.intOrNull ?: 0
            val minute = obj["minute"]?.jsonPrimitive?.intOrNull ?: 0

            val dayOfWeekAi = obj["dayOfWeek"]?.jsonPrimitive?.intOrNull
            val dayOfWeekCalendar = dayOfWeekAi?.let { if (it in 1..7) (it % 7) + 1 else null }

            if (type == AutomationType.WEEKLY && dayOfWeekCalendar == null) return null
            val message = obj["message"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: defaultMessage(title)
            CreateParams(
                title = title,
                companionId = companionId,
                type = type,
                triggerAtMillis = triggerAt,
                hourOfDay = hour.coerceIn(0, 23),
                minuteOfHour = minute.coerceIn(0, 59),
                dayOfWeekCalendar = dayOfWeekCalendar,
                message = message
            )
        }.getOrNull()
    }

    fun parseCreateWorkflowParams(argsJson: String): CreateWorkflowParams? {
        return runCatching {
            val obj = json.parseToJsonElement(argsJson).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val companionId = obj["companionId"]?.jsonPrimitive?.longOrNull ?: 0L
            if (companionId <= 0L) return null
            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "once" -> AutomationType.ONCE
                "daily" -> AutomationType.DAILY
                "weekly" -> AutomationType.WEEKLY
                else -> return null
            }
            val triggerAt = obj["triggerAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val hour = obj["hour"]?.jsonPrimitive?.intOrNull ?: 0
            val minute = obj["minute"]?.jsonPrimitive?.intOrNull ?: 0
            val dayOfWeekAi = obj["dayOfWeek"]?.jsonPrimitive?.intOrNull
            val dayOfWeekCalendar = dayOfWeekAi?.let { if (it in 1..7) (it % 7) + 1 else null }

            if (type == AutomationType.WEEKLY && dayOfWeekCalendar == null) return null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""

            val nodes = obj["nodes"]?.jsonArray?.map { parseWorkflowNode(it.jsonObject) } ?: emptyList()
            val edges = obj["edges"]?.jsonArray?.map { parseWorkflowEdge(it.jsonObject) } ?: emptyList()

            CreateWorkflowParams(
                title = title,
                companionId = companionId,
                type = type,
                triggerAtMillis = triggerAt,
                hourOfDay = hour.coerceIn(0, 23),
                minuteOfHour = minute.coerceIn(0, 59),
                dayOfWeekCalendar = dayOfWeekCalendar,
                description = description,
                nodes = nodes,
                edges = edges
            )
        }.getOrNull()
    }

    private fun parseWorkflowNode(obj: kotlinx.serialization.json.JsonObject): WorkflowNode {
        val type = when (obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "start" -> WorkflowNodeType.START
            "end" -> WorkflowNodeType.END
            "action" -> WorkflowNodeType.ACTION
            "ai_generate" -> WorkflowNodeType.AI_GENERATE
            "condition" -> WorkflowNodeType.CONDITION
            else -> WorkflowNodeType.START
        }
        return WorkflowNode(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            type = type,
            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
            prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull ?: "",
            outputVar = obj["outputVar"]?.jsonPrimitive?.contentOrNull ?: "",
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
            actionType = obj["actionType"]?.jsonPrimitive?.contentOrNull ?: "companion",
            conditionExpr = obj["conditionExpr"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun parseWorkflowEdge(obj: kotlinx.serialization.json.JsonObject): WorkflowEdge {
        return WorkflowEdge(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "",
            target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "",
            label = obj["label"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    fun matchByTitle(automations: List<Automation>, keyword: String): List<Automation> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return automations.filter { it.title.contains(kw, ignoreCase = true) }
    }

    fun defaultMessage(title: String): String = "到点啦～该${title}啦"
}
