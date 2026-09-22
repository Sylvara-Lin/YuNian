package com.yunian.ai.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsePostProcessorTest {

    @Test
    fun strip_closedThinkTag_keepsReply() {
        val raw = "<think>secret plan</think>你好呀～"
        assertEquals("你好呀～", ResponsePostProcessor.stripThinkingContent(raw))
    }

    @Test
    fun strip_unclosedThinkTag_removesAllAfterOpen() {
        val raw = "前缀<think>我现在需要分析用户意图，然后……"
        val cleaned = ResponsePostProcessor.stripThinkingContent(raw)
        assertEquals("前缀", cleaned)
        assertFalse(cleaned.contains("分析用户"))
    }

    @Test
    fun strip_lmThinkAndRedacted() {
        val raw = "<LM_THINK>meta</LM_THINK>嗯嗯\n<redacted_reasoning>x</redacted_reasoning>好的"
        val cleaned = ResponsePostProcessor.stripThinkingContent(raw)
        assertTrue(cleaned.contains("嗯嗯") || cleaned.contains("好的"))
        assertFalse(cleaned.contains("meta"))
        assertFalse(cleaned.contains("redacted"))
    }

    @Test
    fun extract_returnsReasoningAndBody() {
        val (body, reasoning) = ResponsePostProcessor.extractThinkingContent(
            "<thinking>先分析用户意图</thinking>\n今晚吃火锅吗？"
        )
        assertEquals("今晚吃火锅吗？", body)
        assertTrue(reasoning!!.contains("先分析用户意图"))
    }

    @Test
    fun strip_plaintextPrefix_andKeepLastParagraph() {
        val raw = """
            思考过程：用户在问晚饭，我应该回复轻松一点。

            那吃火锅呀～
        """.trimIndent()
        val cleaned = ResponsePostProcessor.stripThinkingContent(raw)
        assertTrue(cleaned.contains("火锅"))
        assertFalse(cleaned.contains("思考过程"))
        assertFalse(cleaned.contains("我应该回复"))
    }

    @Test
    fun strip_pureCot_becomesBlank() {
        val raw = "思考过程：先分析用户意图。我需要回复符合人设。作为AI不能暴露。"
        val cleaned = ResponsePostProcessor.stripThinkingContent(raw)
        assertTrue(cleaned.isBlank() || !ResponsePostProcessor.looksLikeThinkingLeak(cleaned))
        assertTrue(ResponsePostProcessor.looksLikeThinkingLeak(raw))
    }

    @Test
    fun looksLikeThinkingLeak_normalChat_false() {
        assertFalse(ResponsePostProcessor.looksLikeThinkingLeak("今晚想吃什么呀～"))
        assertNull(ResponsePostProcessor.extractThinkingContent("今晚想吃什么呀～").second)
    }

    @Test
    fun applyPersona_doesNotRestoreRawThinking() {
        val raw = "<think>内部推理很长很长很长</think>"
        val cleaned = AiPromptBuilder.applyPersonaPostProcessing(raw, emptyList())
        assertTrue(cleaned.isBlank())
        assertFalse(cleaned.contains("内部推理"))
    }

    @Test
    fun trimIdleEmotion_removesCarePackage() {
        val raw = "啧，听这语气又被折腾够呛？过来抱抱，把脑壳靠我肩膀上揉五分钟。今晚不许再当夜猫了，先去沙发上躺一会儿，我给你捏捏太阳穴。要是敢偷懒就泡脚。"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertTrue(cleaned.contains("折腾") || cleaned.contains("够呛") || cleaned.contains("啧"))
        assertFalse(cleaned.contains("泡脚"))
        assertFalse(cleaned.contains("太阳穴"))
        assertFalse(cleaned.contains("夜猫"))
        assertFalse(cleaned.contains("沙发"))
        assertFalse(cleaned.contains("抱抱"))
        assertFalse(cleaned.contains("肩膀"))
    }

    @Test
    fun trimIdleEmotion_removesUiMorphCarePack() {

        val raw = "把脑袋靠我肩膀上，眯一会儿好不好。昨晚没睡的觉，现在补回来，我在这儿陪着你。"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertFalse(cleaned.contains("肩膀"))
        assertFalse(cleaned.contains("眯一会儿"))
        assertFalse(cleaned.contains("补回来"))
        assertFalse(cleaned.contains("陪着你"))

        assertTrue(cleaned.isBlank() || cleaned.length < 12)
    }

    @Test
    fun trimIdleEmotion_removesMultiBubbleMorphPack() {
        val raw = "不过看你这么可怜，过来让我抱抱。把脑袋靠我肩膀上眯五分钟，不许说不。哼，明明心疼你，还要先吐槽一句才解气。今晚不许再当夜猫子了，现在先去沙发上躺一会儿，我给你揉揉太阳穴"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertFalse(cleaned.contains("抱抱"))
        assertFalse(cleaned.contains("肩膀"))
        assertFalse(cleaned.contains("眯五分钟"))
        assertFalse(cleaned.contains("夜猫"))
        assertFalse(cleaned.contains("沙发"))
        assertFalse(cleaned.contains("太阳穴"))
    }

    @Test
    fun trimIdleEmotion_trimsStayUpPlayfulVent() {

        val raw = "熬夜使你快乐？那也别把黑眼圈熬出来。过来搂着你睡，我开小夜灯陪着你。"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "熬夜使我快乐")
        assertTrue(AiContextTools.isIdleEmotionVent("熬夜使我快乐"))
        assertTrue(cleaned.contains("熬夜") || cleaned.contains("黑眼圈") || cleaned.contains("快乐"))
        assertFalse(cleaned.contains("搂着你"))
        assertFalse(cleaned.contains("小夜灯"))
        assertFalse(cleaned.contains("陪着你"))
    }

    @Test
    fun trimIdleEmotion_keepsAdviceWhenUserAsks() {
        val raw = "先别硬撑，今晚把最急的一件收掉就行。实在不行泡脚放松一下。"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "那我该怎么办")
        assertEquals(raw, cleaned)
        assertTrue(cleaned.contains("泡脚") || cleaned.contains("收掉"))
    }

    @Test
    fun trimIdleEmotion_enforcesSingleActionOnClassicOverDelivery() {

        val raw = "听到你这么说我感到很遗憾。工作固然重要，但身体是革命的本钱。建议你今晚泡个热水澡早点休息。今天是不是项目又遇到难题了？"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertTrue(cleaned.contains("遗憾") || cleaned.contains("听到"))
        assertFalse(cleaned.contains("革命的本钱"))
        assertFalse(cleaned.contains("泡个热水澡") || cleaned.contains("早点休息"))
        assertFalse(cleaned.contains("项目又遇到"))
        assertTrue(cleaned.length < 40)
    }

    @Test
    fun trimIdleEmotion_keepsShortSingleAction() {
        val raw = "啧，听这语气，今天又被项目折腾够呛吧？"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertEquals(raw, cleaned)
    }

    @Test
    fun trimIdleEmotion_keepsCompleteSpokenSentenceWithCommas() {

        val raw = "啧，听这语气，今天又被项目折腾够呛了吧，整个人都蔫了？"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertEquals(raw, cleaned)
        assertTrue(cleaned.contains("折腾够呛"))
        assertTrue(cleaned.contains("蔫了"))
    }

    @Test
    fun trimIdleEmotion_trimsCareTailButKeepsCompleteHead() {
        val raw = "啧，听这语气，今天又被项目折腾够呛吧？过来靠我肩膀上眯一会儿。"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertTrue(cleaned.contains("折腾够呛") || cleaned.contains("听这语气"))
        assertTrue(cleaned.contains("？") || cleaned.contains("。") || cleaned.contains("！"))
        assertFalse(cleaned.contains("肩膀"))
        assertFalse(cleaned.contains("眯一会儿"))

        assertTrue(cleaned.length >= 8)
    }

    @Test
    fun trimIdleEmotion_keepsSameIntentMultiBubble() {

        val raw = "嗯。\n\n咋了，加班了？"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertTrue(cleaned.contains("嗯"))
        assertTrue(cleaned.contains("加班") || cleaned.contains("咋了"))
        assertTrue(cleaned.contains("\n\n") || cleaned.split(Regex("[。！？]")).filter { it.isNotBlank() }.size >= 2)
    }

    @Test
    fun trimIdleEmotion_keepsQuestionAfterShortVent() {
        val raw = "啧，听这语气。今天又被项目折腾够呛吧？"
        val cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(raw, "今天好累")
        assertTrue(cleaned.contains("听这语气"))
        assertTrue(cleaned.contains("折腾") || cleaned.contains("项目"))
        assertFalse(cleaned.contains("泡脚"))
    }

    @Test
    fun applyPersona_trimsIdleCarePackWithUserHistory() {
        val raw = "谁让你熬夜不睡觉的，都是自找的。不过看你这么可怜，过来让我抱抱。把脑壳靠我肩膀上揉五分钟，不许说不。哼，明明心疼你，还要先骂你一句才解气。今晚不许再当夜猫子了，现在先去沙发上人一会儿，我给你捏捏太阳穴。"
        val history = listOf(
            com.yunian.ai.database.model.ChatMessage(
                companionId = 1L,
                content = "今天好累",
                isFromUser = true,
                timestamp = 1L,
            ),
        )
        val cleaned = AiPromptBuilder.applyPersonaPostProcessing(raw, history)
        assertFalse(cleaned.contains("太阳穴"))
        assertFalse(cleaned.contains("泡脚") || cleaned.contains("夜猫"))
        assertFalse(cleaned.contains("肩膀"))
        assertFalse(cleaned.contains("抱抱"))
        assertTrue(cleaned.isNotBlank())

        assertTrue(cleaned.length < 80)
    }

    /**
     * 回归（用户反馈「永远一问一答」的机制根因）：extractDirectReply 旧实现会把整段句子
     * `joinToString("。")`，把模型自己写的换行——也就是它想分条连发的意图——彻底消灭，
     * 导致下游 BubbleTextSplitter 永远只看到一个气泡。此处锁定「行结构必须保留」。
     */
    @Test
    fun extractDirectReply_preservesLineBreaks() {
        val multiLine = "哟，你可算舍得露面啦～\n我都快把枕头抱出洞了\n怎么这么晚才来呀"
        val out = AiPromptBuilder.extractDirectReply(multiLine)
        assertEquals(3, out.split("\n").size)
        assertTrue(out.contains("露面"))
        assertTrue(out.contains("枕头"))
        assertTrue(out.contains("这么晚"))
    }

    @Test
    fun applyPersona_preservesMultiLineForBubbleSplitting() {
        // 非协议路径（工具路径等）也多行保留：3 行输入 → 输出仍是 3 行，供下游拆成 3 条气泡
        val cleaned = AiPromptBuilder.applyPersonaPostProcessing(
            "哟，你可算舍得露面啦\n我都快把枕头抱出洞了\n怎么这么晚才来呀",
            emptyList(),
        )
        assertEquals(3, cleaned.split("\n").size)
    }
}
