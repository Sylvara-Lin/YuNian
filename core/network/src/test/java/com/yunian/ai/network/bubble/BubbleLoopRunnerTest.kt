package com.yunian.ai.network.bubble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLoopRunnerTest {

    private class FakeGenerator(
        private val responses: ArrayDeque<String>,
    ) {
        val calls = mutableListOf<List<String>>()
        var callCount = 0

        suspend fun generate(alreadyGenerated: List<String>): String {
            callCount++
            calls.add(alreadyGenerated.toList())
            return if (responses.isEmpty()) "" else responses.removeFirst()
        }
    }

    private suspend fun BubbleLoopRunner.runWith(
        generator: FakeGenerator,
    ): List<String> = runFollowingBubbles { alreadyGenerated ->
        generator.generate(alreadyGenerated)
    }

    @Test
    fun `runFollowingBubbles - 无继续则只返回首条后续`() = runBlocking {
        val gen = FakeGenerator(ArrayDeque(listOf("""{"text":"不再继续了","continue":false}""")))
        val runner = BubbleLoopRunner()
        val bubbles = runner.runWith(gen)
        assertEquals(listOf("不再继续了"), bubbles)

        assertEquals(1, gen.callCount)
        assertEquals(listOf(emptyList<String>()), gen.calls)
    }

    @Test
    fun `runFollowingBubbles - 继续两条后停止`() = runBlocking {
        val gen = FakeGenerator(
            ArrayDeque(
                listOf(
                    """{"text":"第一条","continue":true}""",
                    """{"text":"第二条","continue":true}""",
                    """{"text":"第三条","continue":false}""",
                )
            )
        )
        val runner = BubbleLoopRunner()
        val bubbles = runner.runWith(gen)
        assertEquals(listOf("第一条", "第二条", "第三条"), bubbles)
        assertEquals(3, gen.callCount)

        assertEquals(listOf("第一条"), gen.calls[1])

        assertEquals(listOf("第一条", "第二条"), gen.calls[2])
    }

    @Test
    fun `runFollowingBubbles - 连发达到兜底上限即停`() = runBlocking {
        var count = 0
        val runner = BubbleLoopRunner()
        val bubbles = runner.runFollowingBubbles {
            count++
            """{"text":"第${count}条","continue":true}"""
        }

        // 上限由 BubbleLoopRunner.MAX_BUBBLES 兜底（产品上条数不限，模型 continue:false 才停）
        assertEquals(BubbleLoopRunner.MAX_BUBBLES - 1, bubbles.size)
        assertEquals(BubbleLoopRunner.MAX_BUBBLES - 1, count)

        assertTrue(bubbles.all { it.startsWith("第") })
    }

    @Test
    fun `runFollowingBubbles - 格式漂移最多重试 3 次`() = runBlocking {

        var count = 0
        val runner = BubbleLoopRunner()
        val bubbles = runner.runFollowingBubbles {
            count++
            "这不是 JSON 格式的回复"
        }
        assertTrue(bubbles.isEmpty())
        assertEquals(3, count)
    }

    @Test
    fun `runFollowingBubbles - 异常只算一次失败，重试成功后继续`() = runBlocking {
        var count = 0
        val runner = BubbleLoopRunner()
        val bubbles = runner.runFollowingBubbles {
            count++
            if (count == 2) throw RuntimeException("模拟网络错误")
            """{"text":"气泡$count","continue":true}"""
        }

        assertEquals(BubbleLoopRunner.MAX_BUBBLES - 1, bubbles.size)
        assertEquals(BubbleLoopRunner.MAX_BUBBLES, count)
    }

    @Test
    fun `runFollowingBubbles - 持续异常则停止且已生成气泡保留`() = runBlocking {
        var count = 0
        val runner = BubbleLoopRunner()
        val bubbles = runner.runFollowingBubbles {
            count++
            if (count > 1) throw RuntimeException("持续网络错误")
            """{"text":"第一条","continue":true}"""
        }

        assertEquals(1, bubbles.size)
        assertEquals(listOf("第一条"), bubbles)
        assertEquals(4, count)
    }
}
