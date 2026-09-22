package com.yunian.ai.agent.host

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.agent.audit.ToolCallRecord
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.agent.uniffi.ToolHost
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.domain.ToolRegistry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Rust `ToolHost` 回调实现：Agent 回合中会话级工具的执行副作用桥。
 *
 * Rust 侧契约（`agent-native/src/agent.rs`）：
 * - 内置聊天工具（emit_segmented / send_sticker / emit_bubble）在 Rust 内执行，**不回调本类**；
 * - 会话级工具（request.tools 传入的）经本回调执行，`contextJson` 含
 *   `{companion_id, group_id, recent_history_summary}`。
 *
 * 职责边界（对齐「决策在 Rust、Kotlin 纯 IO」原则）：
 * - 记忆工具（recall_memory / save_memory / consolidate_memory）：分派到
 *   [AgentFacade.executeMemoryTool]（Rust `MemorySelector` 决策，副作用经 `MemoryStore` 回调落库）；
 * - 其余会话工具：查 [ToolRegistry]（feature 层注册的全局领域工具）分派执行；
 * - 未注册工具：返回错误文本（Rust 会将其作为工具结果回灌模型）。
 *
 * 注意：商业/支付类工具若需用户确认，应在 feature 层接入确认策略后再放行到 ToolRegistry，
 * 本类不做确认决策（决策在 Rust `ConfirmPolicy` / feature 层）。
 */
class AgentToolHost(context: Context) : ToolHost {

    companion object {
        private const val TAG = "AgentToolHost"

        /** 多 Agent 编排：委派工具（主回合发起子任务）。 */
        private const val DELEGATE_TASK_TOOL = "delegate_task"
        /** 多 Agent 编排：汇聚工具（主回合查询委派结果）。 */
        private const val FETCH_DELEGATION_TOOL = "fetch_delegation_result"

        /**
         * 工具执行超时（P1-5）：单个会话级工具执行超过此时长即判定失败并回灌模型。
         * 注意：超时后底层任务仍会在池中跑完（无法安全取消），但结果不再回灌。
         */
        private const val TOOL_TIMEOUT_MS = 20_000L

        /** 工具执行线程池大小（有界，避免并发工具打爆 IO/主线程）。 */
        private const val TOOL_POOL_SIZE = 2

        /** Rust `MemorySelector` 注册的记忆工具名集合。 */
        private val MEMORY_TOOLS = setOf("recall_memory", "save_memory", "consolidate_memory")

        /** 技能渐进式披露 L2 工具：按需加载技能完整正文。 */
        private const val LOAD_SKILL_TOOL = "load_skill"

        /** Rust `builtin_send_sticker` 内部回调：按标签预选实际发送的表情包。 */
        private const val STICKER_PICK_TOOL = "sticker_pick"
    }

    private val appContext: Context = context.applicationContext

    /**
     * 工具副作用执行池（P1-4）：Rust 回调线程只做提交 + 阻塞等待结果，
     * 实际工具执行（DB / 网络 / runBlocking）落到专用有界线程池，
     * 避免阻塞 Rust 工具循环线程之外的调度面，并支持超时控制。
     */
    private val toolExecutor = Executors.newFixedThreadPool(TOOL_POOL_SIZE) { r ->
        Thread(r, "agent-tool-executor").apply { isDaemon = true }
    }

    /**
     * 本回合工具调用明细（线程安全，Rust 回调线程写入；回合结束后由调用方读取
     * 并传入 AgentFacade.recordDispatchLog 落调度日志）。
     */
    private val toolCalls = CopyOnWriteArrayList<ToolCallRecord>()

    /** 回合结束后读取全部工具调用明细（时间正序）。 */
    fun collectedToolCalls(): List<ToolCallRecord> = toolCalls.toList()

    override fun execute(toolName: String, argumentsJson: String, contextJson: String): String {
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "tool call: name=$toolName args=$argumentsJson ctx=$contextJson")
        try {
            // 提交到专用池执行（P1-4/P1-5：有界并发 + 超时兜底）
            val future = toolExecutor.submit<String> {
                executeToolBlocking(toolName, argumentsJson, contextJson)
            }
            val result = try {
                future.get(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // 超时：任务仍可能在池中继续，但结果不再回灌模型
                Log.w(TAG, "tool timeout: $toolName >${TOOL_TIMEOUT_MS / 1000}s")
                "错误：工具 $toolName 执行超时（超过 ${TOOL_TIMEOUT_MS / 1000} 秒），请简化操作后重试"
            } catch (e: Exception) {
                Log.w(TAG, "tool execute failed: $toolName", e)
                "错误：工具 $toolName 执行失败：${e.message ?: "未知错误"}"
            }
            val elapsed = System.currentTimeMillis() - startedAt
            toolCalls.add(ToolCallRecord(name = toolName, args = argumentsJson, result = result, elapsedMs = elapsed, ok = true))
            Log.i(
                TAG,
                "tool done: name=$toolName elapsed=${elapsed}ms result=${result.take(120)}",
            )
            return result
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - startedAt
            toolCalls.add(ToolCallRecord(name = toolName, args = argumentsJson, result = t.message ?: t.javaClass.simpleName, elapsedMs = elapsed, ok = false))
            Log.e(TAG, "execute $toolName failed", t)
            return "工具执行失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * 实际工具执行（在 [toolExecutor] 池线程上运行）：记忆工具 / load_skill /
     * sticker_pick / ToolRegistry 全局工具分派。
     */
    private fun executeToolBlocking(toolName: String, argumentsJson: String, contextJson: String): String =
        when {
            toolName in MEMORY_TOOLS ->
                AgentFacade.executeMemoryTool(appContext, toolName, argumentsJson, contextJson)

            toolName == LOAD_SKILL_TOOL -> executeLoadSkill(argumentsJson, contextJson)

            toolName == STICKER_PICK_TOOL -> executeStickerPick(argumentsJson, contextJson)

            toolName == DELEGATE_TASK_TOOL -> executeDelegateTask(argumentsJson, contextJson)

            toolName == FETCH_DELEGATION_TOOL -> executeFetchDelegation(argumentsJson)

            else -> {
                val tool = ToolRegistry.get(toolName)
                if (tool != null) {
                    runBlocking { tool.execute(argumentsJson) }
                } else {
                    "错误：未注册的工具 $toolName"
                }
            }
        }

    /**
     * delegate_task：创建委派（PENDING）。
     * argumentsJson 契约：`{"role": "analyst|helper", "prompt": "任务说明"}`；
     * contextJson 含 companionId（"companion_id"）。
     */
    private fun executeDelegateTask(argumentsJson: String, contextJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "错误：delegate_task 参数解析失败（需要 JSON {\"role\": \"...\", \"prompt\": \"...\"}）"
        }
        val role = args.optString("role", "helper")
        val prompt = args.optString("prompt", "")
        if (prompt.isBlank()) return "错误：delegate_task 缺少 prompt（任务说明）"
        val companionId = try {
            org.json.JSONObject(contextJson).optLong("companion_id", 0L).takeIf { it > 0L }
        } catch (e: Exception) {
            null
        }
        val coordinator = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.delegation.DelegationCoordinator::class.java)
        if (coordinator == null) return "错误：委派协调器未初始化"
        val record = runBlocking { coordinator.create(role, prompt, companionId) }
        return org.json.JSONObject().apply {
            put("delegation_id", record.id)
            put("role", role)
            put("status", "PENDING")
            put("message", "委派已创建，稍后可用 fetch_delegation_result 查询结果")
        }.toString()
    }

