package com.yunian.ai.common

object EnvAnchorCooldown {

    fun isCoolingDown(
        lastAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = ChatConstants.ENV_ANCHOR_COOLDOWN_MS,
    ): Boolean {
        if (lastAtMs <= 0L) return false
        if (cooldownMs <= 0L) return false
        return nowMs - lastAtMs < cooldownMs
    }

    fun looksLikeEnvCare(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        val keywords = listOf(

            "睡", "早点休息", "早休息", "别熬夜", "熬夜", "还不睡", "该睡了",
            "去睡", "睡觉", "晚安", "好困", "休息吧", "别太晚",

            "吃饭", "吃了没", "吃过了", "记得吃饭", "有没有吃饭", "饿不饿",
            "午饭", "晚饭", "早餐", "吃点东西", "别空腹",

            "到家", "回家了", "在路上", "出门了没", "安全到家", "到了吗",

            "现在都", "都几点了", "这么晚了", "这么早", "注意身体", "多喝水",
            "天气", "降温", "加衣服",
        )
        return keywords.any { t.contains(it) }
    }

    fun recentAiHasEnvCare(
        aiTexts: Iterable<String>,
        lookback: Int = ChatConstants.ENV_ANCHOR_RECENT_LOOKBACK,
    ): Boolean {
        return aiTexts
            .asSequence()
            .take(lookback.coerceAtLeast(0))
            .any { looksLikeEnvCare(it) }
    }

    fun allowEnvAnchor(
        lastAtMs: Long,
        recentAiTexts: Iterable<String>,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = ChatConstants.ENV_ANCHOR_COOLDOWN_MS,
    ): Boolean {
        if (isCoolingDown(lastAtMs, nowMs, cooldownMs)) return false
        if (recentAiHasEnvCare(recentAiTexts)) return false
        return true
    }

    fun buildCooldownDirective(allowEnvAnchor: Boolean): String {
        if (allowEnvAnchor) return ""
        return """
=== 环境关心冷却中 ===
- 本轮禁止主动提时间/时段、催睡、问吃了没、问到家了没、念天气日程等同类关心。
- 只跟用户话题或按人设轻聊；用户明确问时间/日期时再答。
- 禁止把同一关心当背景音乐再扫一遍。
""".trimIndent()
    }

    fun buildProactiveEnvPolicy(allowEnvAnchor: Boolean): String {
        return if (allowEnvAnchor) {
            """
主动环境策略：本条相当于 OPENING/重连，环境信息最多轻提一次，也可按人设完全不提。
若决定提，只一句带过，不要展开成催睡/问吃/到家任务清单。
""".trimIndent()
        } else {
            """
主动环境策略：环境关心冷却中或最近已提过同类。
本轮禁止再提睡/吃/到家/报时/天气关心；只做话题延续或情绪轻触，不要扫深夜安全机制。
""".trimIndent()
        }
    }
}
