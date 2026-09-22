package com.yunian.ai.feature.automation

import android.content.Context
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.agent.host.AgentToolHost
import com.yunian.ai.agent.uniffi.AgentTurnRequest
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.WorkflowNode
import com.yunian.ai.feature.automation.data.WorkflowNodeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WorkflowEngine(
    private val context: Context,
    private val messageWriter: MessageWriteCoordinator,
    private val chatRepository: ChatRepository? = null
) {

    sealed class Result {
        data class Success(val message: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    suspend fun execute(automation: Automation): Result = withContext(Dispatchers.IO) {
        if (!automation.isWorkflow) {
            return@withContext Result.Success(runAction(automation, null, emptyMap()))
        }
        val nodeMap = automation.nodes.associateBy { it.id }
        val startNode = automation.nodes.firstOrNull { it.type == WorkflowNodeType.START }
            ?: return@withContext Result.Failure("工作流缺少 START 节点")
        val variables = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()

        val patrolContext = if (automation.nodes.any { it.type == WorkflowNodeType.AI_GENERATE }) {
            buildPatrolContext(automation.companionId)
        } else ""
        var current = startNode
        var outputMessage = ""

        try {

            withTimeoutOrNull(TimeoutBudgets.AUTOMATION_WORKFLOW_TOTAL_MS) {
                while (true) {
                    if (current.id in visited) break
                    visited.add(current.id)

                    when (current.type) {
                        WorkflowNodeType.START -> {  }
                        WorkflowNodeType.END -> break
                        WorkflowNodeType.ACTION -> {
                            outputMessage = runAction(automation, current, variables)
                        }
                        WorkflowNodeType.AI_GENERATE -> {
                            val enrichedPrompt = if (patrolContext.isBlank()) {
                                current.prompt
                            } else {
                                "$patrolContext\n\n【你的任务】${current.prompt}"
                            }

                            val generated = withTimeoutOrNull(TimeoutBudgets.AUTOMATION_AI_TIMEOUT_MS) {
                                callAgentGeneration(automation.companionId, enrichedPrompt)
                            } ?: ""
                            variables[current.outputVar.ifBlank { "result" }] = generated
                            outputMessage = generated
                        }
                        WorkflowNodeType.CONDITION -> {  }
                    }

                    val outEdges = automation.edges.filter { it.source == current.id }
                    val nextId = if (current.type == WorkflowNodeType.CONDITION) {
                        val conditionResult = evaluateCondition(current, variables)
                        outEdges.firstOrNull { it.label == conditionResult }?.target
                            ?: outEdges.firstOrNull()?.target
                    } else {
                        outEdges.firstOrNull()?.target
                    }

                    current = nextId?.let { nodeMap[it] } ?: break
                }

                Result.Success(outputMessage.ifBlank { "工作流执行完成" })
            } ?: Result.Failure("工作流执行超时")
        } catch (e: Exception) {
            SecureLog.e("WorkflowEngine", "execute failed", e)
            Result.Failure(e.message ?: "工作流执行异常")
        }
    }

    /**
     * 工作流 AI 生成节点 —— 改走 Rust Cordis Agent（[AgentFacade.runTurn]）。
     *
     * 单轮、无工具，`enrichedPrompt` 作为本轮 user 内容追加在历史尾部；
     * 人设 / 记忆 / 世界书由 Rust 编排器注入（故不传 systemPrompt）。
     */
    private suspend fun callAgentGeneration(companionId: Long, prompt: String): String {
        val appContext = context.applicationContext
        val turnRequest = AgentTurnRequest(
            groupId = null,
            historyJson = serializeHistoryJson(prompt),
            tools = emptyList(),
            maxRounds = 1u,
            toolChoice = "auto",
            stickerProbability = 0u,
            image = null,
            systemPrompt = null,
            companionNameMapJson = null,
        )
        val result = runCatching {
            AgentFacade.runTurn(turnRequest, appContext, companionId, AgentToolHost(appContext))
        }.onFailure {
            SecureLog.e("WorkflowEngine", "agent generation failed", it)
        }.getOrNull() ?: return ""
        return result.finalText.trim()
            .ifBlank { result.events.filter { it.kind == "bubble" }.joinToString("\n") { it.text }.trim() }
    }

    /** 单条 user 指令 → OpenAI messages JSON。 */
    private fun serializeHistoryJson(prompt: String): String =
        org.json.JSONArray()
            .put(org.json.JSONObject().apply { put("role", "user"); put("content", prompt) })
            .toString()

    private suspend fun buildPatrolContext(companionId: Long): String {
        val repo = chatRepository ?: return ""
        return runCatching {
            val recent = repo.getRecentMessagesSync(companionId, 10)
                .filter { it.content.replace("\u200B", "").isNotBlank() }
            if (recent.isEmpty()) return@runCatching ""

            val sb = StringBuilder("【巡检快照 · ${formatNow()}】\n最近对话：\n")
            recent.takeLast(6).forEach { msg ->
                val speaker = if (msg.isFromUser) "用户" else "伴侣"
                sb.append("$speaker：${msg.content.take(60)}\n")
            }
            val lastUser = recent.lastOrNull { it.isFromUser }
            if (lastUser != null) {
                val idleMinutes = (System.currentTimeMillis() - lastUser.timestamp) / 60_000L
                val idleText = when {
                    idleMinutes < 10 -> "用户 $idleMinutes 分钟前刚回复，互动正常"
                    idleMinutes < 60 -> "用户已经 ${idleMinutes} 分钟没回消息了"
                    idleMinutes < 24 * 60 -> "用户已经 ${idleMinutes / 60} 小时没回消息了"
                    else -> "用户已经 ${idleMinutes / 60 / 24} 天没回消息了"
                }
                sb.append("冷落判断：$idleText\n")
            } else {
                sb.append("冷落判断：聊天记录里还没有用户的消息\n")
            }
            sb.toString()
        }.getOrElse { e ->
            SecureLog.w("WorkflowEngine", "buildPatrolContext failed: ${e.message}")
            ""
        }
    }

    private fun formatNow(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
            "${cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:${cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')}"
    }

    private suspend fun runAction(
        automation: Automation,
        node: WorkflowNode?,
        variables: Map<String, String>
    ): String {
        val rawMessage = node?.message?.takeIf { it.isNotBlank() } ?: automation.message
        val message = replaceVariables(rawMessage, variables)
        if (message.isBlank()) return ""

        val outputSafety = ContentFilter.checkOutputSafety(message)
        if (!outputSafety.isSafe) {
            SecureLog.w("WorkflowEngine", "workflow message blocked: ${outputSafety.reason}")
            return ""
        }

        val actionType = node?.actionType ?: "companion"
        if (actionType == "notification") {
            AutomationNotifier.show(context, automation.title, message, automation.companionId)
        } else {
            runCatching {
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = automation.companionId,
                        content = message,
                        isFromUser = false
                    )
                )
            }.onFailure { SecureLog.e("WorkflowEngine", "write chat message failed", it) }
        }
        return message
    }

    private fun evaluateCondition(node: WorkflowNode, variables: Map<String, String>): String {

        val value = variables.values.joinToString(" ").lowercase()
        val matched = value.contains("true") || value.contains("是") || value.contains("yes")
        return if (matched) "true" else "false"
    }

    private fun replaceVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
