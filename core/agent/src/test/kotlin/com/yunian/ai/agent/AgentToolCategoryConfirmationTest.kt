package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.ToolCategory
import com.yunian.ai.domain.AiTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认门控接线契约测试：`AiTool.requiresConfirmation` → Rust `ToolCategory`。
 *
 * 背景（为什么这个映射是安全缺陷修复而不是普通重构）：
 * - Rust 侧唯一门控判定在 `agent-native/src/agent.rs`：
 *   `let needs_confirm = definition.is_some_and(|t| t.category == ToolCategory::Commerce);`
 * - `ToolDefinition` 的 JSON 投影只带 name / description / parameters_json，不带 category，
 *   也不带 requiresConfirmation；`ToolCategory` 是 Kotlin 侧 `toolDefinition()` 装配时算出来的。
 * - 修复前 `deriveToolCategory` 只读 toolsets，而全部声明 requiresConfirmation = true 的工具
 *   toolsets 为空（= GENERAL），因此这道门在生产环境零命中——声明等于摆设。
 *
 * 本测试把两件事钉死：
 * 1) requiresConfirmation = true ⇒ COMMERCE（门控生效，且优先于任何工具集规则）；
 * 2) requiresConfirmation = false ⇒ 既有工具集映射逐条不变（不改变任何既有类别）。
 *
 * 全部调用都显式传 `emptyMap()`（= 没有任何显式授权决定），因此本文件钉死的是
 * 「**无决定**时的默认行为」；带决定的折叠语义由 `CapabilityGrantFoldTest` 覆盖。
 *
 * 假工具只声明 name / toolsets / requiresConfirmation，取值逐条对齐生产声明；
 * 位置说明：`:core:agent` 不能反向依赖 feature 模块，故不直接引用真实工具类。
 */
class AgentToolCategoryConfirmationTest {

    private class FakeTool(
        override val name: String,
        override val toolsets: Set<String> = emptySet(),
        override val requiresConfirmation: Boolean = false,
    ) : AiTool {
        override val description: String = "fake: $name"
        override val parametersJsonSchema: String = """{"type":"object","properties":{}}"""
        override suspend fun execute(argumentsJson: String): String = "{}"
    }

    /**
     * 生产侧全部静态 `requiresConfirmation = true` 的工具（名字 / 工具集逐条对齐源码）。
     *
     * **从 [PRODUCTION_CONFIRM_TOOL_NAMES] 派生**，不再本地再维护一份镜像——
     * 此前两处各自写着「6 个」，于是同一个新工具被两处同时漏掉。
     * 这些工具的 `toolsets` 都未声明（= 空集），因此它们只能靠确认语义被拦下，
     * 而不是靠工具集规则——这正是本用例要证明的。
     */
    private val confirmationRequiredTools: List<FakeTool> =
        PRODUCTION_CONFIRM_TOOL_NAMES.map { FakeTool(name = it, requiresConfirmation = true) }

    @Test
    fun `六个 requiresConfirmation = true 的生产工具全部映射为 COMMERCE`() {
        // 断言与唯一镜像一致：本用例不会因为「本地列表少写一个」而静默通过。
        assertEquals(PRODUCTION_CONFIRM_TOOL_NAMES.size, confirmationRequiredTools.size)
        assertEquals(6, PRODUCTION_CONFIRM_TOOL_NAMES.size)
        for (tool in confirmationRequiredTools) {
            assertEquals(
                "${tool.name} 声明了 requiresConfirmation = true，必须被门控拦下（COMMERCE）",
                ToolCategory.COMMERCE,
                AgentFacade.deriveToolCategory(tool, emptyMap()),
            )
            assertNotEquals(
                "${tool.name} 修复前落到 GENERAL（toolsets 为空），不得回退",
                ToolCategory.GENERAL,
                AgentFacade.deriveToolCategory(tool, emptyMap()),
            )
        }
    }

    @Test
    fun `装配入口 toolDefinition 得到 COMMERCE（接线在真实路径上）`() {
        for (tool in confirmationRequiredTools) {
            assertEquals(
                "${tool.name} 经 AgentFacade.toolDefinition 装配后 category 即 Rust 门控的输入",
                ToolCategory.COMMERCE,
                AgentFacade.toolDefinition(tool, AgentFacade.deriveToolCategory(tool, emptyMap())).category,
            )
        }
    }

    @Test
    fun `requiresConfirmation = true 优先于空工具集规则`() {
        assertEquals(
            ToolCategory.COMMERCE,
            AgentFacade.deriveToolCategory(FakeTool(name = "empty_set_confirm", requiresConfirmation = true), emptyMap()),
        )
        assertEquals(
            "requiresConfirmation = false 时空集仍必须是 GENERAL（既有映射不变）",
            ToolCategory.GENERAL,
            AgentFacade.deriveToolCategory(FakeTool(name = "empty_set_no_confirm"), emptyMap()),
        )
    }

