package com.yunian.ai.agent.plugin

import com.yunian.ai.agent.plugin.PluginBlueprintStatus.Outcome
import com.yunian.ai.domain.plugin.BlueprintLoadResult
import com.yunian.ai.domain.plugin.BlueprintPluginRef
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginBlueprint
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginBlueprintStatus] 的纯 JVM 单测（债务 D4 / B2 的「失败必须可查询」半边）。
 *
 * 验证状态记录、不可变快照，以及真实宿主局部失败结果的记录语义。
 * 不碰 `android.util.Log` / `org.json` / `SystemClock`，因此无需 Robolectric。
 */
class PluginBlueprintStatusTest {

    @After
    fun tearDown() {
        // 本对象是进程级单例：测试之间必须复位，否则用例互相污染。
        PluginBlueprintStatus.resetForTest()
    }

    @Test
    fun `初始状态是未尝试而不是失败`() {
        // 「还没跑」与「跑了但失败」必须是两件事：否则启动早期查询会把
        // 「尚未装载」误报成故障。
        assertTrue(PluginBlueprintStatus.outcome() is Outcome.NotAttempted)
        assertFalse("未尝试不得被当成失败", PluginBlueprintStatus.hasFailed())
        assertEquals(0L, PluginBlueprintStatus.attempts())
        assertEquals(0L, PluginBlueprintStatus.current().recordedAtMs)
    }

    @Test
    fun `记录成功后 outcome 是 Applied 且保留 loaded skipped 明细`() {
        PluginBlueprintStatus.recordApplied(
            blueprintId = "default",
            loaded = listOf("automation.core", "channel.qqbot"),
            skipped = listOf("notfound:t.no.such"),
        )

        val outcome = PluginBlueprintStatus.outcome()
        assertTrue(outcome is Outcome.Applied)
        outcome as Outcome.Applied
        assertEquals(listOf("automation.core", "channel.qqbot"), outcome.loaded)
        assertEquals(listOf("notfound:t.no.such"), outcome.skipped)
        assertFalse(PluginBlueprintStatus.hasFailed())
        assertEquals(1L, PluginBlueprintStatus.attempts())
        assertTrue("recordedAtMs 必须被填上", PluginBlueprintStatus.current().recordedAtMs > 0L)
    }

    @Test
    fun `记录失败后 hasFailed 为真且原因与蓝图 id 可读`() {
        PluginBlueprintStatus.recordFailure(
            reason = "蓝图 JSON 解析失败",
            blueprintId = "default",
        )

        assertTrue(PluginBlueprintStatus.hasFailed())
        val outcome = PluginBlueprintStatus.outcome()
        assertTrue(outcome is Outcome.Failed)
        outcome as Outcome.Failed
        assertEquals("default", outcome.blueprintId)
        assertEquals("蓝图 JSON 解析失败", outcome.reason)
        assertTrue("未提供已知装载项时 loaded 默认为空，不能据此推断宿主状态", outcome.loaded.isEmpty())
        assertEquals(1L, PluginBlueprintStatus.attempts())
    }

    @Test
    fun `失败原因自动带上异常类名与 message`() {
        // 「只有 message」会让 NullPointerException（Kotlin `!!` 无消息）失去定位信息，
        // 因此类名必须进 reason。
        PluginBlueprintStatus.recordFailure(
            reason = "蓝图资产读取失败",
            failure = IllegalStateException("assets 打不开"),
        )

        val reason = (PluginBlueprintStatus.outcome() as Outcome.Failed).reason
        assertTrue("必须含原始 reason，实际=$reason", reason.startsWith("蓝图资产读取失败"))
        assertTrue("必须含异常类名，实际=$reason", reason.contains("IllegalStateException"))
        assertTrue("必须含异常 message，实际=$reason", reason.contains("assets 打不开"))
    }

    @Test
    fun `无 message 的异常不会丢掉定位信息`() {
        PluginBlueprintStatus.recordFailure(
            reason = "宿主拒绝装载蓝图",
            failure = NullPointerException(),
        )

        val reason = (PluginBlueprintStatus.outcome() as Outcome.Failed).reason
        assertTrue(reason.contains("NullPointerException"))
        assertTrue("无 message 时用占位而不是 null，实际=$reason", reason.contains("(no message)"))
    }

    @Test
    fun `失败时可以带上失败前已装载的插件`() {
        PluginBlueprintStatus.recordFailure(
            reason = "第 3 条之后抛异常",
            blueprintId = "default",
            loaded = listOf("automation.core", "channel.qqbot"),
        )

        val outcome = PluginBlueprintStatus.outcome() as Outcome.Failed
        assertEquals(listOf("automation.core", "channel.qqbot"), outcome.loaded)
    }

