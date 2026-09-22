package com.yunian.ai.agent.delegation

import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.database.dao.DelegationDao
import com.yunian.ai.database.model.DelegationRecordEntity
import com.yunian.ai.domain.delegation.DelegationCoordinator
import com.yunian.ai.domain.delegation.DelegationRecord
import com.yunian.ai.domain.delegation.DelegationRole
import com.yunian.ai.domain.delegation.DelegationStatus

/**
 * 多 Agent 编排协调器（Room 实现 + 角色注册表 + 子回合执行）。
 *
 * 子回合与主回合串行：主回合结束后由 feature 层调用 [runPendingDelegations]，
 * 此时 AgentRuntime turn_lock 已释放，子回合独立 dispatch（事件账本经 AgentDispatchRecorder 自动入账）。
 */
class DelegationCoordinatorImpl(
    private val dao: DelegationDao,
) : DelegationCoordinator {

    /** 角色注册表（可扩展：设置页/插件注册）。 */
    private val roles: MutableMap<String, DelegationRole> = mutableMapOf(
        "analyst" to DelegationRole(
            name = "analyst",
            systemPrompt = "你是一名资深分析师（子任务 Agent）。只针对委派的任务给出结构化、客观的分析结论，不编造数据，不做无关发挥。回复保持精炼。",
        ),
        "helper" to DelegationRole(
            name = "helper",
            systemPrompt = "你是一名助手（子任务 Agent）。高效完成委派任务并给出可直接使用的结果。回复保持精炼。",
        ),
    )

    fun registerRole(role: DelegationRole) { roles[role.name] = role }
    fun role(name: String): DelegationRole? = roles[name]

    override suspend fun create(role: String, prompt: String, companionId: Long?): DelegationRecord {
        val id = dao.insert(
            DelegationRecordEntity(
                role = role,
                prompt = prompt,
                status = DelegationStatus.PENDING.name,
                companionId = companionId,
            ),
        )
        return dao.byId(id)!!.toDomain()
    }

    override suspend fun get(id: Long): DelegationRecord? = dao.byId(id)?.toDomain()
    override suspend fun pending(): List<DelegationRecord> =
        dao.byStatus(DelegationStatus.PENDING.name).map { it.toDomain() }

    override suspend fun markRunning(id: Long): Boolean {
        val rec = dao.byId(id) ?: return false
        if (rec.status != DelegationStatus.PENDING.name) return false
        dao.update(id, DelegationStatus.RUNNING.name, rec.result, rec.error, rec.dispatchId, rec.completedAtMs)
        return true
    }

    override suspend fun complete(id: Long, result: String, dispatchId: String?) {
        dao.update(id, DelegationStatus.COMPLETED.name, result, "", dispatchId, System.currentTimeMillis())
    }

    override suspend fun fail(id: Long, error: String) {
        dao.update(id, DelegationStatus.FAILED.name, "", error, null, System.currentTimeMillis())
    }

    override suspend fun recent(limit: Int): List<DelegationRecord> =
        dao.recent(limit).map { it.toDomain() }

    /**
     * 执行所有 PENDING 委派（子 Agent 回合，独立 dispatch）。
     * 未知角色按默认助手处理；子回合只传 role systemPrompt，不注入工具。
     */
    suspend fun runPendingDelegations(application: android.app.Application, limit: Int = 4) {
        val pending = pending().take(limit)
        for (record in pending) {
            if (!markRunning(record.id)) continue
            val role = roles[record.role] ?: roles.getValue("helper")
            try {
                val history = org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply { put("role", "system"); put("content", role.systemPrompt) })
                    put(org.json.JSONObject().apply { put("role", "user"); put("content", record.prompt) })
                }.toString()
                val result = AgentFacade.runTurn(
                    request = com.yunian.ai.agent.uniffi.AgentTurnRequest(
                        groupId = null,
                        historyJson = history,
                        tools = emptyList(),
                        maxRounds = 3u,
                        toolChoice = "",
                        stickerProbability = 0u,
                        image = null,
                        systemPrompt = null,
                        companionNameMapJson = null,
                    ),
                    context = application,
                    companionId = record.companionId ?: 0L,
                    toolHost = com.yunian.ai.agent.host.AgentToolHost(application),
                )
                complete(record.id, result.finalText, null)
            } catch (t: Throwable) {
                fail(record.id, t.message ?: t.javaClass.simpleName)
            }
        }
    }
}