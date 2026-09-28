package com.yunian.ai.domain.imagegen

/**
 * AI 生图协议：**全链路唯一**的画面描述清洗 / 解析实现。
 *
 * 背景（BUG-1 根因）：模型并不总是按约定输出 `[[生图: 描述]]`，它会模仿历史消息里
 * `[图片]（画面：xxx）` 的写法，直接输出 `（画面：xxx）`。旧的 `stripTags` 只认双方括号
 * 标签，于是画面描述原文被当成回复正文落库并渲染成气泡。
 *
 * 因此这里统一收敛三条规则：
 * 1. 双方括号标签 `[[生图: x]]` / `[[image: x]]` …
 * 2. 括号包裹的画面描述 `（画面：x）` / `(画面:x)` / `【画面：x】` / `[画面：x]` …
 * 3. 独占一行的 `画面：x`
 *
 * 本 object 只依赖 Kotlin 标准库（core:domain 零依赖约束），供 feature:chat、
 * feature:wechat、feature:qqbot 共用，禁止在别处再写一份正则。
 */
object ImageGenProtocol {

    // ------------------------------------------------------------ 匹配规则

    /** 键名候选：`生图` / `画图` / `配图` / `image` / `draw` …（半角或全角冒号均可）。 */
    private const val TAG_KEYS = "生图|画图|配图|生成图|画一张|出图|image|draw|img|picture"

    /** 描述键名候选：`画面` / `画面描述` / `图片描述` / `prompt` … */
    private const val PROMPT_LABELS = "画面描述|画面|图片描述|生图描述|配图描述|出图描述|image\\s*prompt|image|prompt"

    /** 1. `[[生图: 描述]]`（含未闭合容错由调用方保证）。 */
    private val DOUBLE_BRACKET_TAG = Regex(
        pattern = """\[\[\s*(?:$TAG_KEYS)\s*[:：]\s*([^\[\]]+?)\s*\]\]""",
        option = RegexOption.IGNORE_CASE
    )

    /** 2. `（画面：描述）` / `(画面:描述)` / `【画面：描述】` / `[画面：描述]` / `《…》` / `「…」`。 */
    private val BRACKETED_PROMPT = Regex(
        pattern = """[（(\[【《「]\s*(?:$PROMPT_LABELS)\s*[:：]\s*([^（）()\[\]【】《》「」]+?)\s*[）)\]】》」]""",
        option = RegexOption.IGNORE_CASE
    )

    /** 3. 独占一行的 `画面：描述`（允许前置空白或列表符号）。长度下限避免误伤正常句子。 */
    private val STANDALONE_PROMPT_LINE = Regex(
        pattern = """(?m)^[\s>\-*•]*(?:画面描述|画面|图片描述|生图描述|配图描述)\s*[:：]\s*(.{$MIN_STANDALONE_PROMPT_LENGTH,})$"""
    )

    private val MULTI_BLANK_LINE = Regex("\n{3,}")
    private val TRAILING_LINE_SPACE = Regex("[ \t]+\n")

    // ------------------------------------------------------------ 对外 API

    /**
     * 提取回复里的画面描述；三种写法任一命中即返回，都没有返回 null。
     *
     * @param text 模型原始回复
     * @return 去空白后的画面描述，长度不足或没有时返回 null
     */
    fun extractPrompt(text: String): String? {
        if (text.isBlank()) return null
        DOUBLE_BRACKET_TAG.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        BRACKETED_PROMPT.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        STANDALONE_PROMPT_LINE.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.length >= MIN_STANDALONE_PROMPT_LENGTH }
            ?.let { return it }
        return null
    }

    /** 回复里是否夹带了任意写法的画面描述。 */
    fun hasPrompt(text: String): Boolean = extractPrompt(text) != null

    /**
     * 剥离所有画面描述标记，返回可安全展示/落库的文本。
     *
     * 剥离后连续空行会被压成单个空行（与旧 `stripTags` 行为一致）。
     *
     * @param text 模型原始回复
     * @return 已清洗文本；若整条回复只有画面描述则返回空串
     */
    fun sanitizeForDisplay(text: String): String {
        if (text.isBlank()) return text
        var out = DOUBLE_BRACKET_TAG.replace(text, "")
        out = BRACKETED_PROMPT.replace(out, "")
        out = STANDALONE_PROMPT_LINE.replace(out) { match ->
            // 太短的"画面：xx"多半是正常句子（如「画面：很好看」），保留原样避免误删
            val body = match.groupValues.getOrNull(1).orEmpty().trim()
            if (body.length >= MIN_STANDALONE_PROMPT_LENGTH) "" else match.value
        }
        out = TRAILING_LINE_SPACE.replace(out, "\n")
        out = MULTI_BLANK_LINE.replace(out, "\n\n")
        return out.trim()
    }

    /** 整条回复是否"只有"画面描述（此时应显示占位文案而不是原文）。 */
    fun isPromptOnly(text: String): Boolean =
        text.isNotBlank() && sanitizeForDisplay(text).isBlank() && hasPrompt(text)

    /**
     * 注入给模型的生图协议说明；总开关关闭时返回空串（保证零行为变化）。
     *
     * @param enabled 生图总开关
     * @param hasKeywordTrigger 是否配置了关键词触发
     */
    fun systemRules(enabled: Boolean, hasKeywordTrigger: Boolean): String {
        if (!enabled) return ""
        val extra = if (hasKeywordTrigger) {
            "用户也可能直接用「画一张…」「再生成一张」这类说法来要图，此时同样必须输出该标签。"
        } else ""
        return buildString {
            append("当用户要求你画图/生图/配图，或你主动想发一张画面时，")
            append("你必须在回复正文的最后单独输出一行：[[生图: 画面描述]]。")
            append("不要只说「帮你生成」「这就画」这类话却不输出该标签 —— 那样不会真的出图。")
            append("画面描述只写画面本身（主体、场景、风格、构图、光线），不要写对话口吻的文字。")
            append(extra)
            append("不需要配图时，绝对不要输出该标签。")
            // 关键禁令：历史里出现过「（画面：xxx）」的系统注记，模型极易照抄，
            // 导致画面描述原文泄漏成聊天气泡。这里显式禁止任何非标签写法。
            append("除 [[生图: 画面描述]] 这一种写法外，")
            append("严禁在回复中输出「画面：…」或以圆括号/方括号/书名号包裹的画面描述，")
            append("也不要复述系统注记里出现的画面内容。")
        }
    }

    private const val MIN_STANDALONE_PROMPT_LENGTH = 6
}
