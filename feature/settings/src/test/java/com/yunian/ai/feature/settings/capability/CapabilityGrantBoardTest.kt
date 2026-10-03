package com.yunian.ai.feature.settings.capability

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.CapabilityDefaults
import com.yunian.ai.domain.CapabilityGrant
import com.yunian.ai.domain.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapabilityGrantBoard] 纯 JVM 单测（无 Android / 无 Robolectric / 无 Compose）。
 *
 * 覆盖验收要求的四条：
 * 1. 清单列出**全部** Agent tools（不再按 requiresConfirmation 过滤，也不再有通道分组）；
 * 2. 开关默认值 = `decisions[toolName] ?: !tool.requiresConfirmation`（与折叠点同一个函数）；
 * 3. 拨动开关 → `decide(CapabilityGrant(scopeCompanionId, toolName, value))`；
 * 4. 拨回该工具的默认值 → `clear(...)`（保持存储最小）。
 *
 * 这些断言与 `CapabilityGrantStoreImpl.decisionsFor` 的命中规则同源：
 * 「通配铺底 + 该伴侣的显式决定覆盖」——设置页显示什么，装配期就放行什么。
 */
class CapabilityGrantBoardTest {

    // ── 测试替身与工具函数 ──

    private class FakeTool(
        override val name: String,
        override val description: String = name + " 说明",
        private val confirm: Boolean = false,
    ) : AiTool {
        override val parametersJsonSchema: String = """{"type":"object","properties":{}}"""
        override val requiresConfirmation: Boolean get() = confirm
        override suspend fun execute(argumentsJson: String): String = ""
    }

    private fun confirmTool(name: String) = FakeTool(name, confirm = true)

    private fun normalTool(name: String) = FakeTool(name, confirm = false)

    private fun grantable(name: String, confirm: Boolean = false) =
        GrantableTool(name, name, name + " 说明", requiresConfirmation = confirm)

    private fun itemOf(items: List<GrantToolItem>, toolName: String) = items.first { it.toolName == toolName }

    private fun board(
        tools: List<GrantableTool>,
        decisions: List<CapabilityGrant>,
        scope: GrantScope,
    ) = CapabilityGrantBoard.items(tools, decisions, scope)

    // ── 1. 清单 = 全部 Agent tools ──

    @Test
    fun `grantableTools 列出全部工具不再过滤`() {
        val tools = listOf(
            confirmTool("screen_tap"),
            normalTool("device_get_time"),
            confirmTool("screen_click_text"),
            normalTool("web_fetch"),
        )

        assertEquals(
            listOf("device_get_time", "screen_click_text", "screen_tap", "web_fetch"),
            CapabilityGrantBoard.grantableTools(tools).map { it.name },
        )
    }

    @Test
    fun `grantableTools 逐条透传 requiresConfirmation`() {
        val tools = CapabilityGrantBoard.grantableTools(
            listOf(confirmTool("screen_tap"), normalTool("device_get_time")),
        )

        assertTrue(tools.first { it.name == "screen_tap" }.requiresConfirmation)
        assertFalse(tools.first { it.name == "device_get_time" }.requiresConfirmation)
    }

    @Test
    fun `grantableTools 给已知工具中文名，未收录工具回退工具名`() {
        val tools = CapabilityGrantBoard.grantableTools(
            listOf(confirmTool("screen_tap"), confirmTool("brand_new_tool")),
        )

        assertEquals("点击屏幕", tools.first { it.name == "screen_tap" }.displayName)
        assertEquals("brand_new_tool", tools.first { it.name == "brand_new_tool" }.displayName)
    }

    @Test
    fun `grantableTools 去重且顺序与入参顺序无关（顺序稳定）`() {
        val tools = listOf(confirmTool("screen_tap"), normalTool("screen_tap"), confirmTool("automation_create"))

        val forward = CapabilityGrantBoard.grantableTools(tools).map { it.name }
        val backward = CapabilityGrantBoard.grantableTools(tools.reversed()).map { it.name }

        assertEquals(listOf("automation_create", "screen_tap"), forward)
        assertEquals(forward, backward)
    }

