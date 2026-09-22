package com.yunian.ai.network

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.database.model.ChatMessage
import java.util.Calendar

enum class ConversationPhase {
    OPENING,
    TOPIC,
    CLOSING,
}

object ConversationPhaseDetector {

    fun detect(
        history: List<ChatMessage>,
        nowMs: Long = System.currentTimeMillis(),
        reopenGapMs: Long = ChatConstants.CONVERSATION_REOPEN_GAP_MS,
    ): ConversationPhase {
        if (history.isEmpty()) return ConversationPhase.OPENING

        val sorted = history
            .sortedBy { it.timestamp }
            .takeLast(ChatConstants.CONVERSATION_PHASE_LOOKBACK)
        if (sorted.isEmpty()) return ConversationPhase.OPENING

        val last = sorted.last()
        val gapMs = (nowMs - last.timestamp).coerceAtLeast(0L)

        if (gapMs >= reopenGapMs || !isSameCalendarDay(last.timestamp, nowMs)) {
            return ConversationPhase.OPENING
        }

        val session = sessionWindow(sorted, nowMs, reopenGapMs)
        val lastUser = sorted.lastOrNull { it.isFromUser }
        if (lastUser != null && looksLikeClosingIntent(lastUser.content)) {
            return ConversationPhase.CLOSING
        }

        val hasAiInSession = session.any { !it.isFromUser }
        if (!hasAiInSession) return ConversationPhase.OPENING

        return ConversationPhase.TOPIC
    }

    private fun sessionWindow(
        sorted: List<ChatMessage>,
        nowMs: Long,
        reopenGapMs: Long,
    ): List<ChatMessage> {
        if (sorted.isEmpty()) return emptyList()
        var start = 0
        for (i in sorted.lastIndex downTo 1) {
            val cur = sorted[i]
            val prev = sorted[i - 1]
            val gap = cur.timestamp - prev.timestamp
            if (gap >= reopenGapMs || !isSameCalendarDay(prev.timestamp, cur.timestamp)) {
                start = i
                break
            }
        }

        val last = sorted.last()
        if (nowMs - last.timestamp >= reopenGapMs) {
            return listOf(last)
        }
        return sorted.drop(start)
    }

    fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = aMs }
        val cb = Calendar.getInstance().apply { timeInMillis = bMs }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    fun looksLikeClosingIntent(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return false

        val weakOnly = setOf(
            "嗯", "嗯嗯", "好", "好的", "哦", "噢", "喔", "行", "知道了", "了解",
            "ok", "OK", "Ok", "嗯哼", "哈哈", "哈哈哈", "？", "?", "…", "...", "……",
        )
        if (t in weakOnly) return false

        val strongClosers = listOf(
            "晚安", "再见", "拜拜", "拜了", "先忙", "先睡", "不聊了", "不说了",
            "下线", "挂了", "回头聊", "明天再聊", "回头再说", "我睡了", "去睡了",
            "要睡了", "困死了", "好困", "睡了哈", "先这样", "就这样吧", "下次聊",
            "bye", "good night", "goodnight",
        )
        val lower = t.lowercase()
        if (strongClosers.any { closer ->
                t.contains(closer, ignoreCase = true) || lower.contains(closer.lowercase())
            }
        ) {
            return true
        }

        val pureShort = listOf("晚安啦", "晚安哦", "晚安呀", "晚安咯", "再见啦", "拜拜啦", "先走了", "我先走了")
        if (t.length <= 16 && pureShort.any { t.contains(it) }) return true

        return false
    }
}
