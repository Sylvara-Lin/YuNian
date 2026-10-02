package com.yunian.ai.agent.activity

import com.yunian.ai.agent.host.ToolCallPhase
import com.yunian.ai.agent.host.ToolCallProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 工具执行的阶段（仅最小事实，不含任何 UI 语义，供跨 feature 观察）。
 */
enum class AiActivityPhase {
    /** 正在执行。 */
    RUNNING,

    /** 执行完成（成功）。 */
    DONE,

    /** 执行失败（异常 / 超时 / 被取消）。 */
    FAILED,
}

/**
 * 「当前 AI 活动」的最小事实快照。
 *
 * 归属 core:agent：只承载**事实**（工具 id / 名 / 状态 / 参数摘要 / 时间），
 * 不含展示语义；友好名映射见 [ToolFriendlyNames]，具体渲染由订阅方（feature）负责。
 *
 * @property callId 本次执行的自增序号（RUNNING→终态同 id）。
 * @property toolName 工具原始名。
 * @property argsSummary 参数摘要（单行、已截断）。
 * @property phase 执行阶段。
 * @property startedAtMs 开始时间戳（毫秒）。
 * @property updatedAtMs 本快照更新时间戳（毫秒；用于「最新」判定与陈旧过滤）。
 */
data class AiActivityState(
    val callId: Long,
    val toolName: String,
    val argsSummary: String,
    val phase: AiActivityPhase,
    val startedAtMs: Long,
    val updatedAtMs: Long,
)

/**
 * 进程级「当前 AI 活动」总线：core:agent 暴露的唯一事实源，可被多处（feature）观察。
 *
 * **为何需要它**：工具进度原先只在 `feature:chat` 内部被消费（过程卡片），而悬浮窗位于
 * `feature:skills`，两者**不能互相依赖**。本总线放在 core:agent，由 [AgentToolHost] 的进度
 * 回调在每次工具执行开始/结束时写入；`feature:chat` 仍走自己的 `onProgress`（卡片），
 * `feature:skills` 订阅本总线（悬浮窗）——互不耦合，且覆盖**所有** AgentToolHost 调用方
 * （含后台主动消息路径，其 `onProgress` 未接线，但本总线照样有值）。
 *
 * 血缘：core:agent 的 `ToolCallProgress`（[ToolCallProgress]）→ 本总线的最小事实。
 *
 * 线程安全：`AgentToolHost` 在 Rust 回调线程上同步调用 [onProgress]；内部以锁保护并发写，
 * StateFlow 在锁外发布（避免在持锁期间触发订阅方回调）。
 *
 * 注意：`StateFlow` 会**去重相等值**——本类用 `updatedAtMs`（毫秒时间戳）保证同一 callId
 * 的 RUNNING→终态是「不同值」而必然发布。
 */
object AiActivityBus {

    /** 追踪上限：仅用于防止异常场景下 Map 无限增长（正常一回合工具数为个位数）。 */
    private const val MAX_TRACKED = 16

    private val lock = Any()

    /** callId → 最新快照（同一 callId 覆写）。 */
    private val activities = LinkedHashMap<Long, AiActivityState>()

    private val _current = MutableStateFlow<AiActivityState?>(null)

    /**
     * 当前 AI 活动：存在 RUNNING 时优先给出「最近开始的 RUNNING」；否则给出「最近更新的」；
     * 无任何活动（或已 [clear]）为 null。订阅方据此决定显示/隐藏。
     */
    val current: StateFlow<AiActivityState?> = _current.asStateFlow()

    /**
     * 写入一次工具进度（RUNNING / 终态）。
     *
     * 必须在任何情况下都不抛出（调用方 [AgentToolHost] 位于 UniFFI 回调边界）。
     */
    fun onProgress(progress: ToolCallProgress) {
        val now = System.currentTimeMillis()
        val state = AiActivityState(
            callId = progress.callId,
            toolName = progress.toolName,
            argsSummary = progress.argsSummary,
            phase = progress.phase.toActivityPhase(),
            startedAtMs = progress.startedAtMs,
            updatedAtMs = now,
        )
        val next = synchronized(lock) {
            activities[state.callId] = state
            if (activities.size > MAX_TRACKED) {
                // 淘汰最久未更新的（正常路径永不触发）
                activities.values.minByOrNull { it.updatedAtMs }?.let { activities.remove(it.callId) }
            }
            pickLocked()
        }
        // 锁外发布：避免在持锁期间同步触发订阅方回调
        _current.value = next
    }

    /** 清空全部活动（服务重连/回合切换时复位，避免陈旧态导致悬浮窗卡住）。 */
    fun clear() {
        synchronized(lock) { activities.clear() }
        _current.value = null
    }

    /** 需要持 [lock] 调用。 */
    private fun pickLocked(): AiActivityState? {
        val running = activities.values.filter { it.phase == AiActivityPhase.RUNNING }
        if (running.isNotEmpty()) return running.maxByOrNull { it.startedAtMs }
        return activities.values.maxByOrNull { it.updatedAtMs }
    }

    private fun ToolCallPhase.toActivityPhase(): AiActivityPhase = when (this) {
        ToolCallPhase.RUNNING -> AiActivityPhase.RUNNING
        ToolCallPhase.DONE -> AiActivityPhase.DONE
        ToolCallPhase.FAILED -> AiActivityPhase.FAILED
    }
}
