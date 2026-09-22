package com.yunian.ai.common.text

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextSplitterTest {

    @Test
    fun `无空行长文本保持单条 - 绝不按句末标点拆分`() {
        val text = "你好呀。今天过得怎么样？我这边忙了一整天呢！晚上一起吃个饭吧～"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `三百字无空行整段单条`() {
        val text = "嗯".repeat(150) // 300 chars, no blank line
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `按空行分段`() {
        val text = "第一段内容\n\n第二段内容\n\n第三段内容"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(listOf("第一段内容", "第二段内容", "第三段内容"), result)
    }

    @Test
    fun `单个换行也拆 - AI 敲的回车 = 想换一条`() {
        val text = "嘿嘿，被你这么一夸，尾巴都要翘起来啦～\n那下次你再说三个词，我还能给你编个更长的～"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(2, result.size)
        assertEquals("嘿嘿，被你这么一夸，尾巴都要翘起来啦～", result[0])
        assertEquals("那下次你再说三个词，我还能给你编个更长的～", result[1])
    }

    @Test
    fun `代码块围栏内不拆 - fence 内换行保留为一条`() {
        val text = "看代码：\n```\nfun main() {\n    println(\"hi\")\n}\n```\n就这些"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(3, result.size)
        assertEquals("看代码：", result[0])
        assertEquals("```\nfun main() {\n    println(\"hi\")\n}\n```", result[1])
        assertEquals("就这些", result[2])
    }

    @Test
    fun `fence 未闭合时滞留内容按普通行拆分 - 不吞并为一条`() {
        // P2-A1：奇数个 ``` 行（fence 未闭合）时，落单围栏起到文末的内容
        // 按「fence 从未生效」降级为普通文本逐行切分，不得吞并成一条。
        val text = "开头\n```kotlin\n未闭合fun x()\n普通文本两行"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(listOf("开头", "```kotlin", "未闭合fun x()", "普通文本两行"), result)
    }

    @Test
    fun `fence 未闭合且滞留内容含多行时逐行成泡`() {
        // P2-A1 补充：未闭合滞留段里的空行同样不产生空气泡，围栏行按普通行处理。
        val text = "```\n第一行\n\n第二行"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(listOf("第一行", "第二行"), result)
    }

    @Test
    fun `超过上限时拼接尾段`() {
        val max = 3
        val text = (1..5).joinToString("\n\n") { "段落$it" }
        val result = BubbleTextSplitter.splitByParagraphs(text, max)
        assertEquals(max, result.size)
        assertEquals("段落1", result[0])
        assertEquals("段落2", result[1])
        assertEquals("段落3\n段落4\n段落5", result[2])
    }

    @Test
    fun `空行分隔但含纯标点段时丢弃空段`() {
        val text = "有内容\n\n…\n\n\n\n后面的内容"
        val result = BubbleTextSplitter.splitByParagraphs(text)
        assertEquals(listOf("有内容", "后面的内容"), result)
    }

    @Test
    fun `空白输入原样返回单条`() {
        assertEquals(listOf(""), BubbleTextSplitter.splitByParagraphs(""))
        assertEquals(listOf("   "), BubbleTextSplitter.splitByParagraphs("   "))
    }

    @Test
    fun `splitForDelivery - 禁用分段时整条不拆`() {
        val text = "第一段\n\n第二段\n\n第三段"
        val result = BubbleTextSplitter.splitForDelivery(text, allowParagraphSplit = false)
        assertEquals(1, result.size)
        assertEquals(text, result[0])
    }

    @Test
    fun `splitForDelivery - 启用分段时按空行拆分`() {
        val text = "第一段\n\n第二段"
        val result = BubbleTextSplitter.splitForDelivery(text, allowParagraphSplit = true)
        assertEquals(listOf("第一段", "第二段"), result)
    }

    @Test
    fun `主动消息多行文本拆成多条气泡并按本批窗口去重`() {
        // 主动消息生成端保留换行后，发送端依赖本拆分器把多行文本拆成多条气泡连发：
        // 一行一条、空行不产生空气泡，本批内重复气泡（归一化后）被过滤。
        val proactive = "刚看到个超好笑的\n突然想到你\n\n刚看到个超好笑的\n你今天忙不忙呀"
        val bubbles = BubbleTextSplitter.splitByParagraphs(proactive)
        assertEquals(4, bubbles.size)

        val window = mutableListOf<String>()
        val delivered = bubbles.mapNotNull { bubble ->
            val text = bubble.trim()
            if (text.isEmpty()) return@mapNotNull null
            val norm = DedupGuard.normalize(text)
            if (norm.isNotEmpty() && DedupGuard.isDuplicate(norm, window)) {
                null
            } else {
                if (norm.isNotEmpty()) window.add(norm)
                text
            }
        }
        assertEquals(listOf("刚看到个超好笑的", "突然想到你", "你今天忙不忙呀"), delivered)

        // 无换行的单条主动消息：行为与旧版一致，恰好 1 条气泡
        assertEquals(1, BubbleTextSplitter.splitByParagraphs("在干嘛呢").size)
    }
}
