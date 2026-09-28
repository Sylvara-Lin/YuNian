package com.yunian.ai.network.bubble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BubbleJsonProtocol {

    /**
     * 协议启用标记：出现在系统提示词中即代表本轮走「气泡协议模式」。
     * [com.yunian.ai.network.AiService.streamMessage] 用它推导 `preserveRaw`，
     * 从而无需改动 [com.yunian.ai.domain.AiServiceProvider.streamMessage] 接口。
     *
     * P2-A6：取值为 [systemRules] 首行标题完整串（含 `===` 与括号说明）。嗅探用完整串做
     * contains，避免正常正文/转述中出现的「微信气泡连发协议」字样造成子串误判；
     * [systemRules] 首行直接拼接本常量，保持单一事实源。
     */
    const val PROTOCOL_MARKER = "=== 微信气泡连发协议（本条优先级最高，覆盖上面任何输出格式规则）==="

    private val json = Json { ignoreUnknownKeys = true }

    /** 抠取残缺 JSON 中 "text" 字段值（支持转义）的正则。 */
    private val TEXT_FIELD_REGEX = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * 判断系统提示词是否已启用气泡协议。
     * @param systemPrompt 最终装配好的系统提示词。
     */
    fun isProtocolEnabled(systemPrompt: String): Boolean = systemPrompt.contains(PROTOCOL_MARKER)

    fun systemRules(): String = """
$PROTOCOL_MARKER
你现在处于「真人微信连发」模式：你每一条回复 = 你发出的**一条微信气泡**，就像真人聊天时连发的其中一条。
每一轮你必须且只能输出一个 JSON 对象，严禁输出 JSON 以外的任何文字、解释或标记：

{"text":"本条气泡的内容","continue":true}

字段说明：
- text：本条气泡的完整内容。口语化，像真人发微信，一句话写完并收尾（用 。！？～… 结尾）。不要 markdown、不要括号说明。
- continue：布尔值。true = 你还有同一话题的话没说完，需要继续发下一条；false = 话说完了，到此为止。

判断 continue 的规则（结合你的性格与当前聊天内容）：
1. 你的话还没说完、还有同一话题的内容要接着讲 → true
2. 你的话已经说完整、把话题自然抛回给对方 → false
3. **默认倾向连发**：真人微信聊天经常一次连发好几条短消息（**条数不限**，想说几条就几条）。只要你想说的不止一层意思（例如：回应 + 补充 + 情绪/吐槽 + 反问），就分条发出——本条 continue:true，下一条继续；不要为了省事把好几层意思压成一条长消息。
4. 确实只有一句话、没有第二层意思时（含短肯定：嗯/好/行/哈哈），continue:false；也不要为凑条数把一句完整的话硬拆开。
5. 一段连贯的心里话/叙述写进同一条 text，不要拆开。
6. 用户明确要求你发多条消息（例如「多发几条」「说三条」「多弹几句」）时：必须严格按用户要求的**条数**逐条发出——用户说三条就必须恰好三条，严禁少发、多发，也严禁把多条内容塞进同一条 text。本条只输出当前这一条，continue 取决于「用户要求的剩余条数是否还没发完」：没发完 = true，发完 = false。

已发出的气泡已追加在对话历史中（assistant 消息），**严禁重复**已说过的内容。
""".trimIndent()

    fun parse(raw: String): BubbleReply? = parseInternal(raw, fallbackToPlainText = true)

    fun parseStrict(raw: String): BubbleReply? = parseInternal(raw, fallbackToPlainText = false)

    /**
     * 协议模式下模型输出非法/畸形 JSON 时的宽容提取：
     * 优先用正则从残缺 JSON 中抠出 "text" 字段的真实内容；若无 text 字段但整体像 JSON 残片，
     * 剥掉 JSON 结构字符（`{}[]`、`"continue": true/false`、多余的引号）；都不像 JSON 则原样返回。
     * 目标：绝不把 `{"text":"…","continue":` 这类残片展示给用户。
     */
    fun extractTextLenient(raw: String): String {
        if (raw.isBlank()) return raw

        // 1. 直接从残缺 JSON 中抠出 "text" 字段（含基本反转义）。
        val match = TEXT_FIELD_REGEX.find(raw)
        if (match != null) {
            val extracted = unescapeJsonString(match.groupValues[1]).trim()
            if (extracted.isNotEmpty()) return orEmptyIfNoContent(extracted)
        }

        // 1b. 防御式兜底：仅当「首行 == text 且 末行 == continue」（骨架两端特征齐备）时，
        //     才判定为被打散的 JSON 骨架并按行剔骨取正文；避免误伤以 "text" 开头的正常多行文本。
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2 && lines.first() == "text" && lines.last() == "continue") {
            val body = lines.subList(1, lines.size - 1)
            val joined = body.joinToString("\n").trim()
            if (joined.isNotEmpty()) return orEmptyIfNoContent(joined)
        }

        // 2. 无 text 字段：若形如 JSON 残片则剥结构字符，避免把协议骨架暴露给用户。
        val trimmed = raw.trimStart()
        val looksLikeJson = raw.contains("\"continue\"") || trimmed.startsWith("{") || trimmed.startsWith("[")
        if (looksLikeJson) {
            var stripped = raw
                .replace(Regex("\"continue\"\\s*:\\s*(true|false)"), "")
                .replace(Regex("[{}\\[\\]]"), "")
                .replace(Regex("^[\"\\s]+"), "")
                .replace(Regex("[\"\\s]+$"), "")
                .trim()
            // 处理 `"text" : 内容` 前缀残留（例如缺失闭合引号的情形）。
            stripped = stripped.replace(Regex("^\"?text\"?\\s*:\\s*"), "").trim()
            stripped = stripped.trim('"').trim()
            // 骨架残留检测（P0-1）：剥完后若不含任何字母/数字/CJK 正文（",`、`{}` 类残壳），
            // 归一为空串——交给上层 aiContent.isBlank() 分支提示「API返回空内容」，
            // 绝不把 JSON 残壳/标点残渣当气泡展出。不得再落回步骤 3 原样返回残壳。
            return orEmptyIfNoContent(stripped)
        }

        // 3. 都不成立 → 原样返回（不制造空消息）。
        return raw
    }

    /**
     * 骨架残留检测：结果不含任何字母/数字/CJK 正文 **或 emoji** 时归一空串（P0-1）。
     *
     * emoji 判定说明（P2-new-1）：😂 等主 emoji 在 astral 平面（U+1F300+），UTF-16 下是
     * 代理对——`Char.code` 是码元值（最大 0xFFFF），`code >= 0x1F000` 恒 false，必须用
     * 高半代理区段 [0xD800, 0xDBFF] 识别；❤☀ 等 BMP 符号再补 [0x2600, 0x27BF]。
     * JSON 残壳字符（`{}[]"',:` 等 ASCII）均不命中，骨架检测语义不变。
     */
    private fun orEmptyIfNoContent(s: String): String =
        if (s.any {
            it.isLetterOrDigit() ||
                it.code in 0x4E00..0x9FFF ||
                it.code in 0xD800..0xDBFF ||
                it.code in 0x2600..0x27BF
        }) s else ""

    /** JSON 字符串内容的基本反转义（`\n` `\t` `\r` `\b` `\f` `\"` `\\` `\/` `\uXXXX`）。 */
    private fun unescapeJsonString(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(n)
                            i += 2
                        }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun parseInternal(raw: String, fallbackToPlainText: Boolean): BubbleReply? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()

        val jsonCandidate = trimmed
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val start = jsonCandidate.indexOf('{')
        val end = jsonCandidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val jsonBody = jsonCandidate.substring(start, end + 1)
            val parsed = runCatching { json.parseToJsonElement(jsonBody).jsonObject }.getOrNull()
            if (parsed != null) {
                val text = parsed["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val continueChat = parsed["continue"]?.jsonPrimitive?.booleanOrNull ?: false

                if (text.isBlank()) return null

                // P2-A5：JSON 前/后存在非空外围正文时，把外围正文与提取的 text 一并保留
                // （外围正文在前、与 text 以换行分隔），不再静默吞掉。
                // 仅宽容模式（parse）生效；parseStrict 的 JSON 透传行为保持不变。
                if (fallbackToPlainText) {
                    val surrounding = listOf(
                        jsonCandidate.substring(0, start).trim(),
                        jsonCandidate.substring(end + 1).trim()
                    ).filter { it.isNotBlank() }
                    if (surrounding.isNotEmpty()) {
                        return BubbleReply(
                            text = (surrounding + text).joinToString("\n"),
                            continueChat = continueChat
                        )
                    }
                }
                return BubbleReply(text = text, continueChat = continueChat)
            }
        }

        if (fallbackToPlainText && trimmed.length in 1..2000) {
            return BubbleReply(text = trimmed, continueChat = false)
        }
        return null
    }
}

data class BubbleReply(

    val text: String,

    val continueChat: Boolean,
)
