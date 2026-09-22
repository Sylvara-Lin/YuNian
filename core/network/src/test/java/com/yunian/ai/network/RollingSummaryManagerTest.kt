package com.yunian.ai.network

import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.model.AppMetaEntity
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.domain.ConversationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * RollingSummaryManager 单元测试（QA/T04）。
 *
 * 取舍说明：AppMetaStore 为 final class，但其依赖的 AppMetaDao 是普通 Kotlin
 * interface（Room 注解仅为生成实现用），因此用内存 Map 实现 FakeAppMetaDao，
 * 走真实 AppMetaStore 序列化路径（覆盖 KV 编解码 + runCatching 容错语义），
 * 无需 Robolectric。
 *
 * fire-and-forget 测试采用 Dispatchers.Unconfined 背景 scope：
 * 合并协程在调用线程同步执行至第一个真正挂起点，配合可人工放行的
 * CompletableDeferred 闸门即可确定性地构造「在飞合并」状态。
 */
class RollingSummaryManagerTest {

    // ---------- Fakes ----------

    private class FakeAppMetaDao : AppMetaDao {
        val map = ConcurrentHashMap<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun put(entity: AppMetaEntity) {
            map[entity.key] = entity.value
        }
        override suspend fun remove(key: String) {
            map.remove(key)
        }
        override suspend fun getAll(): List<AppMetaEntity> =
            map.map { (k, v) -> AppMetaEntity(key = k, value = v) }
    }

    /** 记录每次合并调用的 (oldSummary, deltaText) 并可挂起等待放行。 */
    private class RecordingSummarizer(
        private val responder: (old: String, delta: String) -> String? = { _, delta -> "MERGED[$delta]" }
    ) {
        val calls = mutableListOf<Pair<String, String>>()
        @Volatile var gate: CompletableDeferred<Unit>? = null

        suspend fun invoke(old: String, delta: String): String? {
            calls.add(old to delta)
            gate?.await()
            return responder(old, delta)
        }
    }

    private fun msg(id: Long, content: String, isFromUser: Boolean = false) =
        ChatMessage(id = id, companionId = 1L, content = content, isFromUser = isFromUser)

    private fun newManager(
        dao: FakeAppMetaDao = FakeAppMetaDao(),
        summarizer: RecordingSummarizer = RecordingSummarizer(),
        backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        gapFetcher: (suspend (Long, Long) -> List<ChatMessage>)? = null
    ): Pair<RollingSummaryManager, RecordingSummarizer> {
        val manager = RollingSummaryManager(
            metaStore = AppMetaStore(dao),
            mergeSummarizer = { old, delta, _, _, _ -> summarizer.invoke(old, delta) },
            backgroundScope = backgroundScope,
            gapFetcher = gapFetcher
        )
        return manager to summarizer
    }

    private fun scopeOf(id: Long = 1L) = ConversationScope.Single(id)

    // ---------- ConversationScope.storeKey ----------

    @Test
    fun `storeKey format chat and group`() {
        assertEquals("context.rolling_summary.chat-7", ConversationScope.Single(7L).storeKey())
        assertEquals("context.rolling_summary.group-9", ConversationScope.Group(9L).storeKey())
    }

    // ---------- mergeBlocking 基本路径 ----------

    @Test
    fun `mergeBlocking establishes state and advances watermark`() = runBlocking {
        val dao = FakeAppMetaDao()
        val (manager, summarizer) = newManager(dao)

        val state = manager.mergeBlocking(
            scope = scopeOf(),
            delta = listOf(msg(1, "a"), msg(2, "b"), msg(3, "c", isFromUser = true)),
            batchEndId = 3L
        )

        assertNotNull(state)
        assertEquals(3L, state!!.coveredUpToMessageId)
        assertEquals(3, state.coveredMessageCount)
        assertTrue(state.summaryText.startsWith("MERGED["))
        // 单聊用户消息发送者应为「用户」
        assertTrue(state.summaryText.contains("用户：c"))
        assertEquals(1, summarizer.calls.size)
        assertEquals("", summarizer.calls[0].first) // 首次合并旧摘要为空
        // 落 KV
        assertNotNull(dao.map[scopeOf().storeKey()])
    }

    @Test
    fun `mergeBlocking double-check skips already covered batch`() = runBlocking {
        val (manager, summarizer) = newManager()

        manager.mergeBlocking(scopeOf(), listOf(msg(1, "a"), msg(2, "b")), batchEndId = 2L)
        val second = manager.mergeBlocking(scopeOf(), listOf(msg(1, "a"), msg(2, "b")), batchEndId = 2L)

        // 双检：水位线已覆盖 batchEndId → 原样返回现有状态，不再调 LLM
        assertNotNull(second)
        assertEquals(1, summarizer.calls.size)
        assertEquals(2L, second!!.coveredUpToMessageId)
    }

