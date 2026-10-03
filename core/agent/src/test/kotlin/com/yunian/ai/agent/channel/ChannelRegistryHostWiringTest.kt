package com.yunian.ai.agent.channel

import com.yunian.ai.agent.plugin.NoOpPluginLog
import com.yunian.ai.agent.plugin.PluginHostImpl
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.ChannelCapabilities
import com.yunian.ai.domain.channel.ChannelConnectionState
import com.yunian.ai.domain.channel.ChannelOutbound
import com.yunian.ai.domain.channel.ChannelRegistry
import com.yunian.ai.domain.channel.ChannelSendResult
import com.yunian.ai.domain.channel.ChannelSession
import com.yunian.ai.domain.channel.MutableChannelRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「通道即插件」端到端接线测试：证明 [PluginKind.ADAPTER] 在**生产路径**上真的有消费者。
 *
 * ## 被测对象
 *
 * - [PluginHostImpl]（core:agent 的**真实**宿主，非替身）；
 * - [ChannelRegistryImpl]（core:agent 的**真实**注册中心，非替身）。
 *
 * 只有通道适配器与插件用替身：core:agent 不能依赖任何 feature 模块，所以这里用
 * 与被测契约同构的最小替身插件（kind = ADAPTER、requires = CHANNELS、setup 里注册 +
 * effect 撤销）。**真实 QQ 插件**（`channel.qqbot`）由 :feature:qqbot 的单测覆盖。
 *
 * ## 在旧实现下为什么失败
 *
 * 旧实现里 [ChannelAdapter] / [ChannelRegistry] / [MutableChannelRegistry] /
 * [PluginServices.CHANNELS] **都不存在**，本文件**根本编译不过**——这是编译期不可达，
 * 不是行为回归测试，如实说明。可跑的行为断言对应的行为在旧实现下同样不存在：
 * 旧实现里 [PluginHost.pluginsOf] **生产零调用**，没有任何对象会把 ADAPTER 插件
 * 变成「可被生产代码查询的适配器」。
 *
 * 说明：本测试类注入 [NoOpPluginLog] 作为日志出口——`android.util.Log` 在纯 JVM 单测里
 * 是抛 `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric）。
 */
class ChannelRegistryHostWiringTest {

    // ── 测试替身 ──

    /** 会话替身：状态固定为 DISCONNECTED，send 恒成功（本测试不驱动出站）。 */
    private class FakeSession : ChannelSession {
        override val state: StateFlow<ChannelConnectionState> =
            MutableStateFlow(ChannelConnectionState.DISCONNECTED)

        override suspend fun send(outbound: ChannelOutbound): ChannelSendResult =
            ChannelSendResult.Sent("fake")

        override fun close() = Unit
    }

    /** 适配器替身：记录 [start] 调用次数，便于断言「装配期不启动会话」。 */
    private class FakeAdapter(
        override val channelKey: String,
        override val displayName: String = "fake:" + channelKey,
        override val capabilities: ChannelCapabilities = ChannelCapabilities(
            inboundText = true,
            inboundImage = false,
            outboundText = true,
            outboundImage = false,
            typingIndicator = false,
            proactiveSend = false,
            deliveryReceipt = false,
            stickers = false,
        ),
    ) : ChannelAdapter {

        var startCount: Int = 0
            private set

        override suspend fun start(): ChannelSession {
            startCount++
            return FakeSession()
        }
    }

    /**
     * 通道插件替身：与真实 `QQBotChannelPlugin` **同构**——kind = ADAPTER、
     * requires = [PluginServices.CHANNELS]、setup 里注册适配器并把撤销交给 effect。
     */
    private class FakeAdapterPlugin(
        override val id: String,
        private val adapter: ChannelAdapter,
        private val extraSetup: ((PluginContext) -> Unit)? = null,
        override val requires: Set<String> = setOf(PluginServices.CHANNELS),
    ) : LianYuPlugin {

        var setupCount: Int = 0
            private set

        override val name: String = "fake:" + id
        override val kind: PluginKind = PluginKind.ADAPTER
        override val configSchema: String? = null

        override val manifest: PluginManifest = PluginManifest(
            id = id,
            name = "fake:" + id,
            version = "1.0.0",
            kind = PluginKind.ADAPTER,
            requires = requires.sorted(),
            configSchema = null,
        )

        override fun setup(ctx: PluginContext) {
            setupCount++
            extraSetup?.invoke(ctx)
            val registry = ctx.inject<MutableChannelRegistry>(PluginServices.CHANNELS)
            registry.register(id, adapter)
            ctx.effect(
                { registry.unregister(id, adapter.channelKey) },
                "unregister-channel:" + adapter.channelKey,
            )
        }
    }

    /** 非通道插件替身：kind = TOOL（用于证明注册中心只认 ADAPTER）。 */
    private class FakeToolPlugin(override val id: String) : LianYuPlugin {
        override val name: String = "fake:" + id
        override val kind: PluginKind = PluginKind.TOOL
        override val requires: Set<String> = emptySet()
        override val configSchema: String? = null
        override fun setup(ctx: PluginContext) = Unit
    }

    /** 宿主探针：转发到真实宿主，同时统计 [PluginHost.pluginsOf] `(ADAPTER)` 的调用次数。 */
    private class SpyPluginHost(private val delegate: PluginHost) : PluginHost by delegate {
        var pluginsOfAdapterCalls: Int = 0
            private set

        override fun pluginsOf(kind: PluginKind): List<LianYuPlugin> {
            if (kind == PluginKind.ADAPTER) pluginsOfAdapterCalls++
            return delegate.pluginsOf(kind)
        }
    }

    // ── 脚手架 ──

    private fun newHost(vararg services: Pair<String, Any>): PluginHostImpl =
        PluginHostImpl(services.toMap(), NoOpPluginLog)

    /**
     * 建一套**生产接线**：真实 [ChannelRegistryImpl] + 真实 [PluginHostImpl]。
     *
     * 形状与 `YuNianApplication.initBusiness` 逐字一致：
     * ① 先构造注册中心（此时还没宿主）→ ② 用同一个实例作为框架服务建宿主 → ③ bindHost。
     */
    private fun newWiredHost(): Pair<PluginHostImpl, ChannelRegistryImpl> {
        val registry = ChannelRegistryImpl(NoOpPluginLog)
        val host = PluginHostImpl(
            mapOf(PluginServices.CHANNELS to registry),
            NoOpPluginLog,
        )
        registry.bindHost(host)
        return host to registry
    }

    // ── 1. 端到端可达性 ──

    @Test
    fun `ADAPTER 插件装载后注册中心能按 channelKey 取到适配器`() {
        val (host, registry) = newWiredHost()
        val adapter = FakeAdapter(channelKey = "qqbot")
        val plugin = FakeAdapterPlugin(id = "channel.qqbot", adapter = adapter)

        assertNull("装载前不得有适配器", registry.adapter("qqbot"))
        assertTrue("装载前 adapters() 必须为空", registry.adapters().isEmpty())

        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))

        assertSame("注册中心必须返回插件注册的同一个适配器", adapter, registry.adapter("qqbot"))
        assertEquals(listOf(adapter), registry.adapters())
        assertEquals("setup 必须恰好执行一次", 1, plugin.setupCount)
        assertEquals("装配期不得启动会话", 0, adapter.startCount)
    }

    @Test
    fun `注册中心的内容只能来自插件宿主：未装载的 ADAPTER 插件取不到适配器`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin(
            id = "channel.qqbot",
            adapter = FakeAdapter(channelKey = "qqbot"),
        )
        host.register(plugin) // 只注册，不装载

        assertEquals(
            "只注册未装载时 ADAPTER 插件已可被 pluginsOf 查到",
            1,
            host.pluginsOf(PluginKind.ADAPTER).size,
        )
        assertNull("未装载不得出现在注册中心", registry.adapter("qqbot"))
        assertTrue(registry.adapters().isEmpty())
    }

    @Test
    fun `生产代码真的调用了 pluginsOf(ADAPTER)（宿主探针计数）`() {
        val registry = ChannelRegistryImpl(NoOpPluginLog)
        val host = PluginHostImpl(
            mapOf(PluginServices.CHANNELS to registry),
            NoOpPluginLog,
        )
        val probe = SpyPluginHost(host)
        registry.bindHost(probe)

        assertEquals(0, probe.pluginsOfAdapterCalls)

        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        val callsAfterLoad = probe.pluginsOfAdapterCalls
        assertTrue(
            "装载期注册适配器必须经过 pluginsOf(ADAPTER)，实际调用次数=" + callsAfterLoad,
            callsAfterLoad >= 1,
        )

        assertNotNull(registry.adapter("qqbot"))
        assertTrue(
            "查询期也必须回查 pluginsOf(ADAPTER)，实际调用次数=" + probe.pluginsOfAdapterCalls,
            probe.pluginsOfAdapterCalls > callsAfterLoad,
        )
    }

    @Test
    fun `注册中心在查询期剔除已不在装载态的条目（活性兜底）`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertNotNull(registry.adapter("qqbot"))

        // 绕过 unload（不执行 effect）直接注销：注册中心仍必须剔除该条目
        host.unregister(plugin.id)
        assertNull("插件不在装载态时注册中心不得返回适配器", registry.adapter("qqbot"))
        assertTrue(registry.adapters().isEmpty())
    }

    // ── 2. 可逆性 ──

    @Test
    fun `unload 之后注册中心取不到适配器`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertNotNull(registry.adapter("qqbot"))

        assertTrue("unload 必须返回 true", host.unload(plugin.id))

        assertNull("卸载后必须从注册中心消失", registry.adapter("qqbot"))
        assertTrue("卸载后 adapters() 必须为空", registry.adapters().isEmpty())
        assertFalse(host.isLoaded(plugin.id))
        assertTrue(host.loadedIds().isEmpty())
    }

    @Test
    fun `重新装载后适配器回到注册中心（卸载不留鸡毛且可重入）`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertTrue(host.unload(plugin.id))
        assertNull(registry.adapter("qqbot"))

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertNotNull("重装后必须重新出现", registry.adapter("qqbot"))
        assertEquals(2, plugin.setupCount)
    }

    // ── 3. 防「绕过插件宿主直接塞进注册中心」 ──

    @Test
    fun `未注册的插件 id 直接注册适配器被拒绝`() {
        val (host, registry) = newWiredHost()
        try {
            registry.register("channel.ghost", FakeAdapter("ghost"))
            fail("绕过插件宿主的注册必须被拒绝")
        } catch (e: IllegalStateException) {
            assertTrue(
                "拒绝原因必须点名插件不在 ADAPTER 分派视图里: " + e.message,
                e.message!!.contains("pluginsOf(ADAPTER)"),
            )
        }
        assertTrue("被拒绝的注册不得留下条目", registry.adapters().isEmpty())
        assertEquals(0, host.pluginsOf(PluginKind.ADAPTER).size)
    }

    @Test
    fun `已注册但未装载的插件直接注册适配器时不可见（装载后才可见）`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin) // 注册但不装载

        // 绕过插件 setup 直接注册：允许写，但**不得可见**——可见性由查询侧回查装载态决定
        // （PluginHostImpl.load 在 setup 之后才置 isLoaded，装配期必须允许注册）。
        registry.register(plugin.id, FakeAdapter("qqbot"))
        assertNull("未装载插件的适配器不得可见", registry.adapter("qqbot"))
        assertTrue("未装载插件的适配器不得出现在 adapters()", registry.adapters().isEmpty())

        // 装载后同一插件（经自己的 setup 注册）必须可见
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertNotNull("装载后必须可见", registry.adapter("qqbot"))
    }

    @Test
    fun `装载失败的插件即使注册过适配器也不可见`() {
        val registry = ChannelRegistryImpl(NoOpPluginLog)
        val host = PluginHostImpl(
            mapOf(PluginServices.CHANNELS to registry),
            NoOpPluginLog,
        )
        registry.bindHost(host)
        val adapter = FakeAdapter("qqbot")
        val plugin = FakeAdapterPlugin(
            id = "channel.qqbot",
            adapter = adapter,
            extraSetup = { ctx ->
                ctx.inject<MutableChannelRegistry>(PluginServices.CHANNELS)
                    .register("channel.qqbot", adapter)
                throw IllegalStateException("boom")
            },
        )
        host.register(plugin)

        val result = host.load(plugin.id, null)
        assertTrue("setup 抛异常必须 fail-closed", result is PluginLoadResult.Failed)
        assertNull("装载失败不得留下可见适配器", registry.adapter("qqbot"))
        assertTrue(registry.adapters().isEmpty())
        assertFalse(host.isLoaded(plugin.id))
    }

    @Test
    fun `非 ADAPTER 类别的插件 id 注册适配器被拒绝`() {
        val (host, registry) = newWiredHost()
        val toolPlugin = FakeToolPlugin("automation.core")
        host.register(toolPlugin)
        assertEquals(PluginLoadResult.Loaded, host.load(toolPlugin.id, null))

        try {
            registry.register(toolPlugin.id, FakeAdapter("automation"))
            fail("非 ADAPTER 插件的注册必须被拒绝")
        } catch (e: IllegalStateException) {
            assertTrue(
                "拒绝原因必须点名插件不在 ADAPTER 分派视图里: " + e.message,
                e.message!!.contains("pluginsOf(ADAPTER)"),
            )
        }
        assertTrue(registry.adapters().isEmpty())
    }

    @Test
    fun `通道标识冲突时第二个插件装配失败且先装载者不受影响`() {
        val (host, registry) = newWiredHost()
        val first = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        val second = FakeAdapterPlugin("channel.qqbot.clone", FakeAdapter("qqbot"))
        host.register(first)
        host.register(second)
        assertEquals(PluginLoadResult.Loaded, host.load(first.id, null))

        val result = host.load(second.id, null)
        assertTrue("通道标识冲突必须让第二个插件装配失败", result is PluginLoadResult.Failed)
        assertFalse(host.isLoaded(second.id))
        assertNotNull("先装载者的适配器必须仍在", registry.adapter("qqbot"))
        assertEquals(1, registry.adapters().size)
    }

    @Test
    fun `插件不得摘掉别的插件注册的适配器`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin)
        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))

        assertFalse("他人不得摘除", registry.unregister("channel.other", "qqbot"))
        assertNotNull("适配器必须仍在", registry.adapter("qqbot"))
        assertTrue("本人可摘除", registry.unregister(plugin.id, "qqbot"))
        assertNull(registry.adapter("qqbot"))
        assertFalse("幂等：重复摘除返回 false", registry.unregister(plugin.id, "qqbot"))
    }

    // ── 4. 依赖 fail-closed（新插件同样遵守既有宿主语义）──

    @Test
    fun `ChannelRegistry 未注入时通道插件装载失败且不留半装配状态`() {
        val host = newHost() // 刻意不预置 CHANNELS
        val adapter = FakeAdapter("qqbot")
        val plugin = FakeAdapterPlugin("channel.qqbot", adapter)
        host.register(plugin)

        val result = host.load(plugin.id, null)
        assertTrue("缺 CHANNELS 必须 fail-closed", result is PluginLoadResult.Failed)
        val reason = (result as PluginLoadResult.Failed).reason
        assertTrue(
            "原因必须点名缺失的服务键: " + reason,
            reason.contains(PluginServices.CHANNELS),
        )
        assertTrue("原因必须说明宿主未预置: " + reason, reason.contains("缺少依赖服务"))
        assertEquals("被拒绝的装载不得执行 setup", 0, plugin.setupCount)
        assertFalse(host.isLoaded(plugin.id))
        assertTrue(host.loadedIds().isEmpty())
    }

    @Test
    fun `预置了 ChannelRegistry 时同一插件正常装载`() {
        val (host, registry) = newWiredHost()
        val plugin = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        host.register(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertNotNull(registry.adapter("qqbot"))
    }

    // ── 5. 分派视图与多插件共存 ──

    @Test
    fun `只装载一个通道插件时另一个通道插件不出现在注册中心`() {
        val (host, registry) = newWiredHost()
        val qq = FakeAdapterPlugin("channel.qqbot", FakeAdapter("qqbot"))
        val wechat = FakeAdapterPlugin("channel.wechat", FakeAdapter("wechat"))
        host.register(qq)
        host.register(wechat)
        assertEquals(PluginLoadResult.Loaded, host.load(qq.id, null))

        assertEquals(listOf("qqbot"), registry.adapters().map { it.channelKey })
        assertNull(registry.adapter("wechat"))

        assertEquals(PluginLoadResult.Loaded, host.load(wechat.id, null))
        assertEquals(
            "adapters() 必须按 channelKey 排序",
            listOf("qqbot", "wechat"),
            registry.adapters().map { it.channelKey },
        )

        assertTrue(host.unload(qq.id))
        assertEquals(listOf("wechat"), registry.adapters().map { it.channelKey })
    }

    @Test
    fun `注册中心可作为只读视图 ChannelRegistry 使用`() {
        val (_, registry) = newWiredHost()
        val view: ChannelRegistry = registry
        assertTrue(view.adapters().isEmpty())
        assertNull(view.adapter("qqbot"))
    }
}
