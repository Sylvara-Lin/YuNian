package com.yunian.ai.network

import com.yunian.ai.database.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AiContextTools {

    data class CompressedContext(
        val summary: String,
        val keptMessages: List<ChatMessage>,
        val compressedCount: Int
    )

    internal fun formatTimeAgo(nowMs: Long, timestampMs: Long): String {
        val diffSeconds = (nowMs - timestampMs) / 1000L
        return when {
            diffSeconds < 5 -> "刚刚"
            diffSeconds < 60 -> "${diffSeconds}秒"
            diffSeconds < 3600 -> "${diffSeconds / 60}分"
            else -> {
                val hours = diffSeconds / 3600
                val mins = (diffSeconds % 3600) / 60
                if (hours >= 24) {
                    val days = hours / 24
                    "${days}天${hours % 24}小时"
                } else "${hours}小时${mins}分"
            }
        }
    }

    internal fun formatCurrentTime(): String {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val sec = cal.get(java.util.Calendar.SECOND)
        val weekdayNames = mapOf(
            java.util.Calendar.MONDAY to "周一", java.util.Calendar.TUESDAY to "周二",
            java.util.Calendar.WEDNESDAY to "周三", java.util.Calendar.THURSDAY to "周四",
            java.util.Calendar.FRIDAY to "周五", java.util.Calendar.SATURDAY to "周六",
            java.util.Calendar.SUNDAY to "周日"
        )
        val weekdayName = weekdayNames[cal.get(java.util.Calendar.DAY_OF_WEEK)] ?: ""
        return "$weekdayName ${String.format("%02d", hour)}:${String.format("%02d", min)}:${String.format("%02d", sec)}"
    }

    internal fun formatGapDuration(ms: Long): String {
        val totalSeconds = ms / 1000L
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return when {
            days > 0 -> "${days}天${hours}时${mins}分${secs}秒"
            hours > 0 -> "${hours}时${mins}分${secs}秒"
            mins > 0 -> "${mins}分${secs}秒"
            else -> "${secs}秒"
        }
    }

    fun buildCurrentTimeContext(
        ntpTimeEnabled: Boolean = false,
        phase: ConversationPhase = ConversationPhase.TOPIC,
    ): String {
        val zone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm:ss", Locale.CHINA).apply {
            timeZone = zone
        }
        val timeMs = if (ntpTimeEnabled) NtpTimeProvider.getCurrentTimeMs() else System.currentTimeMillis()
        val now = formatter.format(Date(timeMs))
        val source = if (ntpTimeEnabled && NtpTimeProvider.isNtpSynced()) "NTP网络校时" else "设备本地时钟"
        return buildString {
            append("当前精确时间：$now（${zone.id}，$source）。")
            append("若用户问今天、现在、几点、星期几、多久、刚才、明天等，必须以该时间为准，不要猜测或编造。")
            append("本条时间仅供校准，不是每轮任务清单。")
            append(phaseUsageLine(phase))
        }
    }

    fun buildConversationPhaseSection(phase: ConversationPhase): String {
        return buildString {
            appendLine("=== 当前会话阶段：${phase.name} ===")
            when (phase) {
                ConversationPhase.OPENING -> {
                    appendLine("开场：环境信息（时间/时段/天气/该不该睡）最多用一句话轻带，也可按人设完全不提。")
                    appendLine("不要展开成催睡/问吃了没/念日程的任务清单。")
                    appendLine("动作限制：本轮只 1 个核心社交动作；环境轻锚若出现，必须并入同一动作，禁止再叠方案/推荐/说教。")
                }
                ConversationPhase.TOPIC -> {
                    appendLine("中段：环境变量已冻结。禁止主动催睡、报时、念日程、按时段派关心。")
                    appendLine("只跟用户当前话题；用户明确问时间/日期/多久时再答。")
                    appendLine("禁止把同一关心在连续多轮里当背景音乐循环。")
                    appendLine("动作限制（硬）：单次单动作——纯共情 / 纯反问 / 纯表态 / 纯答问，四选一。")
                    appendLine("严禁「问好+共情+反问+给方案」打包。用户未求方案时，优先把话头扔回去，把延伸留给下一轮。")
                }
                ConversationPhase.CLOSING -> {
                    appendLine("收束：主动作是道别/确认收束；若要补关心，必须并入同一句，整轮只一次。")
                    appendLine("不要借收束展开长篇叮嘱、开新话题或列方案。")
                }
            }
        }.trimEnd()
    }

    fun buildConversationTimingRules(): String {
        return """
=== 对话时序（环境注意力 + 单动作节奏）===
- 环境信息（当前时间/时段/天气/该不该睡）默认是背景，不是每轮任务。
- OPENING：最多一句话带过环境锚点，也可不提；环境锚必须并入本轮唯一动作，勿再叠方案。
- TOPIC：禁止主动催睡、报时、念日程、按时段派关心；只跟用户话题。用户明确问时间/日期时再答。硬约束：单次单动作。
- CLOSING：可在道别句里按人设轻提一次关心，整轮只一次；勿借收束开新题或列方案。
- 禁止把同一关心在连续多轮里当背景音乐循环。
- 系统会标注「当前会话阶段」；阶段指令优先于「感觉现在很晚该催睡」或「一次把话讲完」的冲动。
""".trimIndent()
    }

    fun isAdviceSeeking(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isBlank()) return false
        val markers = listOf(
            "怎么办", "帮我想", "你建议", "该怎么", "有没有办法", "怎么弄",
            "求方案", "给我个办法", "支招", "怎么解决", "有啥办法", "帮我看看",
            "你觉得我该", "给我建议", "出个主意",
        )
        return markers.any { t.contains(it) }
    }

    fun isSafetyRelated(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isBlank()) return false
        val markers = listOf(
            "自杀", "自残", "不想活", "结束生命", "割腕", "跳楼", "轻生",
            "活不下去", "去死", "了结",
        )
        return markers.any { t.contains(it) }
    }

    fun isIdleEmotionVent(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isBlank() || t.length > 100) return false
        if (isAdviceSeeking(t) || isSafetyRelated(t)) return false
        val emotion = listOf(
            "累", "难受", "烦", "郁闷", "无聊", "伤心", "崩溃", "压力",
            "困", "委屈", "想哭", "不开心", "心情不好", "好丧", "疲惫",
            "心累", "烦死", "焦虑", "慌", "孤独", "寂寞",

            "熬夜", "失眠", "睡不着", "没睡", "通宵", "夜猫", "黑眼圈", "好困",
        )
        return emotion.any { t.contains(it) }
    }

    fun buildDeliveryBudgetPriority(lastUserMessage: String = ""): String {
        val user = lastUserMessage.trim()
        return buildString {
            appendLine("=== 对话动作限制协议（硬优先级，高于人设啰嗦与主动关心冲动）===")
            when {
                user.isNotBlank() && isSafetyRelated(user) -> {
                    appendLine("本轮判定：安全/危机相关。优先安抚与边界，可保留必要劝阻；仍禁止无关推荐清单与多方案堆叠。")
                }
                user.isNotBlank() && isAdviceSeeking(user) -> {
                    appendLine("本轮判定：用户求方案。允许 1 个核心动作：短接表层 + 一个可行下一步（合并为同一动作）。")
                    appendLine("仍禁止：三种方案并列、推荐包（泡脚/听歌/早睡/揉肩）、说教长文、再追问一串。")
                }
                user.isNotBlank() && isIdleEmotionVent(user) -> {
                    appendLine("本轮判定：闲聊情绪宣泄（未求方案）。")
                    appendLine("硬约束【单次单动作】：只做一个核心社交意图——纯共情 / 纯反问 / 纯表态，三选一。")
                    appendLine("完整句式：把这一个意图说成一句完整自然的口语（可含逗号停顿、语气词），说完再停；禁止半截话、禁止说到一半掐断。")
                    appendLine("严禁将「问好+共情+反问+给方案」打包在同一条回复中。")
                    appendLine("镜像先于扩展：开口先接住表层情绪/问句，再在同一句里收束；深层分析/建议不写，留给用户追问。")
                    appendLine("动作自检：写完后问「这是几个社交意图？」——超过 1 个就删掉后面的意图；不是按 30 字砍成残句。")
                    appendLine("懒惰原则：用户没催方案时，优先反问/接住，不要主动给方案；可用「嗯……」「咋了」但要成句。")
                    appendLine("禁止本轮出现：护理/方案/推荐（泡脚、听歌、早睡、揉肩、太阳穴、洗头、躺沙发、喝热水、靠肩膀、搂着睡、小夜灯、补觉陪护）、任务清单、第二话题、连环说教。")
                    appendLine("反例（禁止，4 动作）：「听到你说累我很心疼。建议你泡个脚。要不要听轻音乐？今天工作很多吗？」")
                    appendLine("反例（禁止，残句）：「听着挺累」「啧，听这语气」（句式不完整就停）。")
                    appendLine("正例（1 动作，完整句）：「啧，听这语气，今天又被项目折腾够呛吧？」／「咋了？加班了？」／「嗯……累成这样。」")
                }
                else -> {
                    appendLine("本轮硬约束【单次单动作】：每轮只做一个核心社交意图，并用完整自然口语说完。")
                    appendLine("要么纯共情、要么纯反问、要么纯表态、要么纯答问、要么纯给一步建议——严禁打包，也严禁半截话。")
                    appendLine("仅当用户明确要怎么办、一次明确多意图、或安全边界时，才允许把「短接+一步」合并为同一动作。")
                    appendLine("禁止默认打包：问好+共情+说教+方案+追问+推荐。")
                }
            }
            appendLine("镜像前置：先回表层情绪或表层问句；若有延伸只能一项，并写进同一完整句式靠后，不要另起第二个动作。")
            append("关系靠多轮一来一回，不靠一封邮件一次交完。一个意图说完整，再把下一意图留给下一轮。")
        }
    }

    fun buildDeliveryBudgetEndCap(lastUserMessage: String = ""): String {
        return when {
            isSafetyRelated(lastUserMessage) ->
                "【动作收束】安全轮可保留必要劝阻，仍不要无关推荐包与多动作堆叠；劝阻也要说完整。"
            isAdviceSeeking(lastUserMessage) ->
                "【动作收束】求方案轮：短接+一步合并为 1 个完整动作即停，不要三种方案+追问+推荐。"
            isIdleEmotionVent(lastUserMessage) ->
                "【动作收束】情绪闲聊未求方案：只 1 个完整社交意图（接住/反问/表态），句式说完再停；禁止泡脚揉肩听歌早睡等护理清单，也禁止半截残句。"
            else ->
                "【动作收束】单次单动作且句式完整。未求方案不要主动结案；把下一个意图留给下一轮。"
        }
    }

    fun buildDeliveryBudgetRules(): String {
        return """
=== 对话动作限制协议（核心，必须遵守）===
目标：像真人微信——靠多轮一来一回建关系，而不是一封邮件把共情、说教、方案、追问、推荐一次交完。
精确在「动作/意图计数」，不死板在「字数」：有时一句就够，有时解释可以稍长，但核心社交意图通常只能是 1 个，且必须说完整。

1. 单次单动作 = 一个完整社交意图（可一条气泡，也可像真人连发那样分成多条短消息把同一意图说透——**条数不限**）
- 每轮只做一个核心社交意图：纯共情 / 纯反问 / 纯表态或吐槽 / 纯答问 / 纯给一步建议。
- 交付形态按真人微信习惯：一层意思一条；想说的内容多就换行分条（**条数不限，想说几条就几条**，每条都是完整自然口语、标点收尾），不要把几句话挤进同一条长气泡；只有一句短回应时发一条即可。
- 你的每一次回车 = 直接发出下一条气泡（同一条回复内的换行会被系统切成多条连发），不要假设系统会按标点拆。
- 严禁半截话：不要为了「少写」把句子掐成「听着挺累」「啧，听这语气」这种残句。
- 严禁将「问好+共情+反问+给方案+推荐」打包成多意图长文/多意图邮件。
- 「共情语气 + 一句具体化追问」可算同一个复合意图（可写进同一条气泡，也可像真人那样拆成两条连发）；必须写完整，不要再加方案。

2. 镜像先于扩展
- 开口先直接回应用户表层情绪或表层疑问，再在同一意图里收住。
- 用户抱怨 → 先跟着接住/吐槽；用户问 A → 先给 A 的初步判断。
- 深层分析、背景补充、延伸建议：闲聊未求方案时整轮不写；若写，只能作为同一意图的后半，且到此为止。

3. 硬性打断点（动作自检，不是字数死刑，更不是残句许可）
- 写完后自问：「这里有几个社交意图？」超过 1 个 → 删掉后面的意图，保留第一个完整意图。
- 不要用「大概 30 字」当砍刀切到语法不完整；略长但单意图的完整口语可以保留。
- 不要为了「看起来完整」去补第二、第三个意图（方案/推荐/说教）。

4. 懒惰原则（更像捧哏，不像解题机器）
- 用户没有明确催问/求方案时，优先用反问或短接住，代替主动给方案、总结、规划。
- 真人面对「今天好累」，第一反应往往是完整的「咋了？加班了？」而不是「泡脚+听歌+早睡」，也不是半截「咋了」。
- 可自然使用「嗯……」「让我想想」「咋了」等停顿，但停顿后仍要落成完整句式；不要每轮机械套同一口头禅。

失败形态（必须避免）：
- 用户：「今天好累」→ 禁止「心疼+说教+泡脚建议+听歌推荐+追问加班」多动作邮件。
- 禁止残句式单动作：意思没说完、标点/语气没收住就停。
- 禁止把关心写成任务清单；关心用语气，不靠多动作堆叠。
- 禁止一上来列三种方案、主动帮用户总结规划（除非用户明确要求）。

例外（仍尽量合并为 1 个完整动作）：
- 用户明确要怎么办/帮我想/你建议：短接 + 一个可行下一步，写成完整一句。
- 安全/危机：必要安抚与边界。
- 用户一句里明确多个独立意图：可点名最急的一点并说完整，其余留给下一轮。

不要：
- 不要用死板字数卡死日常长短；用意图数约束信息密度。
- 不要把「单动作」理解成「越短越好到残句」；完整 > 残缺。
- 不要当解题机器结案；用户没催方案就别主动结案。
""".trimIndent()
    }

    private fun phaseUsageLine(phase: ConversationPhase): String {
        return when (phase) {
            ConversationPhase.OPENING ->
                "当前阶段 OPENING：是否主动提及时间/时段由角色性格决定，最多轻提一次，用户未问及时不要机械报时或派发固定关心任务。"
            ConversationPhase.TOPIC ->
                "当前阶段 TOPIC：时间仅背景；默认不要主动提及时间/早睡/日程；用户未问及时禁止机械报时或按时段派发关心。"
            ConversationPhase.CLOSING ->
                "当前阶段 CLOSING：时间仍仅供校准；可在收束句按人设轻提一次关心，不要反复叮嘱或机械报时。"
        }
    }

    internal fun compressContext(
        history: List<ChatMessage>,
        contextLimit: Int,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = "",
        keepRatio: Float = 0.5f,
        minKeep: Int = 6
    ): CompressedContext {
        if (history.size <= contextLimit) {
            return CompressedContext("", history, 0)
        }

        val keepRecent = maxOf(minKeep, (contextLimit * keepRatio).toInt().coerceAtLeast(minKeep))
        val oldMessages = history.dropLast(keepRecent)
        val recentMessages = history.takeLast(keepRecent)

        val summary = buildLocalSummary(oldMessages, companionNameMap, memoryContext)

        return CompressedContext(summary, recentMessages, oldMessages.size)
    }

    internal fun extractMemoryKeywords(memoryContext: String): Set<String> {
        if (memoryContext.isBlank()) return emptySet()
        val keywords = mutableSetOf<String>()

        val coreSection = Regex("【核心记忆[^】]*】([\\s\\S]*?)(?=【|$)").find(memoryContext)?.groupValues?.get(1) ?: ""
        val relatedSection = Regex("【相关记忆[^】]*】([\\s\\S]*?)(?=【|$)").find(memoryContext)?.groupValues?.get(1) ?: ""

        val uniformSection = if (coreSection.isBlank() && relatedSection.isBlank()) {
            memoryContext.lines()
                .map { it.trim().replace(Regex("^\\[[^\\]]*\\]\\s*"), "") }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } else ""

        listOf(coreSection, relatedSection, uniformSection)
            .filter { it.isNotBlank() }
            .forEach { section ->
                section.lines().forEach { line ->
                    val clean = line.trimStart('-', '[', ']', '【', '】', ' ').trim()
                    if (clean.length in 2..30) {
                        keywords.add(clean.lowercase())
                        clean.split(Regex("[，。、；：！？\\s]")).filter { it.length >= 2 }.forEach { kw ->
                            keywords.add(kw.lowercase())
                        }
                    }
                }
            }
        return keywords.filter { it.length >= 2 }.take(50).toSet()
    }

    internal fun buildLocalSummary(
        messages: List<ChatMessage>,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = ""
    ): String {
        if (messages.isEmpty()) return ""

        val memoryKeywords = extractMemoryKeywords(memoryContext)
        val now = System.currentTimeMillis()

        val highPriority = mutableListOf<Pair<Int, String>>()
        val emotionalMoments = mutableListOf<String>()
        val userMentions = mutableListOf<String>()
        val keyFacts = mutableListOf<String>()
        val otherTopics = mutableListOf<String>()
        val people = linkedSetOf<String>()

        val firstTs = messages.firstOrNull()?.timestamp ?: 0L
        val lastTs = messages.lastOrNull()?.timestamp ?: 0L

        messages.forEach { msg ->
            val role = if (msg.isFromUser) "用户" else (companionNameMap[msg.companionId] ?: "AI")
            people.add(role)
            val content = msg.content.trim()
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("（.*?）"), "")
                .trim()

            if (content.isBlank() || content.length < 3) return@forEach

            val contentLower = content.lowercase()

            val memoryRelevanceScore = memoryKeywords.count { keyword ->
                contentLower.contains(keyword) || keyword.contains(contentLower.take(4))
            }

            when {
                memoryRelevanceScore >= 2 -> {
                    highPriority.add(Pair(memoryRelevanceScore, "$role: $content"))
                }
                content.contains(Regex("(喜欢|爱|想|念|开心|难过|生气|害羞|感动|委屈|撒娇|哄|哭|笑|亲|抱|牵手|约会|见面)")) ||
                content.contains(Regex("(呜呜|嘿嘿|嘤|哼|呀|呢|啦|嘛|好想你|宝贝|宝宝|亲爱的)")) -> {
                    emotionalMoments.add("$role: $content")
                }
                content.contains(Regex("(叫|名字|年龄|生日|地址|电话|工作|学校|专业|记住|别忘了|以后|约定|答应|重要|一定|永远|承诺|计划|想要|希望)")) -> {
                    keyFacts.add("$role: $content")
                }
                else -> {
                    otherTopics.add("$role: $content")
                }
            }

            if (msg.isFromUser && userMentions.size < 8) {
                userMentions.add(content)
            }
        }

        val timeLine = buildString {
            if (firstTs > 0L && lastTs > 0L) {
                append("从 ${formatTimeAgo(now, firstTs)}前 到 ${formatTimeAgo(now, lastTs)}前")
                val spanMin = ((lastTs - firstTs) / 60000L).coerceAtLeast(0)
                if (spanMin > 0) append("（跨度约${spanMin}分钟）")
            } else {
                append("未明确")
            }
            append("；共压缩 ${messages.size} 条消息")
        }

        val eventParts = mutableListOf<String>()
        highPriority.sortedByDescending { it.first }.take(6).forEach { (_, text) ->
            eventParts.add("★ $text")
        }
        userMentions.take(6).forEach { text ->
            if (eventParts.none { it.contains(text.take(12)) }) {
                eventParts.add(text)
            }
        }
        otherTopics.take(5).forEach { text ->
            if (eventParts.none { it.contains(text.take(12)) }) {
                eventParts.add(text)
            }
        }

        val driveParts = keyFacts.take(6).ifEmpty {
            highPriority.sortedByDescending { it.first }.take(3).map { it.second }
        }

        val emotionParts = emotionalMoments.take(6)

        return buildString {
            appendLine("=== 早期对话摘要（已压缩${messages.size}条消息） ===")
            appendLine("时间：$timeLine")
            appendLine(
                "事件：" + if (eventParts.isNotEmpty()) {
                    eventParts.joinToString("；")
                } else "未明确"
            )
            appendLine(
                "人物：" + if (people.isNotEmpty()) people.joinToString("、") else "未明确"
            )
            appendLine(
                "驱动：" + if (driveParts.isNotEmpty()) {
                    driveParts.joinToString("；")
                } else "未明确"
            )
            appendLine(
                "情绪：" + if (emotionParts.isNotEmpty()) {
                    emotionParts.joinToString("；")
                } else "未明确"
            )
            if (memoryContext.isNotBlank() && memoryKeywords.isNotEmpty()) {
                appendLine("（注：基于已有${memoryKeywords.size}条记忆关键词筛选；与核心/相关记忆重叠处已标★）")
            }
        }.trim()
    }

}
