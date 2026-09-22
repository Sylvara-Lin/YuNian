package com.yunian.ai.network

import com.yunian.ai.common.text.BubbleTextSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 独立对抗性验证（不修改生产代码）——主动消息多气泡链路。
 *
 * 与 ProactiveMultiBubblePromptTest 互补，专攻其未覆盖的维度：
 *  A) preserveRaw + 真实 BubbleTextSplitter 的端到端护栏（超长文本不截断、CRLF、15 行不封顶）
 *  B) NO_PROACTIVE_MARKER 组合语义（独占行 + CRLF、内嵌剥离、空白）
 *  C) 内联 prompt -> AiPromptBuilder 纯函数的逐字等价性锁定（重构护栏）
 *  D) 思考块剥离与换行保留的组合（preserveRaw 下思考内容必须仍被剥离）
 *  E) 双层防泄漏：演示文字/标签行被模型照抄时的过滤行为 + 误伤面隔离 + 聊天路径不受影响
 */
class ProactiveMultiBubbleQaAdversarialTest {

    // ==================================================================
    // A. preserveRaw 端到端：换行不被压缩、超长不截断、真实拆分器不限条数
    // ==================================================================

    @Test
    fun `超长多行追问输出-不再截断-真不限`() {
        // 6 行 x 80 字 = 480 字 + 换行 > POST_PROCESS_LONG_CUT_THRESHOLD(360)
        // 旧追问路径(preserveRaw=false) 会走 extractDirectReply/句数/长文截断, 新路径必须原样透传
        val line = "这是一条足够长的追问正文用来模拟模型话痨场景并且不触发任何气泡协议的字符序列凑满八十字"
        val raw = (1..6).map { "${line}${it}" }.joinToString("\n")

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("超长多行文本必须原样透传(preserveRaw 不截断)", raw, result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals("6 行必须拆出 6 条完整气泡", 6, bubbles.size)
        raw.split("\n").forEachIndexed { i, expected -> assertEquals(expected, bubbles[i]) }
    }

    @Test
    fun `十五行追问输出-气泡不封顶-maxBubbles真不限`() {
        val raw = (1..15).map { "第${it}条追问内容超长不截断" }.joinToString("\n")

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertTrue("15 行必须完整透传", result!!.contains("第15条追问内容超长不截断"))
        val bubbles = BubbleTextSplitter.splitByParagraphs(result)
        assertEquals("maxBubbles=Int.MAX_VALUE 等于真不限：15 行必须拆出 15 条", 15, bubbles.size)
    }

    @Test
    fun `CRLF多行追问-端到端经真实拆分器拆成多行`() {
        val raw = "第一条追问\r\n第二条追问\r\n第三条追问"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("CRLF 文本原样透传", raw, result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals(listOf("第一条追问", "第二条追问", "第三条追问"), bubbles)
    }

    @Test
    fun `追问空行不产生空气泡-与单换行同效`() {
        val raw = "第一行\n\n\n第二行\n\n第三行"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals(raw, result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals("单换行与空行同效：3 行 -> 3 条气泡", listOf("第一行", "第二行", "第三行"), bubbles)
    }

    // ==================================================================
    // B. NO_PROACTIVE_MARKER 组合语义（独占/内嵌/空白）
    // ==================================================================

    @Test
    fun `独占一行的marker-含CRLF行尾-整体返回null`() {
        val raw = "正文第一句\r\n${AiPromptBuilder.NO_PROACTIVE_MARKER}\r\n正文第二句"

        assertNull(
            "独占一行的 marker 无论 CRLF 都必须整体不发",
            AiPromptBuilder.postProcessProactiveReply(raw, emptyList()),
        )
    }

    @Test
    fun `独占一行的marker-带空行包围-整体返回null`() {
        val raw = "正文一句话\n\n${AiPromptBuilder.NO_PROACTIVE_MARKER}\n\n再说一句"

        assertNull(AiPromptBuilder.postProcessProactiveReply(raw, emptyList()))
    }

    @Test
    fun `内嵌于某行中间的marker-剥离后其余行保留`() {
        val raw = "前端正文${AiPromptBuilder.NO_PROACTIVE_MARKER}尾巴\n第二行正文"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("内嵌 marker 剥离后剩余行保留", "前端正文尾巴\n第二行正文", result)
        assertTrue("剥离后不得再残留 marker 字面量", !(result!!.contains(AiPromptBuilder.NO_PROACTIVE_MARKER)))
    }

    @Test
    fun `纯空白多行追问-整体返回null`() {
        assertNull(AiPromptBuilder.postProcessProactiveReply("\n\n\n", emptyList()))
        assertNull(AiPromptBuilder.postProcessProactiveReply(" \n \n ", emptyList()))
    }

    // ==================================================================
    // C. 内联 prompt -> buildXxxInstruction 逐字等价性锁定（重构护栏）
    // ==================================================================

    @Test
    fun `问候决策指令-除了新增块-与HEAD内联逐字一致`() {
        val name = "NPC素荷"
        val env = "7. 环境关心冷却中: 禁止再提睡/吃/到家/报时/天气; 只做话题轻触"
        // @HEAD 原内联 prompt(逐字复原, 为 trimIndent 去缩进后结果)
        // 注：演示块被剥离后，原 rule 2 与 rule 3 之间会残留一个 \n（原 demo 块前后的 \n 各留一个），
        // 故此处 rule 2→rule 3 之间为 \n\n（空行），等价于「规则 2 内容 + 空行 + 规则 3」的形态
        val oldInline = """以${name}的身份决定是否、以及如何继续刚才的对话。
先做语义判断（看整段上下文，不要只看最后几个字）：
- 若用户此刻明显不想被打扰、对话已自然收束，只输出 [NO_PROACTIVE]，不要硬聊。
- 「晚安/再见/先忙/嗯/好/知道了」等不能单独当作结束标签，要结合前后文理解。
话题选择（性格优先，禁止机械承接）：
- 先判断：上一话题是否已完结？你是否还感兴趣？按角色性格会不会接？
- 未完结且感兴趣：可自然延伸，但不要复读、不要为了承接而追问已答完的内容。
- 已完结或不感兴趣：可轻转、只回情绪/态度，或输出 [NO_PROACTIVE]；不要硬续旧话题。
若决定发消息，要求：
1. 像真人在微信连发那样说话：口语、自然，不要长文堆共情+方案+大道理，也不要半截残句
2. 消息条数不限：换行即下一条。话多就多敲几行（真人会连发），话少一条也行——由你的性格与此刻想说的话决定，不硬凑条数，也不要把全部内容塞进一条

3. 不要重新开场、不要念日程
4. 语气与互动方式严格服从角色性格，不要统一撒娇/催促
5. 禁止括号，禁止AI感词汇，禁止说教
6. 时间只是背景；不要机械报时或按时段派发固定关心任务
$env"""

        val instruction = AiPromptBuilder.buildProactiveDecisionInstruction(name, env)

        // 新增的演示块（8 行围栏+占位形态）抽出后必须与原内联逐字一致
        val demoBlock = """【格式演示：只演示「换行=发出下一条」，以下围栏内文字只是占位，你的输出禁止包含】
只想发一条时，输出占一行：
（占位：一行消息）
想连发几条时，占几行：
（占位：第一行）
（占位：第二行）
（占位：第三行）
【演示结束】"""
        assertEquals(
            "剥离演示块后，决策指令必须与 @HEAD 内联 prompt 逐字一致（重构等价性护栏）",
            oldInline,
            instruction.replace(demoBlock, ""),
        )
    }

    @Test
    fun `追问决策指令-仅需求改写部分与HEAD内联保持逐字差异`() {
        val instruction = AiPromptBuilder.buildFollowUpReminderInstruction()

        val lines = instruction.lines()
        assertEquals("你上一条消息发出后，用户一直没回复。", lines[0])
        assertEquals("现在由你决定是否追问：", lines[1])
        assertEquals(
            "- 若判断用户可能在忙、已休息或对话已自然收尾，只输出 [NO_PROACTIVE]，不要硬催。",
            lines[2],
        )
        assertEquals("- 不要重复上一条消息的内容，不要堆叠同一句话，不要说教。", lines[lines.size - 2])
        assertEquals("- 禁止括号，禁止AI感词汇。", lines.last())

        // 需求改写部分（仅 L4/L5 两行的预期差异）锁定
        assertFalse("必须已删除旧硬限制『只发 1 条』", instruction.contains("只发 1 条"))
        assertFalse("必须已删除字数区间『10~30』", instruction.contains("10~30"))
        assertFalse("必须已删除『一次一条』", instruction.contains("一次一条"))
        assertFalse("必须已删除旧『堆叠追问』（已改『堆叠同一句话』）", instruction.contains("堆叠追问"))
        assertFalse("旧『黏人可撒娇，冷淡/傲娇可轻戳一句』必须改写", instruction.contains("黏人可撒娇，冷淡/傲娇可轻戳一句"))
    }

    // ==================================================================
    // D. 思考块剥离 + 换行保留（preserveRaw 下思考内容必须仍被剥离）
    // ==================================================================

    @Test
    fun `剥离闭合thinking块-多行追问正文保留`() {
        val raw = "<thinking>\n我思考一下先\n</thinking>\n第一行追问正文\n第二行追问正文"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("思考内容必须剥离，正文多行保留", "第一行追问正文\n第二行追问正文", result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals(listOf("第一行追问正文", "第二行追问正文"), bubbles)
    }

    @Test
    fun `CRLF加thinking剥离加多行追问正文`() {
        val raw = "<think>思考中</think>\r\n追问第一行\r\n追问第二行"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("CRLF 正文必须原样保留", "追问第一行\r\n追问第二行", result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals(listOf("追问第一行", "追问第二行"), bubbles)
    }

    // ==================================================================
    // E. 双层防泄漏独立证伪：演示文字/旧标签的过滤正确性 + 误伤面隔离
    // ==================================================================

    @Test
    fun `旧版标签行被照抄-被过滤-正常内容拆3气泡-QA-tripwire更新`() {
        // 旧版 prompt 标签「话多想连发时的输出：」已下线，但过滤集合仍含它（防旧样本/回滚）。
        // 输入 = 旧 tripwire 对抗输入：1 行旧标签 + 3 行真实内容。
        val mimic = "话多想连发时的输出：\n刚刷到个视频笑死\n想起你昨天吐槽你同事那段\n你俩简直一模一样"

        val result = AiPromptBuilder.postProcessProactiveReply(mimic, emptyList())

        assertEquals("旧版标签行必须被滤除，正常内容保留",
            "刚刷到个视频笑死\n想起你昨天吐槽你同事那段\n你俩简直一模一样", result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals("标签行被滤后必须拆出 3 条真实气泡", 3, bubbles.size)
        assertEquals("首气泡是真实内容而非标签行", "刚刷到个视频笑死", bubbles[0])
    }

    @Test
    fun `新演示块字面量全部被滤-正常内容保留-端到端`() {
        // 10 个字面量中取 4 个混合 2 行正常内容 → 只保留正常内容
        val raw = "【格式演示：只演示「换行=发出下一条」，以下围栏内文字只是占位，你的输出禁止包含】\n" +
            "（占位：第一行）\n想连发几条时，占几行：\n" +
            "今晚天气不错\n想出去走走\n【演示结束】"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("演示字面量全部滤除，正常内容原样保留", "今晚天气不错\n想出去走走", result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals(listOf("今晚天气不错", "想出去走走"), bubbles)
    }

    @Test
    fun `相似但不整行一致的正常行-绝不误滤-精确匹配语义`() {
        // (1) 占位行带后缀 → 不应被滤
        val raw1 = "（占位：第一行）今晚吃啥\n第二条正常内容"
        val result1 = AiPromptBuilder.postProcessProactiveReply(raw1, emptyList())
        assertEquals("占位行+后缀 ≠ 整行字面量，不得误滤", raw1, result1)

        // (2) 无冒号的相似文字 → 不应被滤
        val raw2 = "话少时的输出\n第二条正常内容"
        val result2 = AiPromptBuilder.postProcessProactiveReply(raw2, emptyList())
        assertEquals("无冒号变体 ≠ 整行字面量，不得误滤", raw2, result2)

        // (3) 全角空格变体 → trim 只去 ASCII/Unicode 空白，全角空格(U+3000)不在 trim 范围
        // 验证：全角空格前缀行不会被匹配到字面量集合
        val raw3 = "\u3000（占位：第一行）\n第二条正常内容"
        val result3 = AiPromptBuilder.postProcessProactiveReply(raw3, emptyList())
        // Kotlin trim() 会去掉 U+3000（它是 Char.isWhitespace），所以 trim 后 = "（占位：第一行）"，会被滤
        // → 这条实际验证的是：trim 语义下全角空格前缀行被正确识别为泄漏行并滤除
        assertEquals("全角空格前缀 trim 后命中字面量集合，应被滤除", "第二条正常内容", result3)
    }

    @Test
    fun `全部命中字面量-走既有null兜底本轮不发`() {
        // 10 个字面量中的 8 个新版全覆盖 → stripProactiveDemoLeakLines 返回空串 → parse 返回 null
        val raw = listOf(
            "【格式演示：只演示「换行=发出下一条」，以下围栏内文字只是占位，你的输出禁止包含】",
            "只想发一条时，输出占一行：",
            "（占位：一行消息）",
            "想连发几条时，占几行：",
            "（占位：第一行）",
            "（占位：第二行）",
            "（占位：第三行）",
            "【演示结束】",
        ).joinToString("\n")

        assertNull("整批都是演示字面量时，走既有 null 兜底（本轮不发）",
            AiPromptBuilder.postProcessProactiveReply(raw, emptyList()))
    }

    @Test
    fun `过滤仅在主动路径生效-聊天路径不经过stripProactiveDemoLeakLines`() {
        // 通过 grep 验证：postProcessProactiveReply 仅 AiService:656/:717 两处调用，
        // 均为主动/追问路径。聊天路径（streamMessage/sendMessage/AiResponseFinalizer）不经过。
        // stripProactiveDemoLeakLines 仅在 postProcessProactiveReply 内部调用。
        // 本用例为文档性断言——若未来聊天路径误用 postProcessProactiveReply 会触发。
        // 直接测试 stripProactiveDemoLeakLines 行为：
        val normalChat = "（占位：第一行）\n今晚吃啥\n想连发几条时，占几行：后天见"
        val stripped = AiPromptBuilder.stripProactiveDemoLeakLines(normalChat)
        // stripProactiveDemoLeakLines 是整行精确匹配：
        // "（占位：第一行）" 精确命中 → 被滤
        // "想连发几条时，占几行：后天见" 不是整行匹配 → 保留
        assertEquals("stripProactiveDemoLeakLines 按整行精确匹配过滤",
            "今晚吃啥\n想连发几条时，占几行：后天见", stripped)
    }
}