    @Test
    fun `grantableTools 对空注册池返回空列表`() {
        assertTrue(CapabilityGrantBoard.grantableTools(emptyList()).isEmpty())
    }

    // ── 2. 开关默认值 ──

    @Test
    fun `无任何决定时：危险工具默认关（需要确认）、安全工具默认开（允许）`() {
        val tools = listOf(grantable("screen_tap", confirm = true), grantable("device_get_time"))
        val items = board(tools, emptyList(), GrantScope.AllCompanions)

        assertFalse("默认需确认的工具，开关默认关闭", itemOf(items, "screen_tap").allowed)
        assertTrue("默认允许的工具，开关默认打开", itemOf(items, "device_get_time").allowed)
        assertFalse("没有任何显式决定", itemOf(items, "screen_tap").explicit)
        assertFalse(itemOf(items, "device_get_time").explicit)
    }

    @Test
    fun `开关默认值必须与 CapabilityDefaults 同源`() {
        val tools = listOf(grantable("a_confirm", confirm = true), grantable("b_safe", confirm = false))

        for (explicit in listOf<Boolean?>(null, true, false)) {
            val decisions = if (explicit == null) {
                emptyList()
            } else {
                tools.map { CapabilityGrant(null, it.name, allowed = explicit) }
            }
            val items = board(tools, decisions, GrantScope.AllCompanions)
            for (tool in tools) {
                val expected = !CapabilityDefaults.requiresConfirm(explicit, tool.requiresConfirmation)
                assertEquals(
                    "${tool.name}（explicit=$explicit）的开关值必须等于 CapabilityDefaults 的结果",
                    expected,
                    itemOf(items, tool.name).allowed,
                )
            }
        }
    }

    @Test
    fun `显式允许把危险工具点亮、显式禁止把安全工具熄灭（两向）`() {
        val tools = listOf(grantable("screen_tap", confirm = true), grantable("device_get_time"))

        val allowedAll = board(
            tools,
            tools.map { CapabilityGrant(null, it.name, allowed = true) },
            GrantScope.AllCompanions,
        )
        assertTrue(itemOf(allowedAll, "screen_tap").allowed)
        assertTrue(itemOf(allowedAll, "device_get_time").allowed)

        val deniedAll = board(
            tools,
            tools.map { CapabilityGrant(null, it.name, allowed = false) },
            GrantScope.AllCompanions,
        )
        assertFalse(itemOf(deniedAll, "screen_tap").allowed)
        assertFalse(
            "本来不需要确认的安全工具，被用户显式改成必须先确认",
            itemOf(deniedAll, "device_get_time").allowed,
        )
    }

    // ── 3. 通配与具体伴侣的优先级 ──

    @Test
    fun `全部伴侣视图只认通配记录，具体伴侣的记录不算数`() {
        val tools = listOf(grantable("screen_tap", confirm = true))

        val onlySpecific = board(tools, listOf(CapabilityGrant(42L, "screen_tap", allowed = true)), GrantScope.AllCompanions)
        assertFalse("通配视图的开关写的就是通配记录", itemOf(onlySpecific, "screen_tap").allowed)
        assertFalse(itemOf(onlySpecific, "screen_tap").explicit)

        val wildcard = board(tools, listOf(CapabilityGrant(null, "screen_tap", allowed = true)), GrantScope.AllCompanions)
        assertTrue(itemOf(wildcard, "screen_tap").allowed)
        assertTrue(itemOf(wildcard, "screen_tap").explicit)
    }

