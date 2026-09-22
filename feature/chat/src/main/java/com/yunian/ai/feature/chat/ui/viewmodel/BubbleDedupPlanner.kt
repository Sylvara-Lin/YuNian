package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.text.DedupGuard

/**
 * 气泡送达前查重规划器（纯逻辑，便于单测）。
 *
 * 单聊补查重的统一口径：把候选气泡逐一与「本轮已发气泡 ∪ 最近 3 条历史 AI 消息」比对，
 * 命中重复即丢弃。若某轮所有候选都被丢弃，则用极简应答池兜底，**绝不让整轮变空**。
 */
internal object BubbleDedupPlanner {

    private const val TAG = "BubbleDedupPlanner"

    /** 全部候选被查重命中时的兜底应答池（池内优先选未重复项）。 */
    val FALLBACK_ACKS = listOf("嗯嗯", "在呢", "怎么啦", "嗯，你说", "我在听")

    /**
     * 规划本轮真正要送达的气泡。
     *
     * @param segments 切分后的候选气泡（有序）。
     * @param window 可变查重窗口（归一化字符串）。命中的候选被丢弃；被接受的候选其归一化值
     *   会追加进 [window]，供同一轮后续送达（如连发气泡逐条送达）继续查重。
     * @return 实际送达的气泡；若全部命中则返回兜底应答单条。
     */
    fun plan(segments: List<String>, window: MutableList<String>): List<String> {
        if (segments.isEmpty()) return segments

        val accepted = mutableListOf<String>()
        val acceptedNorms = mutableListOf<String>()
        for (segment in segments) {
            val norm = DedupGuard.normalize(segment)
            // 无有效内容（纯空白 / 零宽占位）不参与查重、原样保留，避免误伤既有兜底逻辑。
            if (norm.isEmpty()) {
                accepted.add(segment)
                continue
            }
            if (DedupGuard.isDuplicate(norm, window) || DedupGuard.isDuplicate(norm, acceptedNorms)) {
                continue
            }
            accepted.add(segment)
            acceptedNorms.add(norm)
        }

        if (accepted.isNotEmpty()) {
            window.addAll(acceptedNorms)
            return accepted
        }

        // P2-A3(a)：整轮候选全被查重命中、走兜底应答时留痕（dropped 只取前 40 字，避免刷屏）。
        SecureLog.w(TAG, "all candidates dropped, fallback ack used. dropped=${segments.joinToString(" | ").take(40)}")

        // P2-A3(b)：兜底池优先选未重复项；池内全部已在窗口（重复）时留痕后落到默认首项，
        // 保持既有兜底行为不炸。
        val fallback = FALLBACK_ACKS.firstOrNull {
            !DedupGuard.isDuplicate(DedupGuard.normalize(it), window)
        }
        if (fallback == null) {
            SecureLog.w(TAG, "all fallback acks already in window, using default ack")
        }
        val chosen = fallback ?: FALLBACK_ACKS.first()
        window.add(DedupGuard.normalize(chosen))
        return listOf(chosen)
    }
}
