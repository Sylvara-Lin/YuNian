package com.yunian.ai.network

import com.yunian.ai.common.text.BubbleTextSplitter
import com.yunian.ai.database.model.CompanionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息多气泡回归护栏。
 *
 * 背景：AI 主动找用户（追问 generateFollowUpReminder / 主动问候 generateProactiveMessage）
 * 此前每次只发一句话一个气泡：
 * - 追问路径 prompt 写死「只发 1 条，10~30 字」，且后处理把换行全部压缩成「，」（永远单气泡）；
 * - 问候路径链路已支持多行但模型只敲一行（软约束未被稳定遵守）。
 *
 * 修复：两条路径统一走 [AiPromptBuilder.postProcessProactiveReply]（preserveRaw 保留换行，
 * 发送端按换行拆多气泡）；prompt 去掉单条硬限制，并在问候指令追加「换行=下一条」few-shot 示例。
 */
class ProactiveMultiBubblePromptTest {

    private val companion = CompanionEntity(
        name = "小云",
        personality = "温柔黏人，爱分享日常",
    )

    // ==================================================================
    // 1. 后处理：追问/问候统一走 postProcessProactiveReply，多行换行必须保留
    //    （核心回归护栏：旧追问路径会把 \n 压缩成「，」，永远单气泡）
    // ==================================================================

    @Test
    fun `后处理保留多行换行-不压缩成单行`() {
        val raw = "刚刷到个视频笑死\n想起你昨天吐槽你同事那段\n你俩简直一模一样"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("多行文本必须原样保留换行（发送端按换行拆多气泡）", raw, result)
    }

    @Test
    fun `后处理不将CRLF压缩为逗号`() {
        // 旧追问路径会把 \r\n 全部替换成「，」；本用例锁定该行为不得回归
        val raw = "第一条\r\n第二条"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("CRLF 换行不得被压缩成逗号（旧追问路径行为）", raw, result)
    }

    @Test
    fun `后处理对独占一行的marker返回null`() {
        val raw = "想你了\n${AiPromptBuilder.NO_PROACTIVE_MARKER}\n算了不发了"

        assertNull(
            "独占一行的 NO_PROACTIVE_MARKER 表示整批不发",
            AiPromptBuilder.postProcessProactiveReply(raw, emptyList()),
        )
    }

    @Test
    fun `后处理对内嵌marker剥离后保留多行正文`() {
        val raw = "第一条${AiPromptBuilder.NO_PROACTIVE_MARKER}\n第二条"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("第一条\n第二条", result)
    }

    @Test
    fun `后处理对空白内容返回null`() {
        assertNull(AiPromptBuilder.postProcessProactiveReply("   ", emptyList()))
    }