    @Test
    fun `具体伴侣视图：通配铺底，本伴侣的显式决定覆盖（两个方向）`() {
        val tools = listOf(grantable("screen_tap", confirm = true))

        val wildcardAllowed = board(
            tools,
            listOf(CapabilityGrant(null, "screen_tap", allowed = true)),
            GrantScope.Companion(42L),
        )
        assertTrue("只靠通配也应显示为已放行", itemOf(wildcardAllowed, "screen_tap").allowed)
        assertFalse("本伴侣自己没有记录", itemOf(wildcardAllowed, "screen_tap").explicit)

        val ownDenied = board(
            tools,
            listOf(
                CapabilityGrant(null, "screen_tap", allowed = true),
                CapabilityGrant(42L, "screen_tap", allowed = false),
            ),
            GrantScope.Companion(42L),
        )
        assertFalse("本伴侣的显式禁止必须压过通配允许", itemOf(ownDenied, "screen_tap").allowed)
        assertTrue(itemOf(ownDenied, "screen_tap").explicit)

        val otherCompanion = board(
            tools,
            listOf(CapabilityGrant(43L, "screen_tap", allowed = false)),
            GrantScope.Companion(42L),
        )
        assertFalse("43 号的禁止不得影响 42 号", itemOf(otherCompanion, "screen_tap").explicit)
        assertFalse(itemOf(otherCompanion, "screen_tap").allowed)
    }

    @Test
    fun `effectiveDecisions 与 decisionsFor 规则一致`() {
        val decisions = listOf(
            CapabilityGrant(null, "screen_tap", allowed = true),
            CapabilityGrant(42L, "screen_tap", allowed = false),
            CapabilityGrant(42L, "screen_swipe", allowed = true),
            CapabilityGrant(43L, "screen_click_text", allowed = false),
        )

        assertEquals(
            mapOf("screen_tap" to true),
            CapabilityGrantBoard.effectiveDecisions(GrantScope.AllCompanions, decisions),
        )
        assertEquals(
            mapOf("screen_tap" to false, "screen_swipe" to true),
            CapabilityGrantBoard.effectiveDecisions(GrantScope.Companion(42L), decisions),
        )
        assertEquals(
            mapOf("screen_tap" to true, "screen_click_text" to false),
            CapabilityGrantBoard.effectiveDecisions(GrantScope.Companion(43L), decisions),
        )
    }

    @Test
    fun `清单顺序与入参一致（由 grantableTools 决定，不重排）`() {
        val tools = listOf(grantable("a_tool"), grantable("m_tool"), grantable("z_tool", confirm = true))
        assertEquals(
            listOf("a_tool", "m_tool", "z_tool"),
            board(tools, emptyList(), GrantScope.AllCompanions).map { it.toolName },
        )
    }

    // ── 4. 拨动开关 → decide / clear ──

    @Test
    fun `危险工具打开 ⇒ decide(true)`() {
        val tool = grantable("screen_tap", confirm = true)
        assertEquals(
            GrantWrite.Decide(CapabilityGrant(null, "screen_tap", allowed = true)),
            CapabilityGrantBoard.write(GrantScope.AllCompanions, tool, allowed = true),
        )
        assertEquals(
            GrantWrite.Decide(CapabilityGrant(42L, "screen_tap", allowed = true)),
            CapabilityGrantBoard.write(GrantScope.Companion(42L), tool, allowed = true),
        )
    }

    @Test
    fun `安全工具关闭 ⇒ decide(false)`() {
        val tool = grantable("device_get_time", confirm = false)
        assertEquals(
            GrantWrite.Decide(CapabilityGrant(null, "device_get_time", allowed = false)),
            CapabilityGrantBoard.write(GrantScope.AllCompanions, tool, allowed = false),
        )
        assertEquals(
            GrantWrite.Decide(CapabilityGrant(1024L, "device_get_time", allowed = false)),
            CapabilityGrantBoard.write(GrantScope.Companion(1024L), tool, allowed = false),
        )
    }