    @Test
    fun `requiresConfirmation = true 优先于各种工具集规则`() {
        for (toolset in listOf("memory", "chat", "commerce", "domain", "skill")) {
            assertEquals(
                "确认声明压过工具集规则：toolsets=$toolset",
                ToolCategory.COMMERCE,
                AgentFacade.deriveToolCategory(
                    FakeTool(name = "confirm_with_$toolset", toolsets = setOf(toolset), requiresConfirmation = true),
                    emptyMap(),
                ),
            )
        }
        assertEquals(
            "多工具集同时命中时确认声明同样优先",
            ToolCategory.COMMERCE,
            AgentFacade.deriveToolCategory(
                FakeTool(name = "confirm_multi", toolsets = setOf("memory", "chat"), requiresConfirmation = true),
                emptyMap(),
            ),
        )
    }

    @Test
    fun `requiresConfirmation = false 时既有工具集映射逐条不变`() {
        assertEquals(
            "空工具集 → GENERAL",
            ToolCategory.GENERAL,
            AgentFacade.deriveToolCategory(FakeTool(name = "plain_general"), emptyMap()),
        )
        assertEquals(
            "commerce → COMMERCE",
            ToolCategory.COMMERCE,
            AgentFacade.deriveToolCategory(FakeTool(name = "plain_commerce", toolsets = setOf("commerce")), emptyMap()),
        )
        assertEquals(
            "memory → MEMORY",
            ToolCategory.MEMORY,
            AgentFacade.deriveToolCategory(FakeTool(name = "plain_memory", toolsets = setOf("memory")), emptyMap()),
        )
        assertEquals(
            "chat → CHAT",
            ToolCategory.CHAT,
            AgentFacade.deriveToolCategory(FakeTool(name = "plain_chat", toolsets = setOf("chat")), emptyMap()),
        )
        assertEquals(
            "未知非空工具集 → CUSTOM",
            ToolCategory.CUSTOM,
            AgentFacade.deriveToolCategory(FakeTool(name = "plain_domain", toolsets = setOf("domain")), emptyMap()),
        )
        assertEquals(
            "多工具集按 commerce > memory > chat 的既有优先级",
            ToolCategory.MEMORY,
            AgentFacade.deriveToolCategory(FakeTool(name = "plain_multi", toolsets = setOf("chat", "memory")), emptyMap()),
        )
    }

    @Test
    fun `requiresConfirmation = false 的既有代表工具保持原类别（回归护栏）`() {
        // 回归护栏：这些工具在修复前后必须得到完全相同的类别，否则本修复扩大了门控范围。
        val unchanged = mapOf(
            FakeTool(name = "screen_read") to ToolCategory.GENERAL,
            FakeTool(name = "accessibility_status") to ToolCategory.GENERAL,
            FakeTool(name = "automation_list") to ToolCategory.GENERAL,
            FakeTool(name = "automation_cancel") to ToolCategory.GENERAL,
            FakeTool(name = "earn_memory", toolsets = setOf("memory")) to ToolCategory.MEMORY,
            FakeTool(name = "bubble", toolsets = setOf("chat")) to ToolCategory.CHAT,
            FakeTool(name = "legacy_commerce", toolsets = setOf("commerce")) to ToolCategory.COMMERCE,
            FakeTool(name = "plugin_custom", toolsets = setOf("plugin")) to ToolCategory.CUSTOM,
        )
        for ((tool, expected) in unchanged) {
            assertEquals("${tool.name} 的既有类别不得被本修复改动", expected, AgentFacade.deriveToolCategory(tool, emptyMap()))
        }
    }

    @Test
    fun `动态声明的 requiresConfirmation（MCP 审批）同样进入门控`() {
        // McpToolAdapter.kt:21：override val requiresConfirmation: Boolean = mcpTool.needsApproval
        val mcpNeedsApproval = FakeTool(name = "mcp_demo_server_pay", requiresConfirmation = true)
        val mcpNoApproval = FakeTool(name = "mcp_demo_server_read", requiresConfirmation = false)
        assertEquals(
            ToolCategory.COMMERCE,
            AgentFacade.deriveToolCategory(mcpNeedsApproval, emptyMap()),
        )
        assertEquals(
            ToolCategory.GENERAL,
            AgentFacade.deriveToolCategory(mcpNoApproval, emptyMap()),
        )
    }

    @Test
    fun `显式 category 参数仍是可用覆盖口（装配期推导走确认门控）`() {
        val confirmTool = FakeTool(name = "explicit_override_probe", requiresConfirmation = true)
        assertEquals(
            "无决定时 deriveToolCategory 必须给出 COMMERCE",
            ToolCategory.COMMERCE,
            AgentFacade.toolDefinition(confirmTool, AgentFacade.deriveToolCategory(confirmTool, emptyMap())).category,
        )
        assertEquals(
            "显式传入的 category 依旧优先（既有行为不变，本修复不引入新分支）",
            ToolCategory.CUSTOM,
            AgentFacade.toolDefinition(confirmTool, ToolCategory.CUSTOM).category,
        )
        assertTrue(
            "装配结果必须保留工具集透传，避免影响其它消费者",
            AgentFacade.toolDefinition(
                FakeTool(name = "toolsets_passthrough", toolsets = setOf("memory"), requiresConfirmation = true),
                AgentFacade.deriveToolCategory(
                    FakeTool(name = "toolsets_passthrough", toolsets = setOf("memory"), requiresConfirmation = true),
                    emptyMap(),
                ),
            ).toolsets == listOf("memory"),
        )
    }
}