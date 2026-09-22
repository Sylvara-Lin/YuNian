package com.yunian.ai.common.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA 独立验证（非工程自测）：针对症状①「故事被逐句拆散」的反向证明。
 *
 * 复现 PRD 验收 #1 / #7 / #8①：300 字无空行整段 → 恰好 1 条；含空行故事 → 按段拆条。
 * 用生产实际调用签名 [BubbleTextSplitter.splitForDelivery]（单聊 deliverResponse 兜底路径）。
 */
class QaBubbleSplitParityTest {

    @Test
    fun `300字无空行故事经生产路径恰好一条`() {
        // 构造恰好 300 字、无空行的整段故事（句末标点齐全，用于验证「绝不按句拆」）。
        val base = "很久很久以前有一只小猫它住在一个安静的小镇上每天清晨它都会沿着河边散步看看日出和来往的行人。" +
            "有一天它遇见了一只迷路的小狗于是决定帮它找到回家的路它们一起走过了很多地方最后终于找到了小狗的家。" +
            "从那以后它们成了最好的朋友每天一起玩耍一起分享食物一起看星星直到很老很老都还住在那条河边的小镇上。"
        val story = base.repeat(3).take(300)
        assertEquals(300, story.length)
        val bubbles = BubbleTextSplitter.splitForDelivery(story, allowParagraphSplit = true, maxBubbles = 8)
        assertEquals(1, bubbles.size)
        assertEquals(story, bubbles[0])
    }

    @Test
    fun `含两个空行的故事拆成三条`() {
        val story = "第一段：猫在河边散步\n\n第二段：猫遇见小狗\n\n第三段：它们一起回家"
        val bubbles = BubbleTextSplitter.splitForDelivery(story, allowParagraphSplit = true, maxBubbles = 8)
        assertEquals(
            listOf("第一段：猫在河边散步", "第二段：猫遇见小狗", "第三段：它们一起回家"),
            bubbles,
        )
    }

    @Test
    fun `无换行时绝不按标点拆；AI 敲回车即拆（症状④塞在一起修复）`() {
        // 无换行：全文受标点约束但绝不按句末标点切分 → 单条
        val text = "一句。两句！三句？四句～五句……"
        assertEquals(1, BubbleTextSplitter.splitForDelivery(text, true, 8).size)
        // 有换行：AI 自己敲了回车 = 想换一条 → 按行拆分（解决「全部塞在一起」的观感）
        val withEnters = text.replace("。", "。\n")
        val bubbles = BubbleTextSplitter.splitForDelivery(withEnters, true, 8)
        assertEquals(withEnters.split("\n").size, bubbles.size)
        assertEquals("一句。", bubbles[0])
    }
}