    @Test
    fun `拨回默认 ⇒ clear（存储最小化）：危险工具关、安全工具开`() {
        assertEquals(
            GrantWrite.Clear(null, "screen_tap"),
            CapabilityGrantBoard.write(GrantScope.AllCompanions, grantable("screen_tap", confirm = true), allowed = false),
        )
        assertEquals(
            GrantWrite.Clear(null, "device_get_time"),
            CapabilityGrantBoard.write(GrantScope.AllCompanions, grantable("device_get_time"), allowed = true),
        )
        assertEquals(
            GrantWrite.Clear(42L, "screen_tap"),
            CapabilityGrantBoard.write(GrantScope.Companion(42L), grantable("screen_tap", confirm = true), allowed = false),
        )
        assertEquals(
            GrantWrite.Clear(42L, "device_get_time"),
            CapabilityGrantBoard.write(GrantScope.Companion(42L), grantable("device_get_time"), allowed = true),
        )
    }

    @Test
    fun `通配放行的工具在本伴侣关掉 ⇒ decide(false)`() {
        val tool = grantable("screen_tap", confirm = true)
        assertEquals(
            "关掉一个被通配放行的项必须写出本伴侣的显式禁止，否则下一回合它又被通配放行",
            GrantWrite.Decide(CapabilityGrant(42L, "screen_tap", allowed = false)),
            CapabilityGrantBoard.write(
                scope = GrantScope.Companion(42L),
                tool = tool,
                allowed = false,
                inheritedAllowed = true,
            ),
        )
        assertEquals(
            "反过来：本伴侣打开一个被通配禁止的项，同样必须写自己的 decide(true)",
            GrantWrite.Decide(CapabilityGrant(42L, "screen_tap", allowed = true)),
            CapabilityGrantBoard.write(
                scope = GrantScope.Companion(42L),
                tool = tool,
                allowed = true,
                inheritedAllowed = false,
            ),
        )
    }

    @Test
    fun `通配给的值与用户所选一致 ⇒ 仍然 clear（存储最小化）`() {
        val tool = grantable("screen_tap", confirm = true)
        assertEquals(
            GrantWrite.Clear(42L, "screen_tap"),
            CapabilityGrantBoard.write(
                scope = GrantScope.Companion(42L),
                tool = tool,
                allowed = false,
                inheritedAllowed = false,
            ),
        )
        assertEquals(
            GrantWrite.Clear(42L, "device_get_time"),
            CapabilityGrantBoard.write(
                scope = GrantScope.Companion(42L),
                tool = grantable("device_get_time"),
                allowed = true,
                inheritedAllowed = true,
            ),
        )
    }

    @Test
    fun `具体伴侣视图把通配状态暴露为 inheritedAllowed`() {
        val tools = listOf(grantable("screen_tap", confirm = true))
        val wildcardAllowed = listOf(CapabilityGrant(null, "screen_tap", allowed = true))

        val inherited = board(tools, wildcardAllowed, GrantScope.Companion(42L)).single()
        assertTrue("本伴侣自己没有记录", !inherited.explicit)
        assertEquals("生效值来自通配", true, inherited.inheritedAllowed)

        val own = board(
            tools,
            wildcardAllowed + CapabilityGrant(42L, "screen_tap", allowed = false),
            GrantScope.Companion(42L),
        ).single()
        assertTrue(own.explicit)
        assertTrue("本伴侣自己覆盖后就没有继承值", own.inheritedAllowed == null)

        val none = board(tools, emptyList(), GrantScope.AllCompanions).single()
        assertTrue("全部伴侣视图没有继承来源", none.inheritedAllowed == null)
    }

    // ── 3b. 来源说明的显示条件（showsWildcardSource） ──
    //
    // 这一组是**真机缺陷的回归护栏**：修复前渲染层的条件是 `!explicit && !allowed`，漏了作用域，
    // 于是「全部伴侣」视图 + 存储为空（无任何记录）时，任何默认关闭的工具都会显示
    // 「当前状态来自『全部伴侣（通配）』设置」——而那时根本不存在任何通配决定。
    // 判据现已收进 CapabilityGrantBoard，四条断言把「什么时候显示 / 什么时候不显示」钉死。

