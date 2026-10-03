package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.ToolCategory
import com.yunian.ai.domain.CapabilityGrant
import com.yunian.ai.domain.CapabilityGrantStore
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权**折叠点**（[AgentFacade.toolDefinitionsFor]）的契约测试。
 *
 * 接线背景：Rust 侧唯一确认门判定是 `definition.category == ToolCategory::Commerce`
 * （agent-native/src/agent.rs），而 category 只在 Kotlin **装配期**由
 * [AgentFacade.deriveToolCategory] 算出。因此「已授权 ⇒ 不受确认门拦截」只能在装配期折叠。
 *
 * 授权**不含通道维度**（P2-2d）：判定只按 (伴侣 × 工具) 命中，
 * 同一批 tools 在 App 内单聊 / 群聊 / QQ / 微信上得到完全相同的类别。
 *
 * 四条不变量逐条钉死（任何一条被破坏都意味着安全边界被改动）：
 * 1. **fail-closed**：无决定 / 存储未注册 / 存储读失败 ⇒ 与引入授权表前逐字一致；
 * 2. **两向**：显式 `allowed = true` 解除强制 COMMERCE；显式 `allowed = false` **施加** COMMERCE
 *    （连本来不需要确认的安全工具也一样）；
 * 3. **伴侣隔离**：为伴侣 A 的决定不得影响伴侣 B（通配除外）；
 * 4. **不得削弱工具自身工具集**：`commerce` 工具集无论怎么授权都仍是 COMMERCE。
 */
class CapabilityGrantFoldTest {

    private val companionA = 42L
    private val companionB = 43L

    @After
    fun tearDown() {
        ServiceRegistry.unregister(CapabilityGrantStore::class.java)
    }

    /** 把存储装进 ServiceRegistry（生产路径同样是 app 在这里绑定实现）。 */
    private fun install(store: CapabilityGrantStore) {
        ServiceRegistry.unregister(CapabilityGrantStore::class.java)
        ServiceRegistry.registerSingleton(CapabilityGrantStore::class.java) { store }
    }

    private fun removeStore() {
        ServiceRegistry.unregister(CapabilityGrantStore::class.java)
    }

    private suspend fun fold(companionId: Long, tools: List<FakeTool>) =
        AgentFacade.toolDefinitionsFor(companionId, tools)

    private suspend fun categoryOf(
        companionId: Long,
        tools: List<FakeTool>,
        toolName: String,
    ): ToolCategory? = fold(companionId, tools).firstOrNull { it.name == toolName }?.category

    /** 基线：无任何显式决定时 [AgentFacade.deriveToolCategory] 的输出。 */
    private fun baseline(tool: FakeTool): ToolCategory =
        AgentFacade.deriveToolCategory(tool, emptyMap())

    private suspend fun assertAllMatchBaseline(companionId: Long, tools: List<FakeTool>) {
        val folded = fold(companionId, tools)
        assertEquals("装配数量与顺序必须与入参一致", tools.map { it.name }, folded.map { it.name })
        for ((tool, definition) in tools.zip(folded)) {
            assertEquals(
                "${tool.name} 的 category 必须与既有映射逐字一致（无决定 / 无授权语义）",
                baseline(tool),
                definition.category,
            )
        }
    }

    // ── 验收要求的四条不变量 ──

    @Test
    fun `危险工具无决定 ⇒ COMMERCE`() = runBlocking {
        install(FakeCapabilityGrantStore())
        val tools = productionConfirmTools()
        assertAllMatchBaseline(companionA, tools)
        for (tool in tools) {
            assertEquals(
                "${tool.name} 没有任何显式决定时必须走确认门",
                ToolCategory.COMMERCE,
                categoryOf(companionA, tools, tool.name),
            )
        }
    }

    @Test
    fun `危险工具显式允许 ⇒ 不再 COMMERCE（回落到自身工具集映射）`() = runBlocking {
        install(FakeCapabilityGrantStore(listOf(CapabilityGrant(companionA, "screen_tap", allowed = true))))
        val tools = productionConfirmTools()
        assertEquals(ToolCategory.GENERAL, categoryOf(companionA, tools, "screen_tap"))
        assertEquals(
            "同一批里的其它工具不受影响",
            ToolCategory.COMMERCE,
            categoryOf(companionA, tools, "automation_create"),
        )
        assertEquals(
            "恰好 1 个工具被放行（不能多）",
            1,
            fold(companionA, tools).count { it.category != ToolCategory.COMMERCE },
        )
    }

    @Test
    fun `安全工具显式禁止 ⇒ 变成 COMMERCE（两向开关的向下方向）`() = runBlocking {
        val safe = FakeTool(name = "device_get_time")
        assertEquals("前置条件：这个工具自身不要求确认", ToolCategory.GENERAL, baseline(safe))

        install(FakeCapabilityGrantStore(listOf(CapabilityGrant(companionA, safe.name, allowed = false))))
        assertEquals(
            "用户显式要求先确认 ⇒ 必须被门控拦下，即使工具自己没声明",
            ToolCategory.COMMERCE,
            categoryOf(companionA, listOf(safe), safe.name),
        )
    }

