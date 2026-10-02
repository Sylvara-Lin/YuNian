package com.yunian.ai.agent.activity

import com.yunian.ai.agent.host.ToolCallPhase
import com.yunian.ai.agent.host.ToolCallProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

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
 * 「当前 AI 活动」的最小事实快照（单个工具调用）。
 *
 * 归属 core:agent：只承载**事实**（工具名 / 状态 / 参数摘要 / 时间 / 回合），
 * 不含展示语义；友好名映射见 `com.yunian.ai.domain.ToolFriendlyNames`，渲染由订阅方（feature）负责。
 *
 * @property callId 本次执行的自增序号（RUNNING→终态同 id）。
 * @property toolName 工具原始名。
 * @property argsSummary 参数摘要（单行、已截断）。
 * @property phase 执行阶段。
 * @property startedAtMs 开始时间戳（毫秒）。
 * @property updatedAtMs 本快照更新时间戳（毫秒；用于「最新」判定与陈旧过滤）。
 * @property turnId 所属回合 id（[AiActivityBus.beginTurn] 分配；无回合时为 0）。
 */
data class AiActivityState(
    val callId: Long,
    val toolName: String,
    val argsSummary: String,
    val phase: AiActivityPhase,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val turnId: Long,
)

/**
 * 「回合 + 当前活动」的原子快照：悬浮窗据 [turnId] 判断回合是否仍在进行，据 [activity] 渲染内容。
 *
 * 用**单一** StateFlow 承载二者（而非两条独立流），避免「回合已结束」与「最后一个活动」
 * 两条流先后到达造成的竞态（例：endTurn 后仍应短暂展示最后一个「完成」）。
 *
 * @property turnId 进行中的回合 id；`null` = 当前无进行中回合。
 * @property activity 本回合（或最近一次）工具活动；无则为 null。
 */
data class AiTurnState(
    val turnId: Long?,
    val activity: AiActivityState?,
)

/**
 * 进程级「当前 AI 活动」总线：core:agent 暴露的唯一事实源，可被多处（feature）观察。
 *
 * **为何需要它**：工具进度原先只在 `feature:chat` 内部被消费（过程卡片），而悬浮窗位于
 * `feature:skills`，两者**不能互相依赖**。本总线放在 core:agent，由 `AgentToolHost` 的进度
 * 回调在每次工具执行开始/结束时写入；`feature:chat` 仍走自己的 `onProgress`（卡片），
 * `feature:skills` 订阅本总线（悬浮窗）——互不耦合，且覆盖**所有** AgentToolHost 调用方。
 *
 * **回合语义（Task #4）**：粒度从「单个工具」提升到「回合」，使悬浮窗**回合内常驻**、
 * 工具之间不闪断。回合由 [beginTurn] / [endTurn] 界定，**唯一调用点是 `AgentFacade.runTurn` /
 * `runTurnStream`**（所有路径的唯一收口），并以 [withTurn] 的 `try/finally` 保证
 * **endTurn 一定被调用**（异常 / 取消路径亦然，否则悬浮窗会永久滞留）。
 *
 * 线程安全：[AgentToolHost] 在 Rust 回调线程上同步调用 [onProgress]；内部以锁保护并发写，
 * StateFlow 在锁外发布（避免在持锁期间触发订阅方回调）。
 *
 * 去重说明：`StateFlow` 会**去重相等值**——本类用 `updatedAtMs`（毫秒时间戳）保证同一 callId
 * 的 RUNNING→终态是「不同值」而必然发布。
 */
object AiActivityBus {

    /** 追踪上限：仅用于防止异常场景下 Map 无限增长（正常一回合工具数为个位数）。 */
    private const val MAX_TRACKED = 16

    /** 无回合归属的活动（异常/未 beginTurn 时兜底，正常不应出现）。 */
    const val NO_TURN = 0L

    private val lock = Any()

    /** callId → 最新快照（同一 callId 覆写）。 */
    private val activities = LinkedHashMap<Long, AiActivityState>()