    @Test
    fun `后处理输出经交付拆分器拆成多条气泡-端到端契约`() {
        // 与 CompanionMessageWorker 送达链路同一拆分器：3 行 = 3 条气泡
        val raw = "刚刷到个视频笑死\n想起你昨天吐槽你同事那段\n你俩简直一模一样"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertNotNull(result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals("3 行应拆成 3 条气泡（交付端按换行拆）", 3, bubbles.size)
        assertEquals(listOf("刚刷到个视频笑死", "想起你昨天吐槽你同事那段", "你俩简直一模一样"), bubbles)
    }

    // ==================================================================
    // 2. 追问路径 prompt：去掉单条/字数硬限制，允许多气泡（用户指令 + 系统提示词口径一致）
    // ==================================================================

    @Test
    fun `追问用户指令不再含单条与字数硬限制`() {
        val instruction = AiPromptBuilder.buildFollowUpReminderInstruction()

        assertFalse("不得再写死「只发 1 条」", instruction.contains("只发 1 条"))
        assertFalse("不得再写死「一次一条」", instruction.contains("一次一条"))
        assertFalse("不得再写死字数区间「10~30」", instruction.contains("10~30"))
        assertTrue("必须允许多气泡：换行即下一条", instruction.contains("换行即下一条"))
        assertTrue("marker 语义保留", instruction.contains(AiPromptBuilder.NO_PROACTIVE_MARKER))
        assertTrue("不重复纪律保留", instruction.contains("不要重复上一条消息的内容"))
        assertTrue("不堆叠纪律保留", instruction.contains("不要堆叠同一句话"))
    }

    @Test
    fun `追问系统提示词的追问纪律不再含单条与字数硬限制`() {
        val prompt = AiPromptBuilder.buildFollowUpReminderSystemPrompt(companion)

        assertFalse("追问纪律不得再写死「一次一条」", prompt.contains("一次一条"))
        assertFalse("追问纪律不得再写死字数区间「10~30」", prompt.contains("10~30"))
        assertTrue("追问纪律必须允许多气泡：换行即下一条", prompt.contains("换行即下一条"))
        assertTrue("禁止堆叠纪律保留", prompt.contains("禁止堆叠同一句话"))
    }

    // ==================================================================
    // 3. 问候路径 prompt：保留多气泡指令 + 「围栏+占位」few-shot 演示（防照抄泄漏）
    // ==================================================================

    @Test
    fun `问候决策指令包含多气泡指令与格式演示块`() {
        val envHint = "7. 环境关心冷却中：禁止再提睡/吃/到家/报时/天气；只做话题延续或情绪轻触"

        val instruction = AiPromptBuilder.buildProactiveDecisionInstruction("小云", envHint)

        assertTrue(instruction.contains("以小云的身份"))
        assertTrue("多气泡指令保留", instruction.contains("消息条数不限"))
        assertTrue(instruction.contains("换行即下一条"))
        assertTrue("必须包含围栏式格式演示", instruction.contains("【格式演示：只演示「换行=发出下一条」"))
        assertTrue("envUserHint 必须透传", instruction.contains(envHint))
        assertTrue("marker 语义保留", instruction.contains(AiPromptBuilder.NO_PROACTIVE_MARKER))
    }

    @Test
    fun `问候演示块为围栏占位形态-1行版与3行版-行级标签不可模仿`() {
        val instruction = AiPromptBuilder.buildProactiveDecisionInstruction("小云", "7. hint")

        // 围栏起止 + 1 行版/3 行版结构（真实换行演示「回车=下一条气泡」）
        assertTrue(instruction.contains("只想发一条时，输出占一行：\n（占位：一行消息）"))
        assertTrue(
            "3 行版必须是三行真实换行的占位连发形态",
            instruction.contains("想连发几条时，占几行：\n（占位：第一行）\n（占位：第二行）\n（占位：第三行）"),
        )
        assertTrue(instruction.contains("【演示结束】"))
        // 旧版可模仿行级标签必须已移除（QA P2：照抄会独立成真实气泡）
        assertFalse("旧标签不得再出现", instruction.contains("话少时的输出："))
        assertFalse("旧标签不得再出现", instruction.contains("话多想连发时的输出："))
    }

    // ==================================================================
    // 3.5 演示文字泄漏兜底：stripProactiveDemoLeakLines（代码侧第二层防御）
    // ==================================================================

    @Test
    fun `模型照抄演示围栏与占位行-全部被滤除-正常3行拆3气泡`() {
        val raw = listOf(
            "【格式演示：只演示「换行=发出下一条」，以下围栏内文字只是占位，你的输出禁止包含】",
            "想连发几条时，占几行：",
            "刚发工资甚是开心",
            "请你喝杯奶茶",
            "想喝啥自己挑",
            "【演示结束】",
        ).joinToString("\n")

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals(
            "围栏/标签/占位行必须被滤除，正常内容原样保留",
            "刚发工资甚是开心\n请你喝杯奶茶\n想喝啥自己挑",
            result,
        )
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals(listOf("刚发工资甚是开心", "请你喝杯奶茶", "想喝啥自己挑"), bubbles)
    }

    @Test
    fun `旧版行级标签被照抄-同样滤除-QA-tripwire旧行为已修复`() {
        // QA ProactiveMultiBubbleQaAdversarialTest 的 tripwire 输入：旧行为=标签行独立成气泡
        val raw = "话多想连发时的输出：\n刚刷到个视频笑死\n想起你昨天吐槽你同事那段\n你俩简直一模一样"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("旧版标签行必须被滤除", "刚刷到个视频笑死\n想起你昨天吐槽你同事那段\n你俩简直一模一样", result)
        val bubbles = BubbleTextSplitter.splitByParagraphs(result!!)
        assertEquals(3, bubbles.size)
        assertEquals("首气泡是真实内容而非标签行", "刚刷到个视频笑死", bubbles[0])
    }

    @Test
    fun `输出仅为演示内容-全部滤除后返回null-走既有兜底`() {
        // 10 个字面量全覆盖（围栏x2 + 标签x2 + 占位x4 + 旧标签x2 中的新版 8 行）
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

        assertNull("整批都是演示文字时应本轮不发（既有 null 兜底）", AiPromptBuilder.postProcessProactiveReply(raw, emptyList()))
    }

    @Test
    fun `CRLF文本中的泄漏行-同样滤除`() {
        val result = AiPromptBuilder.postProcessProactiveReply("话少时的输出：\r\n在忙吗", emptyList())

        assertEquals("CRLF 下的泄漏行（trim 后整行匹配）必须滤除", "在忙吗", result)
    }

    @Test
    fun `相似但非精确匹配的正常行-绝不误伤`() {
        // 带后缀内容的相似行 ≠ 整行字面量：不得被过滤（防宽松匹配误伤真实消息）
        val raw = "（占位：第一行）今晚吃啥\n想连发几条时，占几行：后天见分晓"

        val result = AiPromptBuilder.postProcessProactiveReply(raw, emptyList())

        assertEquals("非整行精确匹配的相似行必须原样保留", raw, result)
    }

    // ==================================================================
    // 4. parseProactiveGenerationResult：多行文本 + marker 行为锁定（本次未改动，应保持绿）
    // ==================================================================

    @Test
    fun `parseProactiveGenerationResult多行无marker原样返回`() {
        val raw = "第一条\n第二条\n第三条"
        assertEquals(raw, AiPromptBuilder.parseProactiveGenerationResult(raw))
    }

    @Test
    fun `parseProactiveGenerationResult marker独占一行返回null`() {
        assertNull(AiPromptBuilder.parseProactiveGenerationResult("第一条\n[NO_PROACTIVE]\n第三条"))
        assertNull(AiPromptBuilder.parseProactiveGenerationResult("[no_proactive]"))
    }

    @Test
    fun `parseProactiveGenerationResult 内嵌marker剥离后保留多行`() {
        assertEquals(
            "正文\n继续",
            AiPromptBuilder.parseProactiveGenerationResult("正文[NO_PROACTIVE]\n继续"),
        )
    }

    @Test
    fun `parseProactiveGenerationResult 剥离marker后过短返回null`() {
        assertNull(AiPromptBuilder.parseProactiveGenerationResult("[NO_PROACTIVE]嗯"))
    }
}