    @Test
    fun `delta fully below watermark advances watermark without LLM`() = runBlocking {
        val (manager, summarizer) = newManager()

        manager.mergeBlocking(scopeOf(), listOf(msg(1, "a")), batchEndId = 5L)
        assertEquals(1, summarizer.calls.size)

        val state = manager.mergeBlocking(scopeOf(), listOf(msg(2, "b"), msg(3, "c")), batchEndId = 8L)

        // 全部 delta 已被水位线覆盖 → 只推进水位线，不再调 mergeSummarizer
        assertEquals(1, summarizer.calls.size)
        assertNotNull(state)
        assertEquals(8L, state!!.coveredUpToMessageId)
        assertEquals(1, state.coveredMessageCount) // 计数不重复累加
    }

    @Test
    fun `mergeBlocking with prewrittenText skips LLM and persists directly`() = runBlocking {
        val (manager, summarizer) = newManager()

        val state = manager.mergeBlocking(
            scope = scopeOf(),
            delta = listOf(msg(1, "a"), msg(2, "b")),
            batchEndId = 2L,
            prewrittenText = "  首启同步摘要  "
        )

        assertEquals(0, summarizer.calls.size) // 不允许二次 LLM
        assertNotNull(state)
        assertEquals("首启同步摘要", state!!.summaryText)
        assertEquals(2L, state.coveredUpToMessageId)
    }

    @Test
    fun `mergeBlocking silent failure keeps state unchanged`() = runBlocking {
        val dao = FakeAppMetaDao()
        val summarizer = RecordingSummarizer { _, _ -> null } // LLM 失败返回 null
        val (manager, _) = newManager(dao, summarizer)

        val result = manager.mergeBlocking(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)

        assertNull(result)
        assertNull(dao.map[scopeOf().storeKey()]) // 不落 KV（失败静默）
        assertTrue(summarizer.calls.isNotEmpty())
    }

    @Test
    fun `empty delta or invalid batchEndId returns null without side effects`() = runBlocking {
        val (manager, summarizer) = newManager()

        assertNull(manager.mergeBlocking(scopeOf(), emptyList(), batchEndId = 5L))
        assertNull(manager.mergeBlocking(scopeOf(), listOf(msg(1, "a")), batchEndId = 0L))
        assertEquals(0, summarizer.calls.size)
    }

    // ---------- 分段边界：SEGMENT_MAX_MESSAGES = 48 ----------

    @Test
    fun `47 messages merge in single segment`() = runBlocking {
        val (manager, summarizer) = newManager()
        val delta = (1L..47L).map { msg(it, "m$it") }

        val state = manager.mergeBlocking(scopeOf(), delta, batchEndId = 47L)

        assertEquals(1, summarizer.calls.size)
        assertEquals(47, state!!.coveredMessageCount)
    }

    @Test
    fun `48 messages merge in single segment`() = runBlocking {
        val (manager, summarizer) = newManager()
        val delta = (1L..48L).map { msg(it, "m$it") }

        val state = manager.mergeBlocking(scopeOf(), delta, batchEndId = 48L)

        assertEquals(1, summarizer.calls.size)
        assertEquals(48, state!!.coveredMessageCount)
    }

    @Test
    fun `49 messages split into two segments merged sequentially`() = runBlocking {
        val (manager, summarizer) = newManager()
        val delta = (1L..49L).map { msg(it, "m$it") }

        val state = manager.mergeBlocking(scopeOf(), delta, batchEndId = 49L)

        // 48 条一段 + 1 条一段；第二段以第一段产物为 oldSummary 顺序合并
        assertEquals(2, summarizer.calls.size)
        assertTrue(summarizer.calls[0].first.isEmpty())
        assertEquals("MERGED[" + summarizer.calls[0].second + "]", summarizer.calls[1].first)
        assertTrue(summarizer.calls[1].second.contains("m49"))
        assertEquals(49, state!!.coveredMessageCount)
        assertEquals(49L, state.coveredUpToMessageId)
    }

