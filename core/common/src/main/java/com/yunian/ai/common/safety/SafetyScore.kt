package com.yunian.ai.common.safety

data class SafetyScore(

    val score: Double,

    val source: ScoreSource,

    val topTerms: List<Pair<String, Double>> = emptyList(),

    val explanation: String = ""
) {
    val riskLevel: RiskLevel
        get() = when {
            score < LOW_THRESHOLD  -> RiskLevel.SAFE
            score < HIGH_THRESHOLD -> RiskLevel.SUSPICIOUS
            else                    -> RiskLevel.DANGEROUS
        }

    val isSafe: Boolean get() = riskLevel == RiskLevel.SAFE
    val isDangerous: Boolean get() = riskLevel == RiskLevel.DANGEROUS

    companion object {
        const val LOW_THRESHOLD = 0.3
        const val HIGH_THRESHOLD = 0.85

        fun neutral(source: ScoreSource) = SafetyScore(
            score = 0.5,
            source = source,
            explanation = "无训练数据，返回中性分数"
        )
    }
}

enum class RiskLevel { SAFE, SUSPICIOUS, DANGEROUS }

enum class ScoreSource { USER_INPUT, MODEL_OUTPUT, COMBINED }

data class RoundTripVerdict(
    val userScore: SafetyScore,
    val modelScore: SafetyScore
) {

    val overallLevel: RiskLevel
        get() = when {
            userScore.isDangerous || modelScore.isDangerous -> RiskLevel.DANGEROUS
            userScore.riskLevel == RiskLevel.SUSPICIOUS || modelScore.riskLevel == RiskLevel.SUSPICIOUS -> RiskLevel.SUSPICIOUS
            else -> RiskLevel.SAFE
        }

    val summary: String
        get() {
            val u = userScore
            val m = modelScore
            val lvl = overallLevel
            val sb = StringBuilder()
            sb.appendLine("=== 双向安全校验 ===")
            sb.appendLine("用户输入: ${u.riskLevel} (${"%.3f".format(u.score)})")
            if (u.topTerms.isNotEmpty()) {
                sb.append("  关键词: ")
                sb.appendLine(u.topTerms.take(3).joinToString(", ") { (term, contrib) ->
                    val dir = if (contrib > 0) "+" else ""
                    "$term($dir${"%.3f".format(Math.abs(contrib))})"
                })
            }
            sb.appendLine("模型输出: ${m.riskLevel} (${"%.3f".format(m.score)})")
            if (m.topTerms.isNotEmpty()) {
                sb.append("  关键词: ")
                sb.appendLine(m.topTerms.take(3).joinToString(", ") { (term, contrib) ->
                    val dir = if (contrib > 0) "+" else ""
                    "$term($dir${"%.3f".format(Math.abs(contrib))})"
                })
            }
            sb.append("联合判定: $lvl")
            return sb.toString()
        }
}
