package com.yunian.ai.common.safety

import kotlin.math.exp
import kotlin.math.ln
import java.security.SecureRandom

object DifferentialPrivacyFilter {

    private val random = SecureRandom()

    var epsilon: Double = 1.0

    var enabled: Boolean = true

    private val piiPatterns = listOf(

        Regex("1[3-9]\\d{9}") to "[PHONE]",

        Regex("\\d{17}[\\dXx]") to "[ID_NUMBER]",

        Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}") to "[EMAIL]",

        Regex("https?://[\\w./?=&%-]+") to "[URL]",

        Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") to "[IP]",

        Regex("\\d{16,19}") to "[CARD_NUMBER]",

        Regex("(?:wxid_|alipay_)[a-zA-Z0-9_-]+") to "[ACCOUNT_ID]",
    )

    private val geoPatterns = mapOf(
        Regex("(?:北京|上海|广州|深圳|杭州|成都|武汉|南京|重庆|天津|苏州|西安|长沙|郑州|青岛|东莞|宁波|佛山|合肥|无锡|厦门|福州|济南|大连|沈阳|昆明|哈尔滨|长春|石家庄|贵阳|南宁|太原|南昌|兰州|乌鲁木齐|呼和浩特|银川|西宁|海口|拉萨)市?") to "[CITY]",
    )

    private val namePattern = Regex(
        "(?:[王李张刘陈杨黄赵周吴徐孙马胡朱郭何罗高林郑梁谢唐许冯宋韩邓彭曹曾田萧潘袁蔡蒋余于杜叶程魏苏吕丁任卢姚沈钟姜崔谭陆范汪廖石金贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵万钱严覃武戴莫孔向汤])(?:[\\u4e00-\\u9fff]{1,2})"
    )

    fun sanitize(text: String): String {
        if (!enabled || text.isBlank()) return text

        val p = randomizationProbability(epsilon)
        var result = text

        for ((pattern, replacement) in piiPatterns) {
            result = pattern.replace(result) { match ->
                if (random.nextDouble() < p) match.value else replacement
            }
        }

        for ((pattern, replacement) in geoPatterns) {
            result = pattern.replace(result) { match ->
                if (random.nextDouble() < p) match.value else replacement
            }
        }

        result = namePattern.replace(result) { match ->
            if (random.nextDouble() < p) match.value else "[NAME]"
        }

        return result
    }

    private fun randomizationProbability(epsilon: Double): Double {
        val e = exp(epsilon)
        return e / (e + 1.0)
    }

    fun estimateEpsilon(keptCount: Int, totalCount: Int): Double {
        if (totalCount == 0 || keptCount == 0 || keptCount == totalCount) return Double.POSITIVE_INFINITY
        val p = keptCount.toDouble() / totalCount
        return ln(p / (1.0 - p))
    }
}