    @Test
    fun `second segment failure advances watermark only to first segment end`() = runBlocking {
        val dao = FakeAppMetaDao()
        var failed = false
        val summarizer = RecordingSummarizer { _, delta ->
            // 第二段（含 m49）首次合并失败，之后恢复（供重试用例）
            if (!failed && delta.contains("m49")) {
                failed = true
                null
            } else {
                "MERGED[$delta]"
            }
        }
        val (manager, _) = newManager(dao, summarizer)
        val delta = (1L..49L).map { msg(it, "m$it") }

        val state = manager.mergeBlocking(scopeOf(), delta, batchEndId = 49L)

        // 第一段成功、第二段失败：水位线只推进到第一段末尾（48），
        // 失败段消息不进摘要、也不推进水位线 → 不会静默丢失
        assertEquals(2, summarizer.calls.size)
        assertNotNull(state)
        assertEquals(48L, state!!.coveredUpToMessageId)
        assertEquals(48, state.coveredMessageCount)

        // 失败段下次重试：水位线过滤后只剩 m49 进摘要
        val retried = manager.mergeBlocking(scopeOf(), delta, batchEndId = 49L)

        assertEquals(3, summarizer.calls.size)
        assertTrue(summarizer.calls[2].second.contains("m49"))
        assertFalse(summarizer.calls[2].second.contains("m48"))
        assertEquals(49L, retried!!.coveredUpToMessageId)
        assertEquals(49, retried.coveredMessageCount)
    }

    // ---------- requestIncrementalMerge：inFlight 防重 ----------

    @Test
    fun `requestIncrementalMerge deduplicates concurrent merges via inFlight`() = runBlocking {
        val dao = FakeAppMetaDao()
        val summarizer = RecordingSummarizer()
        val gate = CompletableDeferred<Unit>()
        summarizer.gate = gate // 挂起第一次合并，制造「在飞」状态
        val (manager, _) = newManager(dao, summarizer)

        manager.requestIncrementalMerge(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)
        // Unconfined：协程已在 gate.await 处挂起，inFlight 已置位
        assertEquals(1, summarizer.calls.size)

        // 第二次触发应被 inFlight 直接丢弃
        manager.requestIncrementalMerge(scopeOf(), listOf(msg(2, "b")), batchEndId = 2L)

        // 放行第一次合并，跑完
        gate.complete(Unit)
        assertEquals(1, summarizer.calls.size) // 第二次从未进入 summarizer

        val saved = manager.loadState(scopeOf())
        assertNotNull(saved)
        assertEquals(1L, saved!!.coveredUpToMessageId) // 只有第一批被覆盖

        // inFlight 已在 finally 中清除：新的合并可以再次进行
        manager.requestIncrementalMerge(scopeOf(), listOf(msg(2, "b")), batchEndId = 2L)
        assertEquals(2, summarizer.calls.size)
        assertEquals(2L, manager.loadState(scopeOf())!!.coveredUpToMessageId)
    }

    @Test
    fun `requestIncrementalMerge ignores empty delta and invalid batchEndId`() = runBlocking {
        val (manager, summarizer) = newManager()

        manager.requestIncrementalMerge(scopeOf(), emptyList(), batchEndId = 5L)
        manager.requestIncrementalMerge(scopeOf(), listOf(msg(1, "a")), batchEndId = 0L)

        // 给 Unconfined 协程执行机会（同步执行，此处已返回即完成）
        assertEquals(0, summarizer.calls.size)
        assertNull(manager.loadState(scopeOf()))
    }

    @Test
    fun `requestIncrementalMerge swallows summarizer exception`() = runBlocking {
        val summarizer = RecordingSummarizer { _, _ -> throw IllegalStateException("LLM down") }
        val (manager, _) = newManager(summarizer = summarizer)

        // fire-and-forget：异常被 launch 的 catch 静默，不得向调用方抛出
        manager.requestIncrementalMerge(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)

        assertNull(manager.loadState(scopeOf()))
        assertEquals(1, summarizer.calls.size)
    }

    // ---------- P2-B1/B2：inFlight 释放 + 失败退避 ----------

    @Test
    fun `requestIncrementalMerge backs off after failure and skips retry within window`() = runBlocking {
        val summarizer = RecordingSummarizer { _, _ -> null } // 合并失败（返回空）
        val (manager, _) = newManager(summarizer = summarizer)

        // 首次触发：合并失败计入退避状态；协程同步跑完后 inFlight 已释放
        manager.requestIncrementalMerge(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)
        assertEquals(1, summarizer.calls.size)
        assertTrue("失败后 inFlight 标记必须释放", inFlightKeys(manager).isEmpty())

        // 退避窗口内（首次失败 +5 分钟）再次触发被直接跳过，不打合并 API / 不占 inFlight
        manager.requestIncrementalMerge(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)
        assertEquals(1, summarizer.calls.size)
    }

