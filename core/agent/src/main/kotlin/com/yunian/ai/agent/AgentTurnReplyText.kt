package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentEvent

/** 通道不支持气泡/表情事件时的文本适配；不重复追加已作为 bubble 发出的 finalText。 */
internal object AgentTurnReplyText {
    fun resolve(events: List<AgentEvent>, finalText: String, finishedReason: String): String? {
        val visible = events.mapNotNull { event ->
            when (event.kind) {
                "bubble" -> event.text.takeIf { it.isNotBlank() }
                "sticker" -> event.text.takeIf { it.isNotBlank() }?.let { "[$it]" }
                else -> null
            }
        }.joinToString("\n")
        if (finishedReason == "confirm_pending") {
            // 兜底：通道侧确认门会先自动拒绝并重跑；走到这里说明仍无法完成本轮，
            // 必须给用户一个明确交代，不能静默。
            return listOf(visible, "这一步需要你在 App 内确认后才能执行（本次未执行）。").filter { it.isNotBlank() }.joinToString("\n")
        }
        return visible.ifBlank { finalText }.takeIf { it.isNotBlank() }
    }
}