    @Test
    fun `后一次记录覆盖前一次但 attempts 累加`() {
        // 语义：current 只回答「**最近一次**装载成没成」，attempts 回答「跑过几次」。
        PluginBlueprintStatus.recordFailure(reason = "第一次失败")
        assertTrue(PluginBlueprintStatus.hasFailed())

        PluginBlueprintStatus.recordApplied(
            blueprintId = "default",
            loaded = listOf("a"),
            skipped = emptyList(),
        )
        assertFalse("重试成功后不得继续报失败", PluginBlueprintStatus.hasFailed())
        assertTrue(PluginBlueprintStatus.outcome() is Outcome.Applied)
        assertEquals(2L, PluginBlueprintStatus.attempts())
    }

    @Test
    fun `记录不可变快照：调用方持有的 loaded 列表变化不影响已记录状态`() {
        val loaded = mutableListOf("a")
        PluginBlueprintStatus.recordApplied(blueprintId = "default", loaded = loaded, skipped = emptyList())
        loaded.add("b")

        val outcome = PluginBlueprintStatus.outcome() as Outcome.Applied
        assertEquals("记录时必须拷贝，否则外部可变列表能污染状态", listOf("a"), outcome.loaded)
    }

    @Test
    fun `Applied skipped 和 Failed loaded 输入变化不污染记录`() {
        val skipped = mutableListOf("failed:a(reason)", "disabled:b")
        PluginBlueprintStatus.recordApplied("default", emptyList(), skipped)
        skipped.clear()
        val applied = PluginBlueprintStatus.outcome() as Outcome.Applied
        assertEquals(listOf("failed:a(reason)", "disabled:b"), applied.skipped)

        val loaded = mutableListOf("a", "b")
        PluginBlueprintStatus.recordFailure("interrupted", loaded = loaded)
        loaded.clear()
        assertEquals(listOf("a", "b"), (PluginBlueprintStatus.outcome() as Outcome.Failed).loaded)
    }

    @Test
    fun `读取方不能修改已发布列表`() {
        PluginBlueprintStatus.recordApplied("default", listOf("a", "b"), listOf("disabled:c", "notfound:d"))
        val applied = PluginBlueprintStatus.current().outcome as Outcome.Applied
        assertUnmodifiable(applied.loaded)
        assertUnmodifiable(applied.skipped)
        PluginBlueprintStatus.recordFailure("interrupted", loaded = listOf("a", "b"))
        assertUnmodifiable((PluginBlueprintStatus.outcome() as Outcome.Failed).loaded)
        assertEquals(listOf("a", "b"), applied.loaded)
    }

    @Test
    fun `真实宿主局部失败仍为 Applied 且继续加载并保留幂等项`() {
        val host = PluginHostImpl(emptyMap(), NoOpPluginLog)
        fun plugin(id: String, fail: Boolean = false) = object : LianYuPlugin {
            override val id = id
            override val name = id
            override val kind = PluginKind.TOOL

            // LianYuPlugin 里只有 version 与 manifest 有默认实现；requires / configSchema 没有，
            // 匿名实现必须显式给出（同模块既有写法见 tools/ChannelSendToolTest.kt 的 rogue 桩）。
            override val requires: Set<String> = emptySet()
            override val configSchema: String? = null

            override fun setup(ctx: PluginContext) {
                if (fail) error("setup failed")
            }
        }
        host.register(plugin("already"))
        host.register(plugin("broken", fail = true))
        host.register(plugin("after"))
        host.load("already", null)
        try {
            val blueprint = PluginBlueprint("default", "default", listOf(
                BlueprintPluginRef("already"), BlueprintPluginRef("broken"), BlueprintPluginRef("after"),
            ))
            val result = host.loadBlueprint(blueprint) as BlueprintLoadResult.Applied
            PluginBlueprintStatus.recordApplied(blueprint.id, result.loaded, result.skipped)
            val outcome = PluginBlueprintStatus.outcome() as Outcome.Applied
            assertEquals(listOf("already", "after"), outcome.loaded)
            assertEquals(1, outcome.skipped.size)
            assertTrue(outcome.skipped.single().startsWith("failed:broken("))
            assertFalse(PluginBlueprintStatus.hasFailed())
            assertEquals(setOf("already", "after"), host.loadedIds())
        } finally {
            host.disposeAll()
        }
    }

    private fun assertUnmodifiable(list: List<String>) {
        try {
            (list as MutableList<String>).clear()
            org.junit.Assert.fail("published list must reject mutation")
        } catch (_: UnsupportedOperationException) {
            // Expected: a read-only Kotlin type alone would not protect the published snapshot.
        }
    }

    @Test
    fun `默认蓝图资产路径常量与实现一致`() {
        // YuNianApplication.loadDefaultBlueprint 直接用它 open assets，拼错就是「读不到资产」。
        assertEquals("blueprints/default.json", PluginBlueprintStatus.DEFAULT_BLUEPRINT_ASSET)
    }

    @Test
    fun `resetForTest 复位成未尝试并清零计数`() {
        PluginBlueprintStatus.recordFailure(reason = "x")
        PluginBlueprintStatus.resetForTest()

        assertTrue(PluginBlueprintStatus.outcome() is Outcome.NotAttempted)
        assertFalse(PluginBlueprintStatus.hasFailed())
        assertEquals(0L, PluginBlueprintStatus.attempts())
    }
}
