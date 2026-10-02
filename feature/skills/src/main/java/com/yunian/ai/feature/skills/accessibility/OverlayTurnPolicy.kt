package com.yunian.ai.feature.skills.accessibility

/**
 * 悬浮窗「回合级」生命周期的纯策略常量与判定（无 Android 依赖，可 JVM 单测）。
 *
 * 悬浮窗已从「单工具级」改为「回合级」：工具之间不再闪断，只有回合结束才隐藏。
 * 具体时序（协程 delay 由 [AiActivityOverlayController] 执行）由本策略的常量界定；
 * 而定性的「何时强制隐藏」判定收敛为可单测的纯函数 [shouldForceHideForIdle]。
 */
internal object OverlayTurnPolicy {

    /**
     * 回合结束后的保留时长：让用户看清最后一个「完成/失败」再消失。
     * 取 1200ms —— 落在「800ms~1.5s」建议区间中段：足够读完一个短状态词，又不会让窗口拖沓。
     */
    const val LINGER_MS = 1_200L

    /**
     * 回合内空闲兜底阈值：**回合已开始、却连续这么久没有任何活动更新、且始终未收到 endTurn**
     * 时，判定为「回合没有正常结束」（异常/被系统杀死/上游漏调 endTurn），强制隐藏悬浮窗，
     * 避免其永久滞留在屏幕上。
     *
     * 取 90_000ms（90s）：单次工具 + 多轮 LLM 往返的正常总耗时远低于此；一旦 90s 内
     * 连一次进度回调都没有，几乎必然是回合未正常收尾，而非工具真的这么慢。
     */
    const val IDLE_TIMEOUT_MS = 90_000L

    /**
     * 是否应因「回合内长时间无活动」而强制隐藏。
     *
     * @param turnActive 触发时刻回合是否仍在进行（`AiActivityBus.state.value.turnId != null`）；
     *                   回合已结束则不适用（那是正常的 linger 隐藏路径）。
     * @param elapsedSinceLastActivityMs 距最后一次活动更新的毫秒数。
     * @return 回合仍在进行且空闲已超阈值 → true。
     */
    fun shouldForceHideForIdle(turnActive: Boolean, elapsedSinceLastActivityMs: Long): Boolean =
        turnActive && elapsedSinceLastActivityMs >= IDLE_TIMEOUT_MS
}
