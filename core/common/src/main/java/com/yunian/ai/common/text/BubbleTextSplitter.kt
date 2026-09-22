package com.yunian.ai.common.text

/**
 * 气泡文本切分器（AI 自主决策的兜底层）。
 *
 * 设计原则：**AI 敲的每一个回车，都是「想发下一条」的信号**。
 * 拆分单位 = AI 自己写下的换行（`\n`，无论单换行还是空行），绝不按句末标点做句子级切分——
 * 句子级切分会把 AI 想要的「一段连贯叙述」硬拆成多条气泡，破坏真人连发观感；
 * 而只认空行会把 AI 用单换行表达的「分两次发」意图压成一条（「全部塞在一起」的观感）。
 *
 * 内容形态到气泡的映射：
 *  - 整段无换行（一气呵成的故事/长叙述）→ 恰好一条；
 *  - AI 用换行分隔的短句（闲聊两连句）→ 一行一条；
 *  - 段间空行 → 与单换行等价（空行不产生空气泡）；
 *  - Markdown 代码块（\`\`\` 围栏）→ 整块作为一条，围栏内换行不再拆分。
 *
 * 与之配合的另一条显式通道：气泡协议 JSON（BubbleJsonProtocol）→ 由连发循环逐条生成；
 * 协议 text 内若也塞了换行，本层同样按换行放行拆分（AI 的回车意图高于协议格式约束）。
 */
object BubbleTextSplitter {

    /**
     * 气泡数上限默认值：**不设限**（条数由 AI 自己敲的换行决定，想说几条说几条）。
     * 如需人为兜底可显式传 `maxBubbles`（超过时保留前 `maxBubbles - 1` 条、尾段拼接为最后一条）。
     */
    const val DEFAULT_MAX_BUBBLES = Int.MAX_VALUE

    private const val CODE_FENCE = "```"

    /**
     * 按 AI 自己敲的换行拆分（单 \n 与空行同效）；绝不按句末标点拆分；代码块围栏内不拆。
     *
     * @param text 待切分文本。
     * @param maxBubbles 气泡数上限，默认 [DEFAULT_MAX_BUBBLES]（不限）。
     * @return 气泡列表；至少包含一个元素（空白输入返回 `listOf(text)`）。
     */
    fun splitByParagraphs(text: String, maxBubbles: Int = DEFAULT_MAX_BUBBLES): List<String> {
        if (text.isBlank()) return listOf(text)

        val bubbles = mutableListOf<String>()
        val buf = StringBuilder()
        var inFence = false

        fun flush() {
            val seg = buf.toString().trim()
            if (hasContent(seg)) bubbles += seg
            buf.clear()
        }

        for (line in text.split("\n")) {
            val fence = line.trimStart().startsWith(CODE_FENCE)
            when {
                // 围栏开始：围栏外的普通行先各自冲刷成气泡，随后整段作为一条
                fence && !inFence -> {
                    flush()
                    inFence = true
                    buf.append(line).append('\n')
                }
                // 围栏结束：代码块整体冲刷为一条（围栏内换行不拆）
                fence && inFence -> {
                    inFence = false
                    buf.append(line).append('\n')
                    flush()
                }
                inFence -> buf.append(line).append('\n')
                // 普通行：一行 = 一条候选气泡；空行只尽显间隔（不产生空气泡）
                else -> {
                    buf.append(line)
                    flush()
                }
            }
        }
        // P2-A1：fence 未闭合（奇数个 ``` 行）时，落单围栏起滞留的内容按「fence 从未生效」降级——
        // 视为普通文本逐行冲入气泡（同 else 分支），不再整段吞并为一条留到文末、围栏标记原文展出。
        if (inFence) {
            val stranded = buf.toString()
            buf.clear()
            for (strandedLine in stranded.split("\n")) {
                buf.append(strandedLine)
                flush()
            }
        }
        flush()

        if (bubbles.isEmpty()) return listOf(text)
        if (bubbles.size <= maxBubbles) return bubbles
        return bubbles.take(maxBubbles - 1) + listOf(bubbles.drop(maxBubbles - 1).joinToString("\n"))
    }

    /**
     * 送达前切分：`allowParagraphSplit == false` 时整条不拆（用于文本已被上游明确整装的场景，
     * 避免对单条内容做二次拆分）。
     *
     * @param text 待切分文本。
     * @param allowParagraphSplit 是否允许按换行拆分。
     * @param maxBubbles 气泡数上限。
     */
    fun splitForDelivery(
        text: String,
        allowParagraphSplit: Boolean,
        maxBubbles: Int = DEFAULT_MAX_BUBBLES,
    ): List<String> = if (allowParagraphSplit) splitByParagraphs(text, maxBubbles) else listOf(text)

    /** 段内须含至少一个字母/数字或 CJK 汉字，纯标点/空白段被丢弃。 */
    private fun hasContent(s: String): Boolean =
        s.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
}
