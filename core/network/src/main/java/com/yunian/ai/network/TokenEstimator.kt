package com.yunian.ai.network

object TokenEstimator {

    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        var cjkCount = 0
        var otherCount = 0
        for (ch in text) {
            if (isCjk(ch)) cjkCount++
            else otherCount++
        }

        val estimate = (cjkCount * 1.5 + otherCount * 0.25).toInt()
        return maxOf(1, estimate)
    }

    fun estimate(messages: List<Message>): Int {
        var total = 0
        for (msg in messages) {

            total += 4
            msg.content?.let { total += estimate(it) }
            msg.reasoning_content?.let { total += estimate(it) }
        }

        return total + 3
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||
               (code in 0x3040..0x309F) ||
               (code in 0x30A0..0x30FF) ||
               (code in 0xAC00..0xD7AF) ||
               (code in 0x3400..0x4DBF) ||
               (code in 0xF900..0xFAFF)
    }
}
