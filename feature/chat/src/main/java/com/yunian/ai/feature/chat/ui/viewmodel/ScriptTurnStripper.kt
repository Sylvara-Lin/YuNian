package com.yunian.ai.feature.chat.ui.viewmodel

/**
 * 角色串线防线（确定性后处理）：剥离 AI 回复里模型自行续写出的「用户回合」脚本内容。
 *
 * 缺陷背景：模型偶发把对话当成「脚本续写」，在一条回复里输出
 * 「用户：xxx ｜ 苏晚：（…）」甚至直接以「你：xxx」开头（诱导源包括人设卡里的
 * 全角竖线脚本样本、上下文里的「发送者：内容」格式与 few-shot 示例）。
 * 提示词约束（禁止自问自答/替用户说话）无法 100% 拦住，此处提供落库/显示前的
 * 确定性兜底：**遇到用户回合标记即截断，其后内容（模型编的用户回合与后续脚本）全部丢弃**。
 *
 * 触发条件（收紧以避免误杀）：
 * - 回合标记只认「用户：」「你：」（全角/半角冒号），且必须出现在
 *   **文本开头、换行后、或 ｜/| 分隔符后**（允许标记前有空白）；
 *   行中间/引号内/普通引用（如「你刚说想我了」「你说'晚安'呀」）一律不触发。
 *
 * 截断策略：
 * - 保留第一个用户回合标记之前的全部内容（AI 自己第一方的话），丢弃其后所有内容；
 * - 截断后若为空（整条回复以用户回合开头，如「你：老婆…｜苏晚：（…）」），
 *   且脚本中存在「AI 自己名字：」开头的回合，则挽救该回合（剥掉名字前缀）——
 *   它通常是模型对用户真实消息的正常应答，比产出空气泡好；
 *   找不到可挽救内容则返回空串，由上层按空回复既有逻辑处理。
 *
 * 自报家门剥离：回复以「AI 自己名字：」开头时仅剥掉该前缀（不截断内容）。
 *
 * 纯函数、无 Android 依赖，可直接单测（见 ScriptTurnStripperTest）。
 */
object ScriptTurnStripper {

    /**
     * 用户回合标记：位于文本开头 / 换行后 / 全角竖线（U+FF5C）或半角竖线后，
     * 紧跟「用户」或「你」+ 冒号（全角：/半角:）。
     * 注意：标记词与冒号之间不允许空白（「你说：」不匹配，「你 ：」按原样保留），进一步压缩误杀面。
     */
    private val TURN_MARKER_REGEX = Regex("(^|[\\n\\uFF5C|])[ \\t\\u3000]*(?:用户|你)[：:]")

    /** 截断后保留段末尾需要清掉的残留分隔符/空白。 */
    private const val TRAILING_TRIM_CHARS = " \t\r\n\u3000\uFF5C|"

    /** 剥离脚本回合，返回 AI 自己第一方的内容（可能为空串）。 */
    fun strip(text: String, aiName: String? = null): String {
        if (text.isBlank()) return text

        val marker = TURN_MARKER_REGEX.find(text)
            ?: return stripSelfNamePrefix(text, aiName)

        // 截断：丢弃用户回合标记及其后全部内容（那都是模型编的脚本）
        // marker.range.first 指向边界字符（\n/｜/|）或文本起点，之前的即 AI 第一方内容
        val kept = text.substring(0, marker.range.first).trimEnd(*TRAILING_TRIM_CHARS.toCharArray())
        if (kept.isNotBlank()) {
            return stripSelfNamePrefix(kept, aiName)
        }

        // 整条回复以用户回合开头 → 截断后为空。挽救脚本里 AI 自己名字开头的回合。
        return salvageOwnTurn(text, aiName)
    }

    /**
     * 挽救「用户回合开头」的脚本里 AI 自己的回合：
     * 找到第一个「AI名字：」回合（文本开头/换行/分隔符后），保留冒号后的内容。
     */
    private fun salvageOwnTurn(text: String, aiName: String?): String {
        val name = aiName?.trim().takeUnless { it.isNullOrEmpty() } ?: return ""
        val ownTurnRegex = Regex("(^|[\\n\\uFF5C|])[ \\t\\u3000]*${Regex.escape(name)}[：:][ \\t\\u3000]*")
        val match = ownTurnRegex.find(text) ?: return ""
        return text.substring(match.range.last + 1).trim()
    }

    /**
     * 剥掉回复开头的「AI名字：」自报家门前缀（仅前缀，不动正文）。
     * 未命中时原样返回（不吞掉原文的空白缩进）。
     */
    private fun stripSelfNamePrefix(text: String, aiName: String?): String {
        val name = aiName?.trim().takeUnless { it.isNullOrEmpty() } ?: return text
        val prefixRegex = Regex("^[ \\t\\u3000]*${Regex.escape(name)}[：:][ \\t\\u3000]*")
        val trimmed = text.trimStart()
        return if (prefixRegex.containsMatchIn(trimmed)) {
            prefixRegex.replaceFirst(trimmed, "")
        } else {
            text
        }
    }
}