    @Test
    fun `安全工具无决定 ⇒ 非 COMMERCE（与引入授权表前逐字一致）`() = runBlocking {
        install(FakeCapabilityGrantStore())
        val safe = listOf(
            FakeTool(name = "screen_read"),
            FakeTool(name = "earn_memory", toolsets = setOf("memory")),
            FakeTool(name = "bubble", toolsets = setOf("chat")),
            FakeTool(name = "plugin_custom", toolsets = setOf("plugin")),
        )
        assertAllMatchBaseline(companionA, safe)
        assertTrue(
            "无决定的安全工具一个都不该是 COMMERCE",
            fold(companionA, safe).none { it.category == ToolCategory.COMMERCE },
        )
    }

    @Test
    fun `安全工具显式允许 ⇒ 仍然非 COMMERCE（授权只解除确认，不改变类别）`() = runBlocking {
        val safe = listOf(
            FakeTool(name = "screen_read"),
            FakeTool(name = "earn_memory", toolsets = setOf("memory")),
        )
        install(
            FakeCapabilityGrantStore(
                safe.map { CapabilityGrant(companionA, it.name, allowed = true) },
            ),
        )
        assertAllMatchBaseline(companionA, safe)
        assertEquals(ToolCategory.GENERAL, categoryOf(companionA, safe, "screen_read"))
        assertEquals(ToolCategory.MEMORY, categoryOf(companionA, safe, "earn_memory"))
    }

    // ── fail-closed ──

    @Test
    fun `没有注册任何授权存储时全部确认类工具维持 COMMERCE`() = runBlocking {
        removeStore()
        val tools = productionConfirmTools()
        assertAllMatchBaseline(companionA, tools)
        assertTrue(tools.all { categoryOf(companionA, tools, it.name) == ToolCategory.COMMERCE })
    }

    @Test
    fun `存储读取抛异常时 fail-closed（不得因授权表故障放松任何工具）`() = runBlocking {
        install(FakeCapabilityGrantStore(failure = IllegalStateException("kv down")))
        val tools = productionConfirmTools()
        assertAllMatchBaseline(companionA, tools)
        assertTrue(tools.all { categoryOf(companionA, tools, it.name) == ToolCategory.COMMERCE })
    }