    /**
     * fetch_delegation_result：查询委派结果。
     * argumentsJson 契约：`{"delegation_id": 1}`。
     */
    private fun executeFetchDelegation(argumentsJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "错误：fetch_delegation_result 参数解析失败（需要 JSON {\"delegation_id\": 1}）"
        }
        val id = args.optLong("delegation_id", 0L)
        if (id <= 0L) return "错误：缺少 delegation_id"
        val coordinator = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.delegation.DelegationCoordinator::class.java)
        if (coordinator == null) return "错误：委派协调器未初始化"
        val record = runBlocking { coordinator.get(id) } ?: return "错误：委派记录不存在（id=$id）"
        return org.json.JSONObject().apply {
            put("delegation_id", record.id)
            put("role", record.role)
            put("status", record.status.name)
            put("result", record.result)
            put("error", record.error)
        }.toString()
    }

    /**
     * load_skill 工具：渐进式披露 L2——按需读取技能完整正文回灌模型。
     *
     * argumentsJson 契约：`{"skill_id": "..."}`，可选 `companion_id`。
     * 返回技能正文；技能不存在/已禁用返回明确错误文本（模型会收到工具结果并调整）。
     */
    private fun executeLoadSkill(argumentsJson: String, contextJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "错误：load_skill 参数解析失败（需要 JSON {\"skill_id\": \"...\"}）"
        }
        val skillId = args.optString("skill_id").ifBlank { return "错误：load_skill 缺少 skill_id 参数" }
        // contextJson 契约为 {companion_id, group_id, recent_history_summary}
        val companionId = try {
            val ctx = org.json.JSONObject(contextJson)
            if (ctx.has("companion_id") && !ctx.isNull("companion_id")) {
                ctx.getLong("companion_id")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        val content = AgentFacade.loadSkillContent(appContext, skillId, companionId)
        if (content == null) {
            return "错误：技能 $skillId 不存在或已禁用"
        }
        Log.i(TAG, "load_skill ok: skill=$skillId len=${content.length}")
        return content
    }

    /**
     * sticker_pick 工具：Rust `builtin_send_sticker` 命中后内部回调，预选实际发送的表情包。
     *
     * argumentsJson 契约：`{"tags":["开心","抱抱"]}`。
     * 流程：偏好引擎 OR 采样 1 张 → DB 取条目 → 返回 JSON：
     * `{"ok":true,"entryId":123,"fileName":"sticker_xxx.png","description":"开心"}`。
     * 无候选 / 参数非法 / 异常 → `{"ok":false}`（Rust 回退事件透传，落地侧兜底采样）。
     *
     * 注意：仅预选不记录使用（recordUsage 由落地成功时执行，避免重复计数）。
     */
    private fun executeStickerPick(argumentsJson: String, contextJson: String): String {
        val args = try {
            org.json.JSONObject(argumentsJson)
        } catch (e: Exception) {
            return "{\"ok\":false}"
        }
        val tags = args.optJSONArray("tags")
            ?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }
                }
            }
            ?: emptyList()
        if (tags.isEmpty()) return "{\"ok\":false}"
        return try {
            runBlocking {
                val entryIds = StickerPreferenceFacade.sampleCandidates(appContext, 1, tags)
                if (entryIds.isEmpty()) return@runBlocking "{\"ok\":false}"
                val entry = AppDatabase.getDatabase(appContext).stickerEntryDao().getById(entryIds.first())
                    ?: return@runBlocking "{\"ok\":false}"
                val out = org.json.JSONObject()
                out.put("ok", true)
                out.put("entryId", entry.id)
                out.put("fileName", entry.fileName.orEmpty())
                out.put("description", entry.description.orEmpty())
                out.toString()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sticker_pick failed", t)
            "{\"ok\":false}"
        }
    }
}
