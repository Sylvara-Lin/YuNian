package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.DialogueRequest
import com.yunian.ai.domain.DialogueResult
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginDispatchException
import com.yunian.ai.domain.plugin.PluginEventNext
import com.yunian.ai.domain.plugin.PluginEventListener
import com.yunian.ai.domain.plugin.PluginEventResult
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.domain.plugin.PluginWaterfallListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 宿主级事件总线接线测试（P3-3a）：证明「插件 A emit → 插件 B 的 on 收到」在**生产路径**上成立。
 *
 * ## 被测对象
 *
 * - [PluginHostImpl]（core:agent 的**真实**宿主，非替身）；
 * - [PluginContextImpl]（**真实**装配上下文）；
 * - [PluginEventBus]（**真实**宿主级共享总线）。
 *
 * 只有插件是替身：core:agent 不能依赖任何 feature 模块，因此这里用与契约同构的最小
 * 替身插件（setup 里订阅 / 派发）。真实插件（skill.builtin_chat_protocol /
 * sticker.preference / automation.core / channel.qqbot）零改动继续通过由各自的单测覆盖。
 *
 * ## 在旧实现下为什么失败（行为回归，不是编译错误）
 *
 * 旧实现里 [PluginContext] **没有** `emit` / `parallel` / `serial` / `bail` / `waterfall`，
 * 且监听表挂在每个 [PluginContextImpl] 实例上、宿主为每个插件新建上下文——
 * 因此第 1 组用例（跨插件投递）在旧实现下**根本无法表达**；即便补一个「把事件派发给
 * 自己的监听器」的 emit，A→B 也仍然收不到（这是 P3-3a 要修的真问题）。
 *
 * ## 语义基准
 *
 * 全部派发语义逐条对齐 cordis-rs 0.6.2：`src/events.rs` 的 `emit`（:482）/
 * `parallel`（:493）/`serial`（:531）/`bail_event`（:572）/`waterfall_async_event`（:620），
 * 以及 `src/context.rs:475-535` 的 `Context::emit/parallel/serial/bail/waterfall`。
 *
 * 说明：本测试类注入 [NoOpPluginLog] 作为日志出口——`android.util.Log` 在纯 JVM 单测里是
 * 抛 `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric）。协程用 `runBlocking`：
 * `:core:agent` 的 testImplementation 里没有 kotlinx-coroutines-test。
 */
class PluginHostEventBusTest {

    // ── 测试替身 ──

    /**
     * 事件插件替身：`requires` 可声明（AGENT fail-closed 用例），
     * `onSetup` 拿到**真实**上下文做订阅 / 派发。
     *
     * [ctx] 记录装配期拿到的真实上下文，供用例在装载之后继续派发——
     * 这正是生产里插件持有的那个对象（不是替身）。
     */
    private class FakeEventPlugin(
        override val id: String,
        override val kind: PluginKind = PluginKind.PIPELINE,
        override val requires: Set<String> = emptySet(),
        private val onSetup: (PluginContext) -> Unit = {},
    ) : LianYuPlugin {

        var setupCount: Int = 0
            private set

        var ctx: PluginContext? = null
            private set

        override val name: String = "fake:" + id
        override val configSchema: String? = null

        override fun setup(ctx: PluginContext) {
            setupCount++
            this.ctx = ctx
            onSetup(ctx)
        }
    }

    /** [DialogueCoordinator] 替身：只用于断言「宿主预置的 AGENT 就是同一个实例」。 */
    private class FakeDialogueCoordinator : DialogueCoordinator {
        override suspend fun generateReply(request: DialogueRequest): DialogueResult =
            DialogueResult(replyText = "fake")
    }

    private fun newHost(vararg services: Pair<String, Any>): Pair<PluginHostImpl, PluginEventBus> {
        val bus = PluginEventBus(NoOpPluginLog)
        return PluginHostImpl(services.toMap(), NoOpPluginLog, bus) to bus
    }

    private fun hostWith(vararg plugins: LianYuPlugin): Pair<PluginHostImpl, PluginEventBus> {
        val (host, bus) = newHost()
        plugins.forEach { host.register(it) }
        return host to bus
    }

    private fun ctxOf(plugin: FakeEventPlugin): PluginContext = requireNotNull(plugin.ctx)

    private fun loadAll(host: PluginHostImpl, vararg plugins: FakeEventPlugin) {
        plugins.forEach { assertEquals(PluginLoadResult.Loaded, host.load(it.id, null)) }
    }

    // ── 1. 跨插件投递（验收标准 1） ──

    @Test
    fun `插件 A emit 的事件被插件 B 的 on 收到（真实宿主 + 真实共享总线）`() {
        val received = mutableListOf<Any>()
        val publisher = FakeEventPlugin(id = "t.a") { ctx -> ctx.emit("t.evt", "from-a") }
        val subscriber = FakeEventPlugin(id = "t.b") { ctx -> ctx.on("t.evt") { received += it } }
        val (host, bus) = hostWith(publisher, subscriber)

        loadAll(host, publisher, subscriber)

        assertEquals("装载期不得自行派发", emptyList<Any>(), received)
        assertEquals("订阅已落到宿主级共享总线", 1, bus.listenerCount("t.evt"))

        ctxOf(publisher).emit("t.evt", "from-a")

        assertEquals("A 的事件必须落到 B 的监听器上", listOf<Any>("from-a"), received)
    }

    @Test
    fun `emit 同步依次调用全部监听器且忽略返回值`() {
        val order = mutableListOf<String>()
        val first = FakeEventPlugin(id = "t.first") { ctx -> ctx.on("t.evt") { order += "first" } }
        // onBail 的监听器同样收到 emit 广播；它返回的非 null 值被 emit 忽略（不得短路）。
        val second = FakeEventPlugin(id = "t.second") { ctx ->
            ctx.onBail("t.evt") { order += "second"; "bail-value" }
        }
        val third = FakeEventPlugin(id = "t.third") { ctx -> ctx.on("t.evt") { order += "third" } }
        val (host, _) = hostWith(first, second, third)
        loadAll(host, first, second, third)

        ctxOf(first).emit("t.evt", Unit)

        assertEquals("emit 必须全部调用且按注册顺序，忽略返回值", listOf("first", "second", "third"), order)
    }

    @Test
    fun `emit 的监听器异常不影响其他监听器`() {
        val calls = mutableListOf<String>()
        val thrower = FakeEventPlugin(id = "t.throw") { ctx ->
            ctx.on("t.evt") { calls += "thrower"; throw IllegalStateException("boom") }
        }
        val survivor = FakeEventPlugin(id = "t.survivor") { ctx ->
            ctx.on("t.evt") { calls += "survivor" }
        }
        val (host, _) = hostWith(thrower, survivor)
        loadAll(host, thrower, survivor)

        ctxOf(thrower).emit("t.evt", Unit)

        assertEquals(listOf("thrower", "survivor"), calls)
    }

    @Test
    fun `监听器在回调里再次 emit 同一事件不会死锁且重入派发完成`() {
        val calls = mutableListOf<String>()
        val plugin = FakeEventPlugin(id = "t.reentrant") { ctx ->
            ctx.on("t.evt") { payload ->
                calls += "outer:" + payload
                if (payload == "a") ctx.emit("t.evt", "b")
            }
        }
        val (host, _) = hostWith(plugin)
        loadAll(host, plugin)

        ctxOf(plugin).emit("t.evt", "a")

        assertEquals(listOf("outer:a", "outer:b"), calls)
    }

    // ── 2. 卸载即退订（验收标准 2） ──

    @Test
    fun `unload 后原监听器不再被调用且总线不再持有它`() {
        val received = mutableListOf<Any>()
        val listener = FakeEventPlugin(id = "t.listener") { ctx -> ctx.on("t.evt") { received += it } }
        val publisher = FakeEventPlugin(id = "t.publisher") { }
        val (host, bus) = hostWith(listener, publisher)
        loadAll(host, listener, publisher)
        assertEquals("订阅已落到共享总线", 1, bus.listenerCount("t.evt"))

        val publisherCtx = ctxOf(publisher)
        publisherCtx.emit("t.evt", "before")
        assertEquals(listOf<Any>("before"), received)

        assertTrue(host.unload(listener.id))

        publisherCtx.emit("t.evt", "after")
        assertEquals("卸载必须退订（effect）", listOf<Any>("before"), received)
        assertEquals("总线必须摘除该监听器", 0, bus.listenerCount("t.evt"))
    }

    @Test
    fun `卸载再装载后监听器重新生效且不重复`() {
        val received = mutableListOf<Any>()
        val listener = FakeEventPlugin(id = "t.reload") { ctx -> ctx.on("t.evt") { received += it } }
        val publisher = FakeEventPlugin(id = "t.pub2") { }
        val (host, bus) = hostWith(listener, publisher)
        loadAll(host, listener, publisher)
        val publisherCtx = ctxOf(publisher)

        publisherCtx.emit("t.evt", "1")
        assertTrue(host.unload(listener.id))
        publisherCtx.emit("t.evt", "2")
        assertEquals(PluginLoadResult.Loaded, host.load(listener.id, null))
        assertEquals("重新装载后必须只订阅一次", 1, bus.listenerCount("t.evt"))
        publisherCtx.emit("t.evt", "3")

        assertEquals(listOf<Any>("1", "3"), received)
    }

    @Test
    fun `装配失败回滚后监听器不残留`() {
        val received = mutableListOf<Any>()
        val failing = FakeEventPlugin(id = "t.failing") { ctx ->
            ctx.on("t.evt") { received += it }
            throw IllegalStateException("setup boom")
        }
        val publisher = FakeEventPlugin(id = "t.pub3") { }
        val (host, bus) = hostWith(failing, publisher)
        assertEquals(PluginLoadResult.Loaded, host.load(publisher.id, null))

        val result = host.load(failing.id, null)

        assertTrue(result is PluginLoadResult.Failed)
        assertEquals("装配失败必须回滚订阅", 0, bus.listenerCount("t.evt"))
        ctxOf(publisher).emit("t.evt", "x")
        assertTrue("回滚后的监听器不得再被调用", received.isEmpty())
    }

    // ── 3. 派发模式：parallel / serial / bail（验收标准 3） ──

    /**
     * 「同时在跑」的判定器：进入 +1、退出 -1，记录峰值。
     *
     * 峰值 2 = 两个监听器在**同一瞬间**都处于执行中（真并发）；峰值 1 = 顺序执行。
     * 不依赖 sleep / 超时，因此不会「因为超时才勉强通过」——顺序执行会稳定失败。
     */
    private class OverlapProbe {
        private val active = java.util.concurrent.atomic.AtomicInteger(0)
        private val peak = java.util.concurrent.atomic.AtomicInteger(0)

        val maxConcurrent: Int get() = peak.get()

        fun <T> track(block: () -> T): T {
            val now = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                return block()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Test
    fun `parallel 并发执行全部监听器且等待全部结束`() = runBlocking {
        val probe = OverlapProbe()
        val finished = CountDownLatch(2)
        val one = FakeEventPlugin(id = "t.p1") { ctx ->
            ctx.on("t.evt") {
                probe.track {
                    // 若顺序执行，这里会阻塞到超时（然后测试在峰值断言上失败）。
                    finished.countDown()
                    finished.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val two = FakeEventPlugin(id = "t.p2") { ctx ->
            ctx.on("t.evt") {
                probe.track {
                    finished.countDown()
                    finished.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val (host, _) = hostWith(one, two)
        loadAll(host, one, two)

        val result = ctxOf(one).parallel("t.evt", Unit)

        assertEquals("parallel 不短路，恒返回 none", PluginEventResult.none, result)
        assertEquals(
            "两个监听器必须同时处于执行中（真并发，而非顺序执行）",
            2,
            probe.maxConcurrent,
        )
        assertEquals("parallel 必须等待全部监听器结束", 0L, finished.count)
    }

    @Test
    fun `parallel 的失败聚合为 PluginDispatchException 且其他监听器照常执行`() = runBlocking {
        val calls = mutableListOf<String>()
        val bad = FakeEventPlugin(id = "t.bad") { ctx ->
            ctx.on("t.evt") { calls += "bad"; throw IllegalArgumentException("nope") }
        }
        val good = FakeEventPlugin(id = "t.good") { ctx -> ctx.on("t.evt") { calls += "good" } }
        val (host, _) = hostWith(bad, good)
        loadAll(host, bad, good)

        try {
            ctxOf(bad).parallel("t.evt", Unit)
            fail("parallel 必须把监听器失败聚合上报")
        } catch (e: PluginDispatchException) {
            assertEquals("t.evt", e.event)
            assertEquals(1, e.failures.size)
            assertTrue(
                "失败描述必须点名异常与消息: " + e.failures,
                e.failures[0].contains("IllegalArgumentException"),
            )
            assertTrue("失败描述必须带消息: " + e.failures, e.failures[0].contains("nope"))
            assertTrue("消息必须可读: " + e.message, e.message!!.contains("event=t.evt"))
        }
        assertEquals("有监听器失败时其他监听器仍必须执行", listOf("bad", "good"), calls.sorted())
    }

    @Test
    fun `serial 遇 bail 值停止后续监听器并返回该值`() = runBlocking {
        val order = mutableListOf<String>()
        val one = FakeEventPlugin(id = "t.s1") { ctx -> ctx.onBail("t.evt") { order += "one"; null } }
        val two = FakeEventPlugin(id = "t.s2") { ctx ->
            ctx.onBail("t.evt") { order += "two"; "stop-here" }
        }
        val three = FakeEventPlugin(id = "t.s3") { ctx -> ctx.onBail("t.evt") { order += "three"; null } }
        val (host, _) = hostWith(one, two, three)
        loadAll(host, one, two, three)

        val result = ctxOf(one).serial("t.evt", Unit)

        assertEquals("serial 必须在 bail 处停止", listOf("one", "two"), order)
        assertTrue(result.isBailed)
        assertEquals("stop-here", result.bailValue)
    }

    @Test
    fun `serial 全部不 bail 时返回 none 且全部执行`() = runBlocking {
        val order = mutableListOf<String>()
        val one = FakeEventPlugin(id = "t.n1") { ctx -> ctx.onBail("t.evt") { order += "one"; null } }
        val two = FakeEventPlugin(id = "t.n2") { ctx -> ctx.on("t.evt") { order += "two" } }
        val (host, _) = hostWith(one, two)
        loadAll(host, one, two)

        val result = ctxOf(one).serial("t.evt", Unit)

        assertEquals(PluginEventResult.none, result)
        assertEquals(listOf("one", "two"), order)
    }

    @Test
    fun `bail 同步遇 bail 值停止后续监听器`() {
        val order = mutableListOf<String>()
        val one = FakeEventPlugin(id = "t.b1") { ctx ->
            ctx.onBail("t.evt") { order += "one"; PluginEventResult.bail("stop") }
        }
        val two = FakeEventPlugin(id = "t.b2") { ctx -> ctx.onBail("t.evt") { order += "two"; null } }
        val (host, _) = hostWith(one, two)
        loadAll(host, one, two)

        val result = ctxOf(one).bail("t.evt", Unit)

        assertTrue(result.isBailed)
        assertEquals("stop", result.bailValue)
        assertEquals("bail 必须同步短路", listOf("one"), order)
    }

    @Test
    fun `bail 支持携带 null 的 bail 值（与未 bail 区分）`() {
        val order = mutableListOf<String>()
        val one = FakeEventPlugin(id = "t.bn1") { ctx ->
            // 显式返回 PluginEventResult.bail(null)：Rust 侧 Some(null) 同样是 bail。
            ctx.onBail("t.evt") { order += "one"; PluginEventResult.bail(null) }
        }
        val two = FakeEventPlugin(id = "t.bn2") { ctx -> ctx.onBail("t.evt") { order += "two"; null } }
        val (host, _) = hostWith(one, two)
        loadAll(host, one, two)

        val result = ctxOf(one).bail("t.evt", Unit)

        assertTrue("bail(null) 仍是 bail（对齐 is_bailed = Option::is_some）", result.isBailed)
        assertEquals(null, result.bailValue)
        assertEquals(listOf("one"), order)
    }

    // ── 4. waterfall 组合顺序（验收标准 3） ──

    @Test
    fun `waterfall 的监听器按注册顺序包在 inner 外面`() {
        val order = mutableListOf<String>()
        val outer = FakeEventPlugin(id = "t.w1") { ctx ->
            ctx.onWaterfall("t.evt") { _, next ->
                order += "outer-before"
                val r = next.next()
                order += "outer-after"
                r
            }
        }
        val inner = FakeEventPlugin(id = "t.w2") { ctx ->
            ctx.onWaterfall("t.evt") { _, next ->
                order += "inner-before"
                val r = next.next()
                order += "inner-after"
                r
            }
        }
        val (host, _) = hostWith(outer, inner)
        loadAll(host, outer, inner)

        val result = ctxOf(outer).waterfall("t.evt", Unit) {
            order += "innermost"
            PluginEventResult.bail("composed")
        }

        assertEquals(
            "首个注册的监听器必须在最外层，innermost 在链尾",
            listOf("outer-before", "inner-before", "innermost", "inner-after", "outer-after"),
            order,
        )
        assertTrue(result.isBailed)
        assertEquals("composed", result.bailValue)
    }

    @Test
    fun `waterfall 的监听器不调用 next 即短路 inner`() {
        val order = mutableListOf<String>()
        val shortCircuit = FakeEventPlugin(id = "t.ws1") { ctx ->
            ctx.onWaterfall("t.evt") { _, _ ->
                order += "short-circuit"
                PluginEventResult.none
            }
        }
        val never = FakeEventPlugin(id = "t.ws2") { ctx ->
            ctx.onWaterfall("t.evt") { _, next -> order += "never"; next.next() }
        }
        val (host, _) = hostWith(shortCircuit, never)
        loadAll(host, shortCircuit, never)

        ctxOf(shortCircuit).waterfall("t.evt", Unit) {
            order += "innermost"
            PluginEventResult.none
        }

        assertEquals("不调用 next 必须短路下游与 inner", listOf("short-circuit"), order)
    }

    @Test
    fun `waterfall 监听器抛异常不掐断整条链`() {
        val order = mutableListOf<String>()
        val thrower = FakeEventPlugin(id = "t.wt1") { ctx ->
            ctx.onWaterfall("t.evt") { _, _ ->
                order += "thrower"
                throw IllegalStateException("w-boom")
            }
        }
        val passthrough = FakeEventPlugin(id = "t.wt2") { ctx ->
            ctx.onWaterfall("t.evt") { _, next -> order += "passthrough"; next.next() }
        }
        val (host, _) = hostWith(thrower, passthrough)
        loadAll(host, thrower, passthrough)

        val result = ctxOf(thrower).waterfall("t.evt", Unit) {
            order += "innermost"
            PluginEventResult.none
        }

        assertEquals(
            "坏监听器退化为「不 bail 直接往下走」，链继续",
            listOf("thrower", "passthrough", "innermost"),
            order,
        )
        assertEquals(PluginEventResult.none, result)
    }

    @Test
    fun `waterfall 无监听器时直接执行 inner`() {
        val plugin = FakeEventPlugin(id = "t.we") { }
        val (host, _) = hostWith(plugin)
        loadAll(host, plugin)

        var innerCalls = 0
        val result = ctxOf(plugin).waterfall("t.evt", Unit) {
            innerCalls++
            PluginEventResult.none
        }

        assertEquals(1, innerCalls)
        assertEquals(PluginEventResult.none, result)
    }

    // ── 5. 宿主预置 AGENT（验收标准 4） ──

    @Test
    fun `宿主未预置 AGENT 时 requires 含 AGENT 的插件装载 fail-closed 且不留半装配`() {
        val (host, bus) = newHost()
        val injected = mutableListOf<Any>()
        val plugin = FakeEventPlugin(
            id = "t.needs.agent",
            kind = PluginKind.ADAPTER,
            requires = setOf(PluginServices.AGENT),
        ) { ctx ->
            injected += ctx.inject<DialogueCoordinator>(PluginServices.AGENT)
        }
        host.register(plugin)

        val result = host.load(plugin.id, null)

        assertTrue("缺 AGENT 必须 fail-closed: " + result, result is PluginLoadResult.Failed)
        val reason = (result as PluginLoadResult.Failed).reason
        assertTrue("原因必须点名缺失的服务键: " + reason, reason.contains(PluginServices.AGENT))
        assertTrue("原因必须说明宿主未预置: " + reason, reason.contains("缺少依赖服务"))
        assertEquals("被拒绝的装载不得执行 setup", 0, plugin.setupCount)
        assertEquals("不得拿到任何上下文", null, plugin.ctx)
        assertFalse("不得留下装载态", host.isLoaded(plugin.id))
        assertTrue("loadedIds 必须为空（无半装配）", host.loadedIds().isEmpty())
        assertTrue("不得注入任何东西", injected.isEmpty())
        assertEquals("不得留下监听器", 0, bus.listenerCount("t.evt"))
    }

    @Test
    fun `宿主预置 AGENT 后同一插件可装载并注入到同一个实例`() {
        val agent = FakeDialogueCoordinator()
        val (host, _) = newHost(PluginServices.AGENT to agent)
        val injected = mutableListOf<DialogueCoordinator>()
        val plugin = FakeEventPlugin(
            id = "t.agent.ok",
            kind = PluginKind.ADAPTER,
            requires = setOf(PluginServices.AGENT),
        ) { ctx -> injected += ctx.inject<DialogueCoordinator>(PluginServices.AGENT) }
        host.register(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertEquals(1, injected.size)
        assertSame("插件必须拿到宿主预置的同一个实例", agent, injected[0])
        assertTrue(host.isLoaded(plugin.id))
    }

    @Test
    fun `AGENT 与其余框架服务键互不干扰且可同时声明`() {
        val (host, _) = newHost(PluginServices.AGENT to FakeDialogueCoordinator())
        val plugin = FakeEventPlugin(
            id = "t.multi.dep",
            requires = setOf(PluginServices.AGENT, PluginServices.TOOLS),
        )
        host.register(plugin)

        val result = host.load(plugin.id, null)

        assertTrue("TOOLS 未预置时同样 fail-closed: " + result, result is PluginLoadResult.Failed)
        assertTrue(
            "原因必须点名 TOOLS: " + (result as PluginLoadResult.Failed).reason,
            result.reason.contains(PluginServices.TOOLS),
        )
        assertFalse(host.isLoaded(plugin.id))
        assertEquals("setup 不得执行", 0, plugin.setupCount)
    }

    // ── 6. 宿主边界与契约面（总线是宿主级的，不是全局的） ──

    @Test
    fun `两个宿主实例的总线互不可见`() {
        val received = mutableListOf<Any>()
        val subscriber = FakeEventPlugin(id = "t.iso.sub") { ctx -> ctx.on("t.evt") { received += it } }
        val publisher = FakeEventPlugin(id = "t.iso.pub") { }
        val (hostA, _) = hostWith(subscriber)
        val (hostB, _) = hostWith(publisher)
        assertEquals(PluginLoadResult.Loaded, hostA.load(subscriber.id, null))
        assertEquals(PluginLoadResult.Loaded, hostB.load(publisher.id, null))

        ctxOf(publisher).emit("t.evt", "cross-host")

        assertTrue("总线必须按宿主隔离（不是进程级全局表）", received.isEmpty())
    }

    @Test
    fun `宿主整体回收清空监听表`() {
        val subscriber = FakeEventPlugin(id = "t.gc.sub") { ctx -> ctx.on("t.evt") { } }
        val publisher = FakeEventPlugin(id = "t.gc.pub") { }
        val (host, bus) = hostWith(subscriber, publisher)
        loadAll(host, subscriber, publisher)
        assertEquals(1, bus.listenerCount("t.evt"))

        host.disposeAll()

        assertEquals(0, bus.listenerCount("t.evt"))
        assertTrue(host.loadedIds().isEmpty())
    }

    @Test
    fun `emit 是 PluginContext 的成员而非服务键`() {
        val plugin = FakeEventPlugin(id = "t.member") { ctx ->
            // 绑定成员引用：只有 PluginContext 真的声明了这些成员才编译得过。
            val emitRef: (String, Any) -> Unit = ctx::emit
            val parallelRef: suspend (String, Any) -> PluginEventResult = ctx::parallel
            val serialRef: suspend (String, Any) -> PluginEventResult = ctx::serial
            val bailRef: (String, Any) -> PluginEventResult = ctx::bail
            val waterfallRef: (String, Any, () -> PluginEventResult) -> PluginEventResult =
                ctx::waterfall
            val onBailRef: (String, PluginEventListener) -> Unit = ctx::onBail
            val onWaterfallRef: (String, PluginWaterfallListener) -> Unit = ctx::onWaterfall
            check(emitRef.hashCode() + parallelRef.hashCode() + serialRef.hashCode() != Int.MIN_VALUE)
            check(bailRef.hashCode() + waterfallRef.hashCode() != Int.MIN_VALUE)
            check(onBailRef.hashCode() + onWaterfallRef.hashCode() != Int.MIN_VALUE)
            // 不声明任何 requires 也能派发：事件能力不是服务键。
            ctx.emit("t.member.evt", Unit)
        }
        val (host, _) = hostWith(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertTrue("插件无需声明 requires 即可使用事件能力", plugin.requires.isEmpty())
    }

    @Test
    fun `onWaterfall 的 lambda 形参类型就是 PluginEventNext`() {
        val plugin = FakeEventPlugin(id = "t.next.type") { ctx ->
            ctx.onWaterfall("t.evt") { _, next: PluginEventNext -> next.next() }
        }
        val (host, _) = hostWith(plugin)

        assertEquals(PluginLoadResult.Loaded, host.load(plugin.id, null))
        assertEquals(1, host.loadedIds().size)
    }
}
