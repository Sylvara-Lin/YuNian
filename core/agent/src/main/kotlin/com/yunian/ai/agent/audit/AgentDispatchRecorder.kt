package com.yunian.ai.agent.audit

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.uniffi.AgentEvent
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.AgentDispatchLogDao
import com.yunian.ai.database.model.AgentDispatchLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 调度日志落库（只写 + 近况查询 + 保留窗口清理）。
 *
 * 与 [PromptAuditRecorder] 互补：
 * - prompt_audit 回答「为什么这样回复」（编排 dry_run 片段摘要）；
 * - 本类回答「AI 是怎么跑的」——每次 Agent 回合的完整调度时间线：
 *   provider/model / 起止时间 / 实际回合数 / 完成原因 / 错误 / 工具调用明细
 *   / 事件流。
 *
 * IO 模式对齐 MemoryStoreImpl：同步调用内切 IO 线程，纯追加不阻塞 LLM 请求。
 */
class AgentDispatchRecorder(context: Context) {

    companion object {
        private const val TAG = "AgentDispatchRecorder"
    }

    private val dao: AgentDispatchLogDao =
        AppDatabase.getDatabase(context.applicationContext).agentDispatchLogDao()

    /**
     * 调度日志写队列（P2-8）：record() 仅入队立即返回，落库在后台单线程执行，
     * 不阻塞回复管线；查询类方法保持同步。
     */
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agent-dispatch-writer").apply { isDaemon = true }
    }

    /**
     * 记录一次完整 Agent 回合调度日志。
     *
     * @param companionId 单聊陪伴者 id（群聊传 null）
     * @param groupId 群聊 id（单聊传 null）
     * @param sessionId 会话 id（conversationId），关联同一对话的连续回合
     * @param dispatchId 本次调度唯一标识（由调用方生成，建议复用 sessionId 或新 UUID）
     * @param provider API 提供商名（ApiProvider.name）
     * @param model 模型名（ApiConfig.model）
     * @param startedAtMs 回合开始时间戳
     * @param completedAtMs 回合结束时间戳
     * @param roundsUsed 实际使用回合数
     * @param finishedReason 完成原因（completed / max_rounds / confirm_pending / state_stop / error）
     * @param error 错误信息（无则 null）
     * @param toolNames 本次回合注入的会话级工具名
     * @param toolCalls 工具调用明细（AgentToolHost 采集）
     * @param events Rust 事件流（bubble/sticker/status/confirm_request）
     * @param querySummary 用户消息摘要
     */
    fun record(
        companionId: Long?,
        groupId: Long?,
        sessionId: String?,
        dispatchId: String,
        provider: String,
        model: String,
        startedAtMs: Long,
        completedAtMs: Long,
        roundsUsed: Int,
        finishedReason: String,
        error: String?,
        toolNames: List<String>,
        toolCalls: List<ToolCallRecord>,
        events: List<AgentEvent>,
        querySummary: String,
    ) {
        val entity = AgentDispatchLogEntity(
            companionId = companionId,
            groupId = groupId,
            sessionId = sessionId,
            dispatchId = dispatchId,
            provider = provider,
            model = model,
            startedAtMs = startedAtMs,
            completedAtMs = completedAtMs,
            roundsUsed = roundsUsed,
            finishedReason = finishedReason,
            error = error.orEmpty(),
            toolNames = toolNames.joinToString(","),
            toolCallsJson = toolCallsToJson(toolCalls),
            eventsJson = eventsToJson(events),
            querySummary = querySummary.take(200),
        )
        // P2-8：异步落库（fire-and-forget，失败仅记日志，不影响主流程）
        writeExecutor.execute {
            try {
                runBlockingOnIo { dao.insert(entity) }
            } catch (t: Throwable) {
                Log.e(TAG, "record failed", t)
            }
            // 事件溯源：回合完成事件入不可变账本（幂等键=dispatchId，重放安全）
            runCatching {
                val ledger = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.timeline.EventLedger::class.java)
                if (ledger != null) {
                    runBlocking {
                        val streamId = if (groupId != null) "agent:group:$groupId" else "agent:companion:${companionId ?: "unknown"}"
                        ledger.append(
                            streamId = streamId,
                            type = "agent.turn.completed",
                            payloadJson = JSONObject().apply {
                                put("dispatchId", dispatchId)
                                put("provider", provider)
                                put("model", model)
                                put("roundsUsed", roundsUsed)
                                put("finishedReason", finishedReason)
                                put("error", error ?: "")
                                put("toolNames", toolNames)
                            }.toString(),
                            idempotencyKey = dispatchId,
                            metadataJson = JSONObject().apply { put("sessionId", sessionId ?: "") }.toString(),
                        )
                        // ④ cordis/Agent 事件桥接：status / confirm_request 入账（幂等键=dispatchId#index；
                        // bubble/sticker 量大且消息表已存，不入账）
                        bridgeAgentEvents(ledger, streamId, dispatchId, entity.eventsJson)
                        // ③ 快照自动持久化：回合完成时对当前流保存聚合投影（version=最后 sequence）
                        ledger.snapshotStream(streamId)
                    }
                }
            }.onFailure { Log.w(TAG, "ledger append failed", it) }
        }
    }

    /**
     * ④ 事件桥接：把回合事件流中的治理/状态类事件（status、confirm_request）写入事件账本。
     * 幂等键 = dispatchId#index，重放安全。
     */
    private suspend fun bridgeAgentEvents(
        ledger: com.yunian.ai.domain.timeline.EventLedger,
        streamId: String,
        dispatchId: String,
        eventsJson: String,
    ) {
        val events = try { JSONArray(eventsJson) } catch (e: Exception) { return }
        val bridgeable = setOf("status", "confirm_request")
        for (i in 0 until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            val kind = item.optString("kind", "")
            if (kind !in bridgeable) continue
            ledger.append(
                streamId = streamId,
                type = "agent.event.$kind",
                payloadJson = JSONObject().apply {
                    put("dispatchId", dispatchId)
                    put("text", item.optString("text", ""))
                    put("extra", item.optString("extra", ""))
                }.toString(),
                idempotencyKey = "$dispatchId#$i",
            )
        }
    }

    /** 最近 N 条调度日志（时间倒序）。 */
    fun recent(limit: Int = 50, offset: Int = 0): List<AgentDispatchLogEntity> =
        runBlockingOnIo { dao.recent(limit, offset) }

    /** 按调度 id 查询（一次完整回合）。 */
    fun byDispatchId(dispatchId: String): List<AgentDispatchLogEntity> =
        runBlockingOnIo { dao.byDispatchId(dispatchId) }

    /** 同一会话的连续回合（时间正序）。 */
    fun bySession(sessionId: String): List<AgentDispatchLogEntity> =
        runBlockingOnIo { dao.bySession(sessionId) }

    /** 某陪伴者的最近调度（时间倒序）。 */
    fun byCompanion(companionId: Long, limit: Int = 50): List<AgentDispatchLogEntity> =
        runBlockingOnIo { dao.byCompanion(companionId, limit) }

    /** 某群聊的最近调度（时间倒序）。 */
    fun byGroup(groupId: Long, limit: Int = 50): List<AgentDispatchLogEntity> =
        runBlockingOnIo { dao.byGroup(groupId, limit) }

    /** 删除早于 threshold(ms) 的记录，返回删除条数。 */
    fun deleteOlderThan(threshold: Long): Int =
        runBlockingOnIo { dao.deleteOlderThan(threshold) }

    /** 调度日志总条数。 */
    fun count(): Int = runBlockingOnIo { dao.count() }

    // ── 内部 ──

    /** 工具调用明细列表 → JSON 数组字符串。 */
    private fun toolCallsToJson(toolCalls: List<ToolCallRecord>): String {
        val arr = JSONArray()
        for (c in toolCalls) {
            arr.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("args", truncate(c.args, 500))
                    put("result", truncate(c.result, 500))
                    put("elapsedMs", c.elapsedMs)
                    put("ok", c.ok)
                }
            )
        }
        return arr.toString()
    }

    /** Rust 事件流 → JSON 数组字符串（bubble/sticker 内容截断）。 */
    private fun eventsToJson(events: List<AgentEvent>): String {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject().apply {
                    put("kind", e.kind)
                    put("text", truncate(e.text, 200))
                    put("extra", truncate(e.extra, 200))
                }
            )
        }
        return arr.toString()
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "…"

    /** 对齐 MemoryStoreImpl 的 IO 模式：同步调用内切 IO 线程。 */
    private fun <T> runBlockingOnIo(block: suspend () -> T): T =
        runBlocking { withContext(Dispatchers.IO) { block() } }
}

/** 一次工具调用的明细（AgentToolHost.execute 内采集）。 */
data class ToolCallRecord(
    val name: String,
    val args: String,
    val result: String,
    val elapsedMs: Long,
    val ok: Boolean,
)
