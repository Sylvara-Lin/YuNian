package com.yunian.ai.feature.notification

/**
 * AI 主动消息（主动问候 / 追问）的单轮指令构造器。
 *
 * **为何独立成纯函数**：便于单测；并集中承载「多气泡」引导语义。
 *
 * **背景（真机实测复现）**：文本路径 Agent 化迁移时，[CompanionMessageWorker] 沿用了旧的
 * 「只发 1 条 / 优先 1 条」硬限制，且生成收尾把换行压成「，」，导致主动消息**永远只发一句话**。
 * 本构造器补齐与聊天路径 / 旧 [com.yunian.ai.network.AiPromptBuilder] 路径**等价**的多气泡语义：
 * 换行 = 发出下一条气泡，条数由性格与想说的话决定，不硬凑也不封顶，每条为完整口语短句。
 *
 * **与 [NO_PROACTIVE_MARKER] 兼容**：当判定「不该打扰」时**只允许**输出该 marker，
 * 多气泡格式引导不得干扰该分支（prompt 中已明确要求只输出 marker）。
 */
internal object ProactiveMessageInstruction {

    /** 「本轮不发言」标记（与 AiPromptBuilder.NO_PROACTIVE_MARKER 同值）。 */
    const val NO_PROACTIVE_MARKER = "[NO_PROACTIVE]"

    /**
     * 构造单轮指令。
     *
     * @param companionName 角色名（用于「以 X 的身份…」）。
     * @param allowLateNightMessage 是否允许免打扰时段消息；false 时追加禁提睡/吃/报时约束。
     * @param followUp true = 追问（用户未回复）；false = 主动问候（决定是否续聊）。
     */
    fun build(companionName: String, allowLateNightMessage: Boolean, followUp: Boolean): String =
        if (followUp) buildFollowUp() else buildGreeting(companionName, allowLateNightMessage)

    private fun buildGreeting(companionName: String, allowLateNightMessage: Boolean): String = buildString {
        appendLine("以「$companionName」的身份决定是否、以及如何继续刚才的对话。")
        appendLine("先做语义判断（看整段上下文，不要只看最后几个字）：")
        appendLine(" - 若用户此刻明显不想被打扰、对话已自然收束，只输出 $NO_PROACTIVE_MARKER，不要硬聊。")
        appendLine(" - 「晚安/再见/先忙/嗯/好/知道了」等不能单独当作结束标签，要结合前后文理解。")
        appendLine("话题选择（性格优先，禁止机械承接）：")
        appendLine(" - 上一话题已完结或不感兴趣时，可轻转、只回情绪/态度，或输出 $NO_PROACTIVE_MARKER；不要硬续旧话题。")
        appendLine("若决定发消息，要求：")
        appendLine("1. 像真人在微信连发那样说话：口语、自然，不要长文堆共情+方案+大道理，也不要半截残句。")
        appendLine("2. 消息条数不限：换行即下一条。话多就多敲几行（真人会连发），话少一条也行——由你的性格与此刻想说的话决定，不硬凑条数，也不要把全部内容塞进一条。")
        appendLine("3. 不要重新开场、不要念日程；时间只是背景，不要机械报时或按时段派发固定关心任务。")
        appendLine("4. 语气与互动方式严格服从角色性格，不要统一撒娇/催促。")
        appendLine("5. 禁止括号，禁止AI感词汇，禁止说教。")
        if (!allowLateNightMessage) {
            appendLine("6. 当前处于免打扰时段，只做话题延续或情绪轻触，禁止提睡/吃/到家/报时。")
        }
    }.trimEnd()

    private fun buildFollowUp(): String = buildString {
        appendLine("你上一条消息发出后，用户一直没回复。现在由你决定是否追问：")
        appendLine(" - 若判断用户可能在忙、已休息或对话已自然收尾，只输出 $NO_PROACTIVE_MARKER，不要硬催。")
        appendLine(" - 若决定追问：可像真人微信连发那样一次发多条（换行即下一条），条数由你的性格与想说的话决定，不硬凑也不封顶；简短自然，语气严格服从你的性格（黏人可撒娇多戳两句，冷淡/傲娇一条即止）。")
        appendLine(" - 不要重复上一条消息的内容，不要堆叠同一句话，不要说教。")
        appendLine(" - 禁止括号，禁止AI感词汇。")
    }.trimEnd()
}
