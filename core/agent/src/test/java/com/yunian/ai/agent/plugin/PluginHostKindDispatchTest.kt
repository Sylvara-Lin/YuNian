package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.BlueprintLoadResult
import com.yunian.ai.domain.plugin.BlueprintPluginRef
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginBlueprint
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件框架地基契约测试（P1：消息通道 / 消息管道的地基）。
 *
 * 覆盖验收标准：
 * 1. **kind 分派**：五个 [PluginKind] 均可注册 / 装载 / 卸载，[PluginHostImpl.pluginsOf] 按类别分派；
 *    ADAPTER（消息通道适配器）与 PIPELINE（回合后处理管道）与 TOOL/SKILL/STICKER 走完全相同的
 *    装载 / 卸载 / 逆序回滚路径（用**测试替身插件**，P1 不实现任何真实通道或管道）。
 * 2. **manifest 校验**：manifest 与插件自描述不一致
 *    （id/name/kind/requires/configSchema/description）
 *    在 register 阶段即被拒绝（fail-closed，registry 不留半成品）。
 * 3. **requires 缺失 → fail-closed**：宿主未预置的依赖服务使装载被拒绝并给出明确原因，
 *    且 setup 从未执行（不留任何副作用）。
 *
 * 说明：本测试类注入 [NoOpPluginLog] 作为日志出口——`android.util.Log` 在纯 JVM 单测里是
 * 抛 `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric，:core:agent 也未开启
 * `unitTests.isReturnDefaultValues`），日志出口是宿主唯一的 Android 依赖点。
 */
class PluginHostKindDispatchTest {

    // ── 测试替身 ──

    private class FakeTool(private val toolName: String) : AiTool {
        override val name: String = toolName
        override val description: String = "fake: $toolName"
        override val parametersJsonSchema: String = """{"type":"object","properties":{}}"""
        override suspend fun execute(argumentsJson: String): String = ""
    }

    /**
     * 通用插件替身：可指定 kind / requires / configSchema / description / 装配行为。
     *
     * [manifest] 默认由插件自描述合成（与契约默认实现同构）；传入 [manifestOverride]
     * 可构造「清单与自描述不一致」的非法插件用于校验测试。
     */
    private class FakePlugin(
        override val id: String,
        override val kind: PluginKind,
        override val name: String = "fake:$id",
        override val requires: Set<String> = emptySet(),
        override val configSchema: String? = null,
        // 插件说明；默认空串 = 未声明说明（与契约默认值同向）。
        override val description: String = "",
        private val manifestOverride: PluginManifest? = null,
        private val toolName: String? = null,
        private val onSetup: ((PluginContext) -> Unit)? = null,
    ) : LianYuPlugin {

        var setupCount: Int = 0
            private set

        override val manifest: PluginManifest
            get() = manifestOverride ?: PluginManifest(
                id = id,
                name = name,
                version = "1.0.0",
                kind = kind,
                requires = requires.sorted(),
                description = description,
                configSchema = configSchema,
            )

        override fun setup(ctx: PluginContext) {
            setupCount++
            toolName?.let { tool ->
                ToolRegistry.register(FakeTool(tool))
                ctx.effect({ ToolRegistry.unregister(tool) }, "unregister:$tool")
            }
            onSetup?.invoke(ctx)
        }
    }

    private val registeredTools = mutableListOf<String>()

    private fun newHost(vararg services: Pair<String, Any>): PluginHostImpl =
        PluginHostImpl(services.toMap(), NoOpPluginLog)

    private fun newPlugin(
        id: String,
        kind: PluginKind,
        toolName: String? = null,
        requires: Set<String> = emptySet(),
        onSetup: ((PluginContext) -> Unit)? = null,
    ): FakePlugin {
        if (toolName != null) registeredTools += toolName
        return FakePlugin(id = id, kind = kind, toolName = toolName, requires = requires, onSetup = onSetup)
    }

    private fun cleanup() {
        registeredTools.forEach { ToolRegistry.unregister(it) }
        registeredTools.clear()
        ToolRegistry.invalidateAvailabilityCache()
    }

    // ── 1. kind 分派 ──

    @Test
    fun `五个插件类别都能注册并被 pluginsOf 按类别分派`() {
        val host = newHost()
        val tool = newPlugin("t.tool", PluginKind.TOOL)
        val skill = newPlugin("t.skill", PluginKind.SKILL)
        val sticker = newPlugin("t.sticker", PluginKind.STICKER)
        val adapter = newPlugin("t.adapter", PluginKind.ADAPTER)
        val pipeline = newPlugin("t.pipeline", PluginKind.PIPELINE)
        listOf(tool, skill, sticker, adapter, pipeline).forEach { host.register(it) }

        assertEquals("plugins() 必须包含全部已注册插件", 5, host.plugins().size)
        assertEquals(listOf(tool), host.pluginsOf(PluginKind.TOOL))
        assertEquals(listOf(skill), host.pluginsOf(PluginKind.SKILL))
        assertEquals(listOf(sticker), host.pluginsOf(PluginKind.STICKER))
        assertEquals(listOf(adapter), host.pluginsOf(PluginKind.ADAPTER))
        assertEquals(listOf(pipeline), host.pluginsOf(PluginKind.PIPELINE))
        assertEquals("分派视图必须按 id 排序", listOf("t.adapter"), host.pluginsOf(PluginKind.ADAPTER).map { it.id })
    }

    @Test
    fun `ADAPTER 与 PIPELINE 与 TOOL 走完全相同的装载卸载路径`() {
        val toolName = "t_adapter_effect_tool"
        val adapter = newPlugin("t.adapter", PluginKind.ADAPTER, toolName = toolName)
        val pipeline = newPlugin("t.pipeline", PluginKind.PIPELINE)
        val host = newHost(PluginServices.TOOLS to ToolRegistry)
        host.register(adapter)
        host.register(pipeline)

        assertEquals(PluginLoadResult.Loaded, host.load(adapter.id, null))
        assertEquals(PluginLoadResult.Loaded, host.load(pipeline.id, null))
        assertTrue(host.isLoaded(adapter.id))
        assertTrue(host.isLoaded(pipeline.id))
        assertTrue("装配副作用已生效", ToolRegistry.all().any { it.name == toolName })

        assertTrue(host.unload(adapter.id))
        assertTrue(host.unload(pipeline.id))
        assertFalse(host.isLoaded(adapter.id))
        assertFalse("卸载必须清理装配副作用", ToolRegistry.all().any { it.name == toolName })
        assertTrue(host.loadedIds().isEmpty())
    }

    // ── 2. manifest 校验 ──

    @Test
    fun `manifest 与自描述一致时注册成功且清单可直接读取`() {
        val host = newHost()
        val plugin = newPlugin("t.ok", PluginKind.PIPELINE)
        host.register(plugin)

        assertTrue(host.isRegistered(plugin.id))
        assertNotNull(host.plugin(plugin.id))
        assertEquals(plugin.id, plugin.manifest.id)
        assertEquals("1.0.0", plugin.manifest.version)
        assertEquals(PluginKind.PIPELINE, plugin.manifest.kind)
        assertEquals("默认合成清单必须带上自描述的说明", plugin.description, plugin.manifest.description)
    }

    @Test
    fun `manifest 的 id 与插件 id 不一致时拒绝注册`() {
        val plugin = FakePlugin(
            id = "t.bad.id",
            kind = PluginKind.TOOL,
            manifestOverride = PluginManifest(
                id = "t.other",
                name = "fake:t.bad.id",
                version = "1.0.0",
                kind = PluginKind.TOOL,
                requires = emptyList(),
            ),
        )
        val host = newHost()
        host.register(plugin)

        assertFalse("清单不一致必须拒绝注册", host.isRegistered(plugin.id))
        assertTrue(host.plugins().isEmpty())
        assertEquals(PluginLoadResult.NotFound, host.load(plugin.id, null))
    }

    @Test
    fun `manifest 的 kind 与插件 kind 不一致时拒绝注册`() {
        val plugin = FakePlugin(
            id = "t.bad.kind",
            kind = PluginKind.TOOL,
            manifestOverride = PluginManifest(
                id = "t.bad.kind",
                name = "fake:t.bad.kind",
                version = "1.0.0",
                kind = PluginKind.ADAPTER,
                requires = emptyList(),
            ),
        )
        val host = newHost()
        host.register(plugin)

        assertFalse(host.isRegistered(plugin.id))
        assertEquals(PluginLoadResult.NotFound, host.load(plugin.id, null))
    }

    @Test
    fun `manifest 的 requires 与插件 requires 不一致时拒绝注册`() {
        val plugin = FakePlugin(
            id = "t.bad.requires",
            kind = PluginKind.TOOL,
            requires = setOf(PluginServices.TOOLS),
            manifestOverride = PluginManifest(
                id = "t.bad.requires",
                name = "fake:t.bad.requires",
                version = "1.0.0",
                kind = PluginKind.TOOL,
                requires = emptyList(),
            ),
        )
        val host = newHost(PluginServices.TOOLS to ToolRegistry)
        host.register(plugin)

        assertFalse(host.isRegistered(plugin.id))
        assertEquals(PluginLoadResult.NotFound, host.load(plugin.id, null))
    }

    @Test
    fun `manifest 的 configSchema 与插件 configSchema 不一致时拒绝注册`() {
        val plugin = FakePlugin(
            id = "t.bad.schema",
            kind = PluginKind.TOOL,
            configSchema = """{"type":"object"}""",
            manifestOverride = PluginManifest(
                id = "t.bad.schema",
                name = "fake:t.bad.schema",
                version = "1.0.0",
                kind = PluginKind.TOOL,
                requires = emptyList(),
                configSchema = null,
            ),
        )
        val host = newHost()
        host.register(plugin)

        assertFalse(host.isRegistered(plugin.id))
    }

    @Test
    fun `manifest 的 description 与插件 description 不一致时拒绝注册`() {
        // description 是「插件设置」页直接展示给用户的一句话说明（行副标题）。
        // 它和 id/name/kind/requires/configSchema 一样参与逐字一致性校验：
        // 清单独说一套、代码另说一套，会让设置页显示给用户的内容与插件实际行为脱节。
        val plugin = FakePlugin(
            id = "t.bad.description",
            kind = PluginKind.TOOL,
            description = "插件自己声明的说明",
            manifestOverride = PluginManifest(
                id = "t.bad.description",
                name = "fake:t.bad.description",
                version = "1.0.0",
                kind = PluginKind.TOOL,
                requires = emptyList(),
                description = "清单里另写的一套说明",
            ),
        )
        val host = newHost()
        host.register(plugin)

        assertFalse("说明不一致必须拒绝注册（fail-closed）", host.isRegistered(plugin.id))
        assertTrue(host.plugins().isEmpty())
        assertEquals(PluginLoadResult.NotFound, host.load(plugin.id, null))

        val reason = PluginHostImpl.manifestMatchesSelfDescription(plugin)
        assertNotNull("说明不一致必须被定位", reason)
        assertTrue("原因必须指明是 description 字段：$reason", reason!!.contains("description"))
    }

    @Test
    fun `description 两边一致时照常注册（含两边都未声明的情形）`() {
        // 校验是**逐字相等**，不是「必须非空」：两边都留空同样一致、同样合法，
        // 这样既有实现方（未覆写 description）不会被这次契约扩展判成非法插件。
        val host = newHost()
        val silent = FakePlugin(id = "t.silent", kind = PluginKind.TOOL)
        val spoken = FakePlugin(
            id = "t.spoken",
            kind = PluginKind.TOOL,
            description = "让 AI 做某件事的一句话说明",
        )
        listOf(silent, spoken).forEach { host.register(it) }

        assertTrue("两边都未声明说明也必须合法", host.isRegistered(silent.id))
        assertTrue(host.isRegistered(spoken.id))
        assertEquals("", silent.manifest.description)
        assertEquals("让 AI 做某件事的一句话说明", spoken.manifest.description)
        assertEquals(null, PluginHostImpl.manifestMatchesSelfDescription(silent))
        assertEquals(null, PluginHostImpl.manifestMatchesSelfDescription(spoken))
    }

    @Test
    fun `校验函数对一致清单返回 null 且能定位首个差异`() {
        val good = newPlugin("t.valid", PluginKind.SKILL)
        assertEquals(null, PluginHostImpl.manifestMatchesSelfDescription(good))

        val badName = FakePlugin(
            id = "t.bad.name",
            kind = PluginKind.SKILL,
            manifestOverride = PluginManifest(
                id = "t.bad.name",
                name = "另一个名字",
                version = "1.0.0",
                kind = PluginKind.SKILL,
                requires = emptyList(),
            ),
        )
        val reason = PluginHostImpl.manifestMatchesSelfDescription(badName)
        assertNotNull("名称不一致必须被定位", reason)
        assertTrue("原因必须指明是 name 字段", reason!!.contains("name"))
    }

    // ── 3. requires 缺失 → fail-closed ──

    @Test
    fun `依赖服务缺失时装载被拒绝且原因明确`() {
        val plugin = newPlugin(
            id = "t.missing.dep",
            kind = PluginKind.PIPELINE,
            requires = setOf("tMissingService"),
        )
        val host = newHost(PluginServices.TOOLS to ToolRegistry)

        val result = host.load(plugin.id, null)
        // 未注册 → NotFound（与迁移前一致）
        assertEquals(PluginLoadResult.NotFound, result)

        host.register(plugin)
        val loaded = host.load(plugin.id, null)
        assertTrue("缺依赖必须 fail-closed", loaded is PluginLoadResult.Failed)
        val reason = (loaded as PluginLoadResult.Failed).reason
        assertTrue("原因必须点名缺失的服务键: $reason", reason.contains("tMissingService"))
        assertTrue("原因必须说明宿主未预置: $reason", reason.contains("缺少依赖服务"))
        assertEquals("被拒绝的装载不得执行 setup", 0, plugin.setupCount)
        assertFalse(host.isLoaded(plugin.id))
        assertTrue(host.loadedIds().isEmpty())
    }

    @Test
    fun `依赖齐备时同一插件可正常装载`() {
        val plugin = newPlugin(
            id = "t.dep.ok",
            kind = PluginKind.PIPELINE,
            requires = setOf(PluginServices.TOOLS),
        )
        val host = newHost(PluginServices.TOOLS to ToolRegistry)
        host.register(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertEquals(1, plugin.setupCount)
        assertTrue(host.isLoaded(plugin.id))
    }

    // ── 4. 迁移不改动的既有语义（回归护栏）──

    @Test
    fun `load 保持幂等 setup 只执行一次`() {
        val plugin = newPlugin("t.idempotent", PluginKind.PIPELINE)
        val host = newHost()
        host.register(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertEquals("重复装载必须幂等", 1, plugin.setupCount)
    }

    @Test
    fun `unload 逆序执行 effects 且未装载时返回 false`() {
        val order = mutableListOf<String>()
        val plugin = newPlugin(
            id = "t.lifo",
            kind = PluginKind.PIPELINE,
            onSetup = { ctx ->
                ctx.effect({ order += "a" }, "a")
                ctx.effect({ order += "b" }, "b")
                ctx.effect({ order += "c" }, "c")
            },
        )
        val host = newHost()
        host.register(plugin)

        assertFalse("未装载时 unload 必须返回 false", host.unload(plugin.id))
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertTrue(host.unload(plugin.id))
        assertEquals("effects 必须逆序执行", listOf("c", "b", "a"), order)
    }

    @Test
    fun `装配失败时回滚全部副作用并返回 Failed`() {
        val plugin = newPlugin(
            id = "t.fail.rollback",
            kind = PluginKind.ADAPTER,
            onSetup = { ctx ->
                ctx.effect({ ToolRegistry.unregister("t_fail_rollback_tool") }, "unregister")
                ToolRegistry.register(FakeTool("t_fail_rollback_tool"))
                throw IllegalStateException("boom")
            },
        )
        val host = newHost()
        host.register(plugin)

        val result = host.load(plugin.id, null)
        assertTrue(result is PluginLoadResult.Failed)
        assertTrue((result as PluginLoadResult.Failed).reason.contains("boom"))
        assertFalse("装配失败不得留下装载态", host.isLoaded(plugin.id))
        assertFalse("装配失败必须回滚副作用", ToolRegistry.all().any { it.name == "t_fail_rollback_tool" })
    }

    @Test
    fun `配置 Schema 校验失败时拒绝装载`() {
        val plugin = FakePlugin(
            id = "t.schema",
            kind = PluginKind.PIPELINE,
            configSchema = """{"type":"object","required":["mode"]}""",
        )
        val host = newHost()
        host.register(plugin)

        val ok = host.load(plugin.id, """{"mode":"segmented"}""")
        assertEquals(PluginLoadResult.Loaded, ok)
        assertTrue(host.unload(plugin.id))

        val bad = host.load(plugin.id, """{}""")
        assertTrue("缺少 required 字段必须拒绝装载", bad is PluginLoadResult.Failed)
        assertFalse(host.isLoaded(plugin.id))
    }

    @Test
    fun `loadBlueprint 语义不变 配置变化触发卸载重装`() {
        val plugin = FakePlugin(
            id = "t.blueprint",
            kind = PluginKind.PIPELINE,
            configSchema = """{"type":"object","required":["mode"]}""",
        )
        val host = newHost()
        host.register(plugin)

        val first = host.loadBlueprint(
            PluginBlueprint("bp", "测试蓝图", listOf(BlueprintPluginRef("t.blueprint", configJson = """{"mode":"a"}""")))
        )
        assertEquals(listOf("t.blueprint"), (first as BlueprintLoadResult.Applied).loaded)
        assertEquals(1, plugin.setupCount)

        // 配置未变 → 幂等跳过
        val second = host.loadBlueprint(
            PluginBlueprint("bp", "测试蓝图", listOf(BlueprintPluginRef("t.blueprint", configJson = """{"mode":"a"}""")))
        )
        assertEquals(listOf("t.blueprint"), (second as BlueprintLoadResult.Applied).loaded)
        assertEquals("配置未变不得重装", 1, plugin.setupCount)

        // 配置变化 → 先卸载再装载
        val third = host.loadBlueprint(
            PluginBlueprint("bp", "测试蓝图", listOf(BlueprintPluginRef("t.blueprint", configJson = """{"mode":"b"}""")))
        )
        assertEquals(listOf("t.blueprint"), (third as BlueprintLoadResult.Applied).loaded)
        assertEquals("配置变化必须重装", 2, plugin.setupCount)

        // 禁用 → 卸载 + skipped 记录
        val fourth = host.loadBlueprint(
            PluginBlueprint("bp", "测试蓝图", listOf(BlueprintPluginRef("t.blueprint", enabled = false)))
        )
        assertEquals(listOf("disabled:t.blueprint"), (fourth as BlueprintLoadResult.Applied).skipped)
        assertFalse(host.isLoaded("t.blueprint"))

        // 未知插件 → notfound
        val fifth = host.loadBlueprint(
            PluginBlueprint("bp", "测试蓝图", listOf(BlueprintPluginRef("t.no.such")))
        )
        assertEquals(listOf("notfound:t.no.such"), (fifth as BlueprintLoadResult.Applied).skipped)
    }

    // ── 5. 副作用清理 ──

    @Test
    fun `测试替身插件注册的工具在用例间被清理`() {
        val toolName = "t_cleanup_tool"
        val plugin = newPlugin("t.cleanup", PluginKind.TOOL, toolName = toolName)
        val host = newHost(PluginServices.TOOLS to ToolRegistry)
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertTrue(ToolRegistry.all().any { it.name == toolName })

        assertTrue(host.unload(plugin.id))
        assertFalse(ToolRegistry.all().any { it.name == toolName })
        cleanup()
        assertFalse(ToolRegistry.all().any { it.name == toolName })
    }
}