    @Test
    fun `KV 内容损坏时等同无决定（fail-closed 且不抛异常）`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(CapabilityGrantStoreImpl.KEY, "\u0000GARBAGE\u0000")
        install(grantStoreOn(dao))
        assertAllMatchBaseline(companionA, productionConfirmTools())
    }

    @Test
    fun `旧的三段式（含通道维度）行被当坏行丢弃 ⇒ 等同无决定 ⇒ fail-closed`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(
            CapabilityGrantStoreImpl.KEY,
            listOf("42|qqbot|screen_tap", "*|wechat|automation_create").joinToString("\n"),
        )
        install(grantStoreOn(dao))
        assertAllMatchBaseline(companionA, productionConfirmTools())
        assertTrue(
            "旧格式不得意外放行任何工具",
            fold(companionA, productionConfirmTools()).all { it.category == ToolCategory.COMMERCE },
        )
    }

    // ── 伴侣隔离与通配 ──

    @Test
    fun `伴侣隔离：为伴侣 A 的决定不得影响伴侣 B`() = runBlocking {
        install(FakeCapabilityGrantStore(listOf(CapabilityGrant(companionA, "screen_tap", allowed = true))))
        val tools = productionConfirmTools()

        assertEquals(ToolCategory.GENERAL, categoryOf(companionA, tools, "screen_tap"))
        for (other in listOf(companionB, 0L, -1L, 999_999L)) {
            assertEquals(
                "伴侣 $other 不得继承伴侣 $companionA 的决定",
                ToolCategory.COMMERCE,
                categoryOf(other, tools, "screen_tap"),
            )
        }
    }

    @Test
    fun `通配决定对所有伴侣生效（不再有通道维度可以泄漏）`() = runBlocking {
        install(FakeCapabilityGrantStore(listOf(CapabilityGrant(null, "screen_tap", allowed = true))))
        val tools = productionConfirmTools()
        assertEquals(ToolCategory.GENERAL, categoryOf(companionA, tools, "screen_tap"))
        assertEquals(ToolCategory.GENERAL, categoryOf(companionB, tools, "screen_tap"))
    }

    @Test
    fun `具体伴侣的显式决定优先于通配决定（两个方向都验证）`() = runBlocking {
        // 通配允许 + 伴侣 42 禁止 ⇒ 42 仍然要确认，43 直接放行
        install(
            FakeCapabilityGrantStore(
                listOf(
                    CapabilityGrant(null, "screen_tap", allowed = true),
                    CapabilityGrant(companionA, "screen_tap", allowed = false),
                ),
            ),
        )
        val tools = productionConfirmTools()
        assertEquals(
            "本伴侣的显式禁止必须压过通配允许",
            ToolCategory.COMMERCE,
            categoryOf(companionA, tools, "screen_tap"),
        )
        assertEquals(
            "其它伴侣继续吃通配允许",
            ToolCategory.GENERAL,
            categoryOf(companionB, tools, "screen_tap"),
        )

        // 反向：通配禁止 + 伴侣 42 允许 ⇒ 42 放行，43 要确认
        install(
            FakeCapabilityGrantStore(
                listOf(
                    CapabilityGrant(null, "screen_tap", allowed = false),
                    CapabilityGrant(companionA, "screen_tap", allowed = true),
                ),
            ),
        )
        assertEquals(ToolCategory.GENERAL, categoryOf(companionA, tools, "screen_tap"))
        assertEquals(
            "通配禁止对该工具施加 COMMERCE",
            ToolCategory.COMMERCE,
            categoryOf(companionB, tools, "screen_tap"),
        )
    }

    // ── 反向护栏：不得削弱工具自身的工具集分类 ──

    @Test
    fun `工具自身工具集为 commerce 时授权不解除 COMMERCE`() = runBlocking {
        val tool = FakeTool(name = "confirm_and_commerce", toolsets = setOf("commerce"), requiresConfirmation = true)
        install(FakeCapabilityGrantStore(listOf(CapabilityGrant(companionA, tool.name, allowed = true))))
        assertEquals(
            "授权表不是「关掉确认门」：commerce 工具集本身的类别必须保留",
            ToolCategory.COMMERCE,
            categoryOf(companionA, listOf(tool), tool.name),
        )
    }

    @Test
    fun `显式允许只解除确认声明这一道，memory 与 chat 工具集照旧`() = runBlocking {
        val tools = listOf(
            FakeTool(name = "confirm_memory", toolsets = setOf("memory"), requiresConfirmation = true),
            FakeTool(name = "confirm_chat", toolsets = setOf("chat"), requiresConfirmation = true),
        )
        install(FakeCapabilityGrantStore(tools.map { CapabilityGrant(companionA, it.name, allowed = true) }))
        assertEquals(ToolCategory.MEMORY, categoryOf(companionA, tools, "confirm_memory"))
        assertEquals(ToolCategory.CHAT, categoryOf(companionA, tools, "confirm_chat"))
    }

    // ── 装配结果透传与查询参数 ──

    @Test
    fun `折叠不改变 name、description、parametersJson 与 toolsets 透传`() = runBlocking {
        install(FakeCapabilityGrantStore(listOf(CapabilityGrant(companionA, "screen_tap", allowed = true))))
        val tool = FakeTool(name = "screen_tap", toolsets = setOf("memory"), requiresConfirmation = true)
        val definition = fold(companionA, listOf(tool)).single()
        assertEquals(tool.name, definition.name)
        assertEquals(tool.description, definition.description)
        assertEquals(tool.parametersJsonSchema, definition.parametersJson)
        assertEquals(tool.toolsets.toList(), definition.toolsets)
        assertEquals(true, definition.available)
    }

    @Test
    fun `折叠点只按 companionId 查询，不传任何通道标识`() = runBlocking {
        val store = FakeCapabilityGrantStore()
        install(store)
        fold(companionB, productionConfirmTools())
        assertEquals(listOf(companionB), store.queries)
    }

    // ── 端到端：真实存储 + decide/clear 全链路 ──

    @Test
    fun `端到端：decide(allowed = true) 后放行、clear 后回到 COMMERCE`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        install(store)
        val tools = productionConfirmTools()

        assertEquals("初始无决定", ToolCategory.COMMERCE, categoryOf(companionA, tools, "automation_create"))
        store.decide(CapabilityGrant(companionA, "automation_create", allowed = true))
        assertEquals("显式允许后放行", ToolCategory.GENERAL, categoryOf(companionA, tools, "automation_create"))
        assertEquals(
            "同一批里的其它工具不受影响",
            ToolCategory.COMMERCE,
            categoryOf(companionA, tools, "screen_tap"),
        )

        store.clear(companionA, "automation_create")
        assertEquals(
            "清除后回到确认门",
            ToolCategory.COMMERCE,
            categoryOf(companionA, tools, "automation_create"),
        )
    }

    @Test
    fun `端到端：decide(allowed = false) 让安全工具进入确认门、clear 后恢复`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        install(store)
        val safe = listOf(FakeTool(name = "screen_read"))

        assertEquals(ToolCategory.GENERAL, categoryOf(companionA, safe, "screen_read"))
        store.decide(CapabilityGrant(companionA, "screen_read", allowed = false))
        assertEquals(
            "显式禁止 ⇒ 需要确认",
            ToolCategory.COMMERCE,
            categoryOf(companionA, safe, "screen_read"),
        )
        store.clear(companionA, "screen_read")
        assertEquals(
            "清除后恢复工具自身默认",
            ToolCategory.GENERAL,
            categoryOf(companionA, safe, "screen_read"),
        )
        assertFalse(
            "清除后不得残留任何显式决定",
            store.decisions().any { it.toolName == "screen_read" },
        )
    }
}