    /** 当前进行中的回合 id（null = 无）。仅持 [lock] 读写。 */
    private var activeTurnId: Long? = null

    /** 回合 id 自增序列（跨回合唯一）。 */
    private val turnSeq = AtomicLong(0L)

    private val _state = MutableStateFlow(AiTurnState(turnId = null, activity = null))

    /** 回合 + 当前活动的原子快照。订阅方据此决定显示/隐藏与内容。 */
    val state: StateFlow<AiTurnState> = _state.asStateFlow()

    /** 分配一个新的回合 id（调用方在 [beginTurn] / [withTurn] 前获取）。 */
    fun nextTurnId(): Long = turnSeq.incrementAndGet()

    /**
     * 标记一个回合开始，并清空上一回合遗留活动（避免旧回合活动串到新回合）。
     *
     * 必须在任何情况下都不抛出（调用方位于 Agent 回合入口，异常会污染回合）。
     */
    fun beginTurn(turnId: Long) {
        val next = synchronized(lock) {
            activeTurnId = turnId
            activities.clear()
            AiTurnState(turnId = turnId, activity = null)
        }
        _state.value = next
    }

    /**
     * 标记一个回合结束（幂等）。
     *
     * 仅当 [turnId] 与当前进行中的回合一致时才结束；否则忽略（防止乱序/并发的旧回合
     * endTurn 误清掉新回合）。结束后**保留最后一个活动**，供悬浮窗做「结束后的短暂保留」展示。
     */
    fun endTurn(turnId: Long) {
        val next = synchronized(lock) {
            if (activeTurnId != turnId) return@synchronized null
            activeTurnId = null
            AiTurnState(turnId = null, activity = activities.values.maxByOrNull { it.updatedAtMs })
        }
        if (next != null) _state.value = next
    }

    /**
     * 写入一次工具进度（RUNNING / 终态），自动归属到当前进行中的回合。
     *
     * 必须在任何情况下都不抛出（调用方 `AgentToolHost` 位于 UniFFI 回调边界）。
     */
    fun onProgress(progress: ToolCallProgress) {
        val now = System.currentTimeMillis()
        val next = synchronized(lock) {
            val turnId = activeTurnId ?: NO_TURN
            val snapshot = AiActivityState(
                callId = progress.callId,
                toolName = progress.toolName,
                argsSummary = progress.argsSummary,
                phase = progress.phase.toActivityPhase(),
                startedAtMs = progress.startedAtMs,
                updatedAtMs = now,
                turnId = turnId,
            )
            activities[snapshot.callId] = snapshot
            if (activities.size > MAX_TRACKED) {
                // 淘汰最久未更新的（正常路径永不触发）
                activities.values.minByOrNull { it.updatedAtMs }?.let { activities.remove(it.callId) }
            }
            AiTurnState(turnId = activeTurnId, activity = pickLocked())
        }
        // 锁外发布：避免在持锁期间同步触发订阅方回调
        _state.value = next
    }

    /** 清空全部活动与回合（服务重连/异常复位时使用）。 */
    fun clear() {
        val next = synchronized(lock) {
            activeTurnId = null
            activities.clear()
            AiTurnState(turnId = null, activity = null)
        }
        _state.value = next
    }

    /**
     * 以一个回合的边界包裹 [block]：进入即 [beginTurn]，**无论正常返回 / 抛异常 / 被取消**
     * 都在 `finally` 中 [endTurn]。
     *
     * 这是「**endTurn 一定被调用**」的唯一保证点，供 `AgentFacade.runTurn` /
     * `runTurnStream` 复用，避免在多个调用方各写一遍 try/finally 而漏掉异常路径。
     */
    inline fun <T> withTurn(turnId: Long, block: () -> T): T {
        beginTurn(turnId)
        return try {
            block()
        } finally {
            endTurn(turnId)
        }
    }

    /** 需要持 [lock] 调用。存在 RUNNING 时优先「最近开始的 RUNNING」，否则「最近更新的」。 */
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
