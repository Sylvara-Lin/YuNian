package com.yunian.ai.agent.audit

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.uniffi.PromptFragmentSummary
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.PromptAuditDao
import com.yunian.ai.database.model.PromptAuditEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 提示词编排审计落库（只写 + 近况查询 + 保留窗口清理）。
 *
 * 对齐编排方案 v3「可观测性」原则与 MemoryStoreImpl 的 IO 模式：
 * - 决策全在 Rust（`PromptOrchestrator.dryRun` 产出片段摘要），本类仅做序列化 + 纯 IO；
 * - 只读审计数据，不参与 LLM 请求与决策；
 * - 保留窗口由调用方（feature 层）按需调用 [deleteOlderThan] 清理。
 */
class PromptAuditRecorder(context: Context) {

    companion object {
        private const val TAG = "PromptAuditRecorder"
    }

    private val dao: PromptAuditDao =
        AppDatabase.getDatabase(context.applicationContext).promptAuditDao()

    /**
     * 审计写队列（P2-8）：record() 仅入队立即返回，落库在后台单线程执行，
     * 不阻塞 LLM 回合主流程；查询类方法保持同步。
     */
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agent-prompt-audit-writer").apply { isDaemon = true }
    }

    /**
     * 记录一次 Agent 回合审计。
     *
     * @param companionId 单聊陪伴者 id（群聊传 null）
     * @param groupId 群聊 id（单聊传 null）
     * @param sessionId 会话 id（conversationId），关联同一对话的连续回合
     * @param fragments 该回合实际注入的片段摘要（dryRun 产出，不含正文）
     * @param roundsUsed 实际使用回合数（TurnStateController 统计）
     * @param systemPromptHash 最终 system prompt 的 SHA-256 指纹（空则本类计算）
     * @param toolNames 已注册工具名列表
     */
    fun record(
        companionId: Long?,
        groupId: Long?,
        sessionId: String?,
        fragments: List<PromptFragmentSummary>,
        roundsUsed: Int,
        systemPromptHash: String,
        toolNames: List<String>,
    ) {
        val entity = PromptAuditEntity(
            companionId = companionId,
            groupId = groupId,
            sessionId = sessionId,
            fragmentsJson = fragmentsToJson(fragments),
            roundsUsed = roundsUsed,
            systemPromptHash = systemPromptHash,
            toolNames = toolNames.joinToString(","),
        )
        // P2-8：异步落库（fire-and-forget，失败仅记日志，不影响主流程）
        writeExecutor.execute {
            try {
                runBlockingOnIo { dao.insert(entity) }
            } catch (t: Throwable) {
                Log.e(TAG, "record failed", t)
            }
        }
    }

    /** 最近 N 条审计（时间倒序）。 */
    fun recent(limit: Int = 50, offset: Int = 0): List<PromptAuditEntity> =
        runBlockingOnIo { dao.recent(limit, offset) }

    /** 同一会话的连续回合（时间正序）。 */
    fun bySession(sessionId: String): List<PromptAuditEntity> =
        runBlockingOnIo { dao.bySession(sessionId) }

    /** 删除早于 threshold(ms) 的记录，返回删除条数。 */
    fun deleteOlderThan(threshold: Long): Int =
        runBlockingOnIo { dao.deleteOlderThan(threshold) }

    /** 审计总条数。 */
    fun count(): Int = runBlockingOnIo { dao.count() }

    // ── 内部 ──

    /** PromptFragmentSummary 列表 → JSON 数组字符串（字段名对齐 Rust 侧）。 */
    private fun fragmentsToJson(fragments: List<PromptFragmentSummary>): String {
        val arr = JSONArray()
        for (f in fragments) {
            arr.put(
                JSONObject().apply {
                    put("id", f.id)
                    put("source", f.source)
                    put("layer", f.layer.toInt())
                    put("lifetime", f.lifetime)
                    put("chars", f.chars.toInt())
                }
            )
        }
        return arr.toString()
    }

    /** 对齐 MemoryStoreImpl 的 IO 模式：同步调用内切 IO 线程。 */
    private fun <T> runBlockingOnIo(block: suspend () -> T): T =
        runBlocking { withContext(Dispatchers.IO) { block() } }
}