    @Test
    fun `全部伴侣视图 + 未设过的工具 ⇒ 不显示来源说明`() {
        val tools = listOf(grantable("automation_create", confirm = true))

        // 真机现场：存储里一条记录都没有（当时已 grep 确认）。
        val empty = board(tools, emptyList(), GrantScope.AllCompanions).single()
        assertFalse("存储为空时，开关默认关闭", empty.allowed)
        assertFalse("没有任何显式决定", empty.explicit)
        assertFalse(
            "修复前这里为 true：会声称状态来自一个用户从没做过的通配设置",
            empty.showsWildcardSource,
        )

        // 通配**有**决定时，来源就是这一行自己（全部伴侣视图写的就是通配记录），仍不显示来源说明。
        val wildcardOff = board(
            tools,
            listOf(CapabilityGrant(null, "automation_create", allowed = false)),
            GrantScope.AllCompanions,
        ).single()
        assertTrue("通配自己关掉它", wildcardOff.explicit)
        assertFalse("本视图就是通配本身，不需要来源说明", wildcardOff.showsWildcardSource)
    }

    @Test
    fun `具体伴侣视图 + 只有通配决定 + 通配放行 ⇒ 显示来源说明`() {
        val tools = listOf(grantable("screen_tap", confirm = true))

        val allowed = board(
            tools,
            listOf(CapabilityGrant(null, "screen_tap", allowed = true)),
            GrantScope.Companion(42L),
        ).single()
        assertFalse("本伴侣自己没有记录", allowed.explicit)
        assertTrue("状态由通配放行", allowed.allowed)
        assertTrue("必须告诉用户这一行来自通配", allowed.showsWildcardSource)

        val denied = board(
            tools,
            listOf(CapabilityGrant(null, "screen_tap", allowed = false)),
            GrantScope.Companion(42L),
        ).single()
        assertFalse("通配也可以给「需确认」", denied.allowed)
        assertTrue(
            "通配给的值与工具默认同向时同样是「来自通配」，不能因为 allowed 为假就吞掉来源说明",
            denied.showsWildcardSource,
        )
    }

    @Test
    fun `具体伴侣视图 + 自己有显式决定 ⇒ 不显示来源说明`() {
        val tools = listOf(grantable("screen_tap", confirm = true))

        val own = board(
            tools,
            listOf(
                CapabilityGrant(null, "screen_tap", allowed = true),
                CapabilityGrant(42L, "screen_tap", allowed = false),
            ),
            GrantScope.Companion(42L),
        ).single()
        assertTrue("本伴侣自己覆盖了通配", own.explicit)
        assertFalse("来源是本伴侣自己的决定，不是通配", own.showsWildcardSource)
    }

    @Test
    fun `具体伴侣视图 + 通配无决定 ⇒ 不显示来源说明`() {
        val tools = listOf(grantable("screen_tap", confirm = true))

        // 本伴侣视图 + 存储为空：状态来自**工具默认**，不是通配。
        val empty = board(tools, emptyList(), GrantScope.Companion(42L)).single()
        assertFalse("没有任何决定", empty.explicit)
        assertFalse(empty.showsWildcardSource)

        // 只有别的伴侣的记录：同样不构成继承来源。
        val other = board(
            tools,
            listOf(CapabilityGrant(43L, "screen_tap", allowed = true)),
            GrantScope.Companion(42L),
        ).single()
        assertFalse("43 号的记录不算 42 号的来源", other.explicit)
        assertFalse("通配没有决定就没有来源说明", other.showsWildcardSource)
    }

    @Test
    fun `工具名为空白 ⇒ 不产生任何写入`() {
        assertEquals(
            GrantWrite.None,
            CapabilityGrantBoard.write(GrantScope.AllCompanions, grantable("   "), allowed = true),
        )
        assertEquals(
            GrantWrite.None,
            CapabilityGrantBoard.write(GrantScope.Companion(42L), grantable(""), allowed = false),
        )
    }

