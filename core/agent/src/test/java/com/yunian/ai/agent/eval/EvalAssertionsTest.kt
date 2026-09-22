package com.yunian.ai.agent.eval

import com.yunian.ai.domain.eval.EvalCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalAssertionsTest {

    private val base = EvalCase(name = "t", historyJson = "[]")

    @Test
    fun passes_when_all_expectations_met() {
        val case = base.copy(
            expectTextContains = listOf("方案", "结论"),
            expectBubbleContains = listOf("开始"),
            expectFinishedReason = "completed",
        )
        val result = EvalAssertions.toResult(case, "我的方案与结论如下", "completed", listOf("开始思考"))
        assertTrue(result.passed)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun fails_when_text_piece_missing() {
        val case = base.copy(expectTextContains = listOf("结论"), expectFinishedReason = "completed")
        val result = EvalAssertions.toResult(case, "只有方案", "completed", emptyList())
        assertEquals(false, result.passed)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].contains("结论"))
    }

    @Test
    fun fails_when_finished_reason_mismatch() {
        val case = base.copy(expectFinishedReason = "completed")
        val result = EvalAssertions.toResult(case, "x", "max_rounds", emptyList())
        assertEquals(false, result.passed)
        assertTrue(result.failures.single().contains("max_rounds"))
    }

    @Test
    fun fails_when_bubble_piece_missing() {
        val case = base.copy(expectBubbleContains = listOf("问候"))
        val result = EvalAssertions.toResult(case, "你好", "completed", listOf("普通回复"))
        assertEquals(false, result.passed)
        assertTrue(result.failures.single().contains("问候"))
    }

    @Test
    fun empty_expectations_pass_trivially() {
        val result = EvalAssertions.toResult(base, "任意", "error", emptyList())
        assertTrue(result.passed)
    }
}