    @Test
    fun `requestIncrementalMerge inFlight released even when job body never runs`() {
        // P2-B1：背景 scope 已取消时 launch 的协程体不会执行，旧 finally 不触发会永久滞留标记；
        // invokeOnCompletion 保证标记释放（反射直读私有 inFlight 集作确定性断言）。
        val cancelledScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        cancelledScope.cancel()
        val summarizer = RecordingSummarizer()
        val manager = RollingSummaryManager(
            metaStore = AppMetaStore(FakeAppMetaDao()),
            mergeSummarizer = { old, delta, _, _, _ -> summarizer.invoke(old, delta) },
            backgroundScope = cancelledScope,
            gapFetcher = null
        )

        manager.requestIncrementalMerge(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)
        assertEquals(0, summarizer.calls.size) // 协程体从未执行
        assertTrue("inFlight 标记必须被 invokeOnCompletion 释放", inFlightKeys(manager).isEmpty())
    }

    /** 反射读取私有 inFlight 集的快照（仅测试用，验证 P2-B1 标记释放）。 */
    @Suppress("UNCHECKED_CAST")
    private fun inFlightKeys(manager: RollingSummaryManager): Set<String> {
        val field = RollingSummaryManager::class.java.getDeclaredField("inFlight")
        field.isAccessible = true
        return (field.get(manager) as Set<String>).toSet()
    }

    // ---------- gapFetcher 补拉（仅 Single） ----------

    @Test
    fun `gap between watermark and delta is fetched for Single scope`() = runBlocking {
        val dao = FakeAppMetaDao()
        val fetchedArgs = mutableListOf<Pair<Long, Long>>()
        val gapFetcher: suspend (Long, Long) -> List<ChatMessage> = { companionId, afterId ->
            fetchedArgs.add(companionId to afterId)
            listOf(msg(6, "g6"), msg(7, "g7"))
        }
        val (manager, summarizer) = newManager(dao, gapFetcher = gapFetcher)

        // 先建立水位线 5
        manager.mergeBlocking(scopeOf(3L), listOf(msg(1, "a"), msg(2, "b"), msg(3, "c")), batchEndId = 5L)
        assertEquals(1, summarizer.calls.size)

        // 增量从 id=8 开始，水位线 5 与 8 之间有空洞 → 补拉 6、7
        val state = manager.mergeBlocking(
            scopeOf(3L),
            listOf(msg(8, "d8"), msg(9, "d9")),
            batchEndId = 9L
        )

        assertEquals(listOf(3L to 5L), fetchedArgs)
        assertEquals(2, summarizer.calls.size)
        val mergedDelta = summarizer.calls[1].second
        assertTrue("补拉消息应进摘要: $mergedDelta", mergedDelta.contains("g6"))
        assertTrue(mergedDelta.contains("g7"))
        assertTrue(mergedDelta.contains("d8"))
        assertTrue(mergedDelta.contains("d9"))
        assertEquals(9L, state!!.coveredUpToMessageId)
        assertEquals(3 + 4, state.coveredMessageCount) // 3 + 补拉2 + delta2
    }

    @Test
    fun `no gap when delta continues watermark directly`() = runBlocking {
        var gapFetchCount = 0
        val gapFetcher: suspend (Long, Long) -> List<ChatMessage> = { _, _ ->
            gapFetchCount++
            emptyList()
        }
        val (manager, summarizer) = newManager(gapFetcher = gapFetcher)

        manager.mergeBlocking(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)
        manager.mergeBlocking(scopeOf(), listOf(msg(2, "b")), batchEndId = 2L)

        assertEquals(0, gapFetchCount) // id 连续 → 不补拉
        assertEquals(2, summarizer.calls.size)
    }

    // ---------- clear / loadState ----------

    @Test
    fun `clear removes persisted state`() = runBlocking {
        val dao = FakeAppMetaDao()
        val (manager, _) = newManager(dao)

        manager.mergeBlocking(scopeOf(), listOf(msg(1, "a")), batchEndId = 1L)
        assertNotNull(manager.loadState(scopeOf()))

        manager.clear(scopeOf())
        assertNull(manager.loadState(scopeOf()))
        assertFalse(dao.map.containsKey(scopeOf().storeKey()))
    }

    @Test
    fun `loadState returns null when no state`() = runBlocking {
        val (manager, _) = newManager()
        assertNull(manager.loadState(scopeOf()))
    }
}