    @Test
    fun `写入记录严格使用当前作用域的 companionId`() {
        val tool = grantable("screen_tap", confirm = true)
        assertEquals(
            GrantWrite.Decide(CapabilityGrant(null, "screen_tap", allowed = true)),
            CapabilityGrantBoard.write(GrantScope.AllCompanions, tool, allowed = true),
        )
        assertEquals(
            GrantWrite.Decide(CapabilityGrant(1024L, "screen_tap", allowed = true)),
            CapabilityGrantBoard.write(GrantScope.Companion(1024L), tool, allowed = true),
        )
    }

    @Test
    fun `作用域名称：全部伴侣固定文案，具体伴侣回落显示 id`() {
        val companions = listOf(CompanionOption(42L, "小念"))

        assertEquals("全部伴侣（通配）", scopeDisplayName(GrantScope.AllCompanions, companions))
        assertEquals("小念", scopeDisplayName(GrantScope.Companion(42L), companions))
        assertEquals("伴侣 #99", scopeDisplayName(GrantScope.Companion(99L), companions))
    }

    // ── 5. 容量：清单必须能承载全部 Agent tools（当前生产注册点 37 处 + MCP 动态工具） ──

    @Test
    fun `清单承载全部工具：40 个工具全部成行、顺序稳定、默认值正确`() {
        val pool = (1..40).map { i ->
            GrantableTool(
                name = "tool_%02d".format(i),
                displayName = "工具 $i",
                description = "第 $i 个工具",
                requiresConfirmation = i % 4 == 0,
            )
        }

        val items = CapabilityGrantBoard.items(
            tools = pool,
            decisions = listOf(CapabilityGrant(null, "tool_01", allowed = true)),
            scope = GrantScope.AllCompanions,
        )

        assertEquals("每个工具一行，不丢行", 40, items.size)
        assertEquals("顺序与入池顺序一致（稳定）", pool.map { it.name }, items.map { it.toolName })
        assertEquals("默认需确认的有 10 个", 10, items.count { it.requiresConfirmation })
        assertEquals("默认关闭的有 10 个", 10, items.count { !it.allowed })
        assertEquals("被通配打开的那 1 个不计入默认关闭", 1, items.count { it.explicit })
    }

    // ── 6. 与真实 ToolRegistry 的接线契约 ──

    @Test
    fun `真实 ToolRegistry 注册池：确认类与普通工具都在清单里`() {
        val confirm = FakeTool("test_capability_confirm_tool", confirm = true)
        val plain = FakeTool("test_capability_plain_tool", confirm = false)
        ToolRegistry.register(confirm)
        ToolRegistry.register(plain)

        try {
            val items = CapabilityGrantBoard
                .grantableTools(ToolRegistry.availableTools(includeAppLocal = true))

            assertTrue(items.any { it.name == "test_capability_confirm_tool" && it.requiresConfirmation })
            assertTrue(
                "普通工具也必须在清单里（两向开关要能把它改成「必须先确认」）",
                items.any { it.name == "test_capability_plain_tool" && !it.requiresConfirmation },
            )
        } finally {
            ToolRegistry.unregister(confirm.name)
            ToolRegistry.unregister(plain.name)
        }
    }

    @Test
    fun `真实 ToolRegistry：本机敏感工具也在清单里`() {
        val local = object : AiTool {
            override val name: String = "test_capability_app_local_tool"
            override val description: String = "本机工具"
            override val parametersJsonSchema: String = """{"type":"object","properties":{}}"""
            override val appLocalOnly: Boolean = true
            override val requiresConfirmation: Boolean = true
            override suspend fun execute(argumentsJson: String): String = ""
        }
        ToolRegistry.register(local)

        try {
            val items = CapabilityGrantBoard
                .grantableTools(ToolRegistry.availableTools(includeAppLocal = true))

            assertTrue(
                "清单来源是 availableTools(includeAppLocal = true)，本机工具必须出现",
                items.any { it.name == local.name && it.requiresConfirmation },
            )
        } finally {
            ToolRegistry.unregister(local.name)
        }
    }
}
