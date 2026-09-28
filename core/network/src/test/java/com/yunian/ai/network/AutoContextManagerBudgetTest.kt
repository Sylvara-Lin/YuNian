package com.yunian.ai.network

import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.model.AppMetaEntity
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.domain.ConversationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * AutoContextManager 预算与滚动摘要集成测试（QA/T04）。
 *
 * 覆盖：预算分配顺序（system → turnContext → summary → memory → history）、
 * 摘要压缩保尾段、memory 整条取舍、原文水位线过滤、pending 双门槛触发、
 * 首启同步摘要 prewrittenText 落 KV、陈旧防护、占位 scope 不落 KV。
 *
 * 预算口径（用于精确构造溢出）：model "gpt-4" 窗口 8192，
 * maxOutputTokens=7000、safetyMargin=512 → totalBudget = 680。
 * TokenEstimator：CJK≈1.5 token/字、ASCII≈0.25 token/字符；
 * history 逐条估算 = 4 + content，列表再 +3。
 * 内容统一用「[m<id>]」标记保证断言无子串歧义（"m1" ⊂ "m10" 陷阱）。
 */
class AutoContextManagerBudgetTest {

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

    /** 记录滚动合并 LLM 调用（fake，绝不真调 LLM）。 */
    private class RecordingMergeSummarizer {
        val calls = mutableListOf<Pair<String, String>>()
        suspend fun invoke(old: String, delta: String): String? {
            calls.add(old to delta)
            return "MERGED[$delta]"
        }
    }

    /** 记录一次性同步摘要调用。 */
    private class RecordingAiSummarizer(private val response: String = "首启摘要文本") {
        val calls = mutableListOf<Int>()
        suspend fun invoke(messages: List<ChatMessage>): String? {
            calls.add(messages.size)
            return response
        }
    }

    private fun msg(id: Long, content: String, isFromUser: Boolean = false) =
        ChatMessage(id = id, companionId = 1L, content = content, isFromUser = isFromUser)

    /** 唯一标记 + ASCII 填充：总长恰为 [totalChars] 个 ASCII 字符（est = totalChars/4）。 */
    private fun asciiContent(id: Long, totalChars: Int): String {
        val marker = "[m$id]"
        val pad = (totalChars - marker.length).coerceAtLeast(0)
        return marker + "x".repeat(pad)
    }

    private suspend fun seedState(dao: FakeAppMetaDao, key: String, state: RollingSummaryState) {
        AppMetaStore(dao).put(key, state, RollingSummaryState.serializer())
    }

    /** 组装被测对象；fire-and-forget 合并用 Unconfined scope 使其在调用线程同步完成。 */
    private fun newFixture(aiSummaryResponse: String = "首启摘要文本"): AutoContextFixture {
        val dao = FakeAppMetaDao()
        val mergeSummarizer = RecordingMergeSummarizer()
        val aiSummarizer = RecordingAiSummarizer(aiSummaryResponse)
        val rolling = RollingSummaryManager(
            metaStore = AppMetaStore(dao),
            mergeSummarizer = { old, delta, _, _, _ -> mergeSummarizer.invoke(old, delta) },
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            gapFetcher = null
        )
        val manager = AutoContextManager(
            aiSummarizer = { messages, _, _ -> aiSummarizer.invoke(messages) },
            rollingSummaryManager = rolling
        )
        return AutoContextFixture(manager, dao, rolling, mergeSummarizer, aiSummarizer)
    }

    private class AutoContextFixture(
        val manager: AutoContextManager,
        val dao: FakeAppMetaDao,
        val rolling: RollingSummaryManager,
        val mergeSummarizer: RecordingMergeSummarizer,
        val aiSummarizer: RecordingAiSummarizer
    )

    /** totalBudget = 8192 - 7000 - 512 = 680。 */
    private fun buildConfig() = AutoContextManager.ContextConfig(
        model = "gpt-4",
        maxOutputTokens = 7000,
        safetyMargin = 512
    )

    private val scope = ConversationScope.Single(5L)
    private val key = scope.storeKey()

    // ---------- 纯函数：摘要压缩（保尾段，任何情况不丢整条） ----------

    @Test
    fun `compressSummaryToBudget returns text unchanged when within budget`() {
        val text = "一二三四五六七八九十"
        assertEquals(text, AutoContextManager().compressSummaryToBudget(text, 100))
    }

    @Test
    fun `compressSummaryToBudget drops oldest paragraphs keeps marker and tail`() {
        val para = "一二三四五六七八九十" // 10 CJK ≈ 15 tokens
        val text = listOf("P1", "P2", "P3", "P4", "P5").joinToString("\n") { it + para }
        // 5 段 ≈ 78 tokens；预算 60 → 丢最旧 2 段剩 3 段(≈47) + 标记(≈11) ≈ 58 ≤ 60，不触发截断
        val result = AutoContextManager().compressSummaryToBudget(text, 60)

        assertTrue("不应为空", result.isNotBlank()) // 任何情况不丢整条
        assertTrue("应含省略标记: $result", result.contains("[更早内容已省略]"))
        assertTrue("应保留尾部段落 P5: $result", result.contains("P5"))
        assertTrue("应保留尾部段落 P4: $result", result.contains("P4"))
        assertTrue("应保留段落 P3: $result", result.contains("P3"))
        assertFalse("最旧段落应被丢弃: $result", result.contains("P1"))
        assertFalse("次旧段落应被丢弃: $result", result.contains("P2"))
    }

    @Test
    fun `compressSummaryToBudget tiny budget still returns nonblank text`() {
        val para = "一二三四五六七八九十"
        val text = (1..5).joinToString("\n") { "P$it" + para }
        // 极小预算：段落数保到 2 后仍超限 → 字符截断兜底，但绝不为空
        val result = AutoContextManager().compressSummaryToBudget(text, 5)

        assertTrue("极小预算下也不得丢整条摘要", result.isNotBlank())
    }

    @Test
    fun `compressSummaryToBudget blank input or nonpositive budget yields empty`() {
        assertEquals("", AutoContextManager().compressSummaryToBudget("  ", 100))
        assertEquals("", AutoContextManager().compressSummaryToBudget("内容", 0))
        assertEquals("", AutoContextManager().compressSummaryToBudget("内容", -1))
    }

    // ---------- 纯函数：memory 整条取舍 ----------

    @Test
    fun `trimMemoryToBudget drops whole entries from oldest never splits`() {
        val para = "一二三四五六七八九十" // ≈15 tokens
        val text = "M1" + para + "\n\n" + "M2" + para + "\n\n" + "M3" + para
        // 3 段 ≈ 47 tokens；预算 33 → 丢 M1，剩 2 段 ≈ 31 ≤ 33
        val result = AutoContextManager().trimMemoryToBudget(text, 33)

        assertFalse("最旧整条应被丢弃: $result", result.contains("M1"))
        assertTrue("保留整条 M2 不切半: $result", result.contains("M2" + para))
        assertTrue("保留整条 M3 不切半: $result", result.contains("M3" + para))
    }

    @Test
    fun `trimMemoryToBudget falls back to truncation only when single entry exceeds budget`() {
        val single = "超".repeat(40) // 单条 ≈ 60 tokens
        val result = AutoContextManager().trimMemoryToBudget(single, 20)

        // 唯一一条仍超预算 → 字符截断兜底（非空、变短），而非整体丢弃
        assertTrue(result.isNotBlank())
        assertTrue(result.length < single.length)
    }

    @Test
    fun `trimMemoryToBudget within budget unchanged`() {
        val text = "记忆甲\n\n记忆乙"
        assertEquals(text, AutoContextManager().trimMemoryToBudget(text, 100))
    }

    // ---------- build：水位线过滤（原文只含 id > 水位线） ----------

    @Test
    fun `build filters original text by watermark and injects rolling summary`() = runBlocking {
        val f = newFixture()
        seedState(
            f.dao, key,
            RollingSummaryState(
                summaryText = "旧摘要文本内容",
                coveredUpToMessageId = 5L,
                coveredMessageCount = 5,
                updatedAt = 1L
            )
        )
        val history = (1L..10L).map { msg(it, asciiContent(it, 12)) }

        val result = f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            scope = scope
        )

        // 注入顺序：system → rolling summary → history → lastUser
        assertEquals("system", result[0].role)
        assertEquals("SYS", result[0].content)
        assertTrue("第二层应为滚动摘要: ${result[1].content}", result[1].content!!.contains("对话进展摘要"))
        assertTrue(result[1].content!!.contains("已压缩5条"))
        assertTrue(result[1].content!!.contains("旧摘要文本内容"))

        val historyText = result.drop(2).joinToString("\n") { it.content.orEmpty() }
        (1L..5L).forEach { id ->
            assertFalse("水位线之前(id=$id)的原文不得回放: $historyText", historyText.contains("[m$id]"))
        }
        (6L..10L).forEach { id ->
            assertTrue("水位线之后的原文应保留(id=$id): $historyText", historyText.contains("[m$id]"))
        }

        // lastUserMessage 兜底追加在最后
        assertEquals("user", result.last().role)
        assertEquals("hi", result.last().content)

        // 请求路径不调任何摘要 LLM
        assertEquals(0, f.aiSummarizer.calls.size)
        assertEquals(0, f.mergeSummarizer.calls.size)
    }

    // ---------- build：pending 双门槛触发（≥24 条 或 ≥2500 token） ----------

    @Test
    fun `pending at or above 24 messages triggers fire-and-forget merge`() = runBlocking {
        val f = newFixture()
        seedState(
            f.dao, key,
            RollingSummaryState(summaryText = "s", coveredUpToMessageId = 2L, coveredMessageCount = 2, updatedAt = 1L)
        )
        // 水位线 2，原文 3..61（59 条 × 19 token ≈ 1124 > 原文预算 ≈ 653）
        // → 保尾部 29 条、丢最旧 30 条（id 3..32）→ 达到 24 条门槛
        val history = (1L..61L).map { msg(it, asciiContent(it, 60)) }

        f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            scope = scope
        )

        // Unconfined：合并已同步完成
        assertEquals(1, f.mergeSummarizer.calls.size)
        val state = f.rolling.loadState(scope)
        assertNotNull(state)
        assertEquals(32L, state!!.coveredUpToMessageId)
        assertEquals(2 + 30, state.coveredMessageCount)
        val mergedDelta = f.mergeSummarizer.calls[0].second
        assertTrue("pending 起点应进合并: $mergedDelta", mergedDelta.contains("[m3]"))
        assertTrue("pending 终点应进合并: $mergedDelta", mergedDelta.contains("[m32]"))
        assertFalse("pending 之外不得混入: $mergedDelta", mergedDelta.contains("[m33]"))
    }

    @Test
    fun `pending below both thresholds does not trigger merge`() = runBlocking {
        val f = newFixture()
        seedState(
            f.dao, key,
            RollingSummaryState(summaryText = "s", coveredUpToMessageId = 2L, coveredMessageCount = 2, updatedAt = 1L)
        )
        // 原文 3..40（38 条 × 19 ≈ 725 > 649）→ 只丢 4 条：条数、token 双双低于门槛
        val history = (1L..40L).map { msg(it, asciiContent(it, 60)) }

        f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            scope = scope
        )

        assertEquals(0, f.mergeSummarizer.calls.size)
        val state = f.rolling.loadState(scope)
        assertEquals(2L, state!!.coveredUpToMessageId) // 水位线不动
    }

    // ---------- build：无持久化状态 → 首启同步摘要 + prewritten 落 KV（不二次 LLM） ----------

    @Test
    fun `first launch generates one-shot summary and persists via prewrittenText`() = runBlocking {
        val f = newFixture(aiSummaryResponse = "首启摘要文本")
        // 40 条 × (4+50+3) token ≈ 2283 > 679 → 保尾部 11 条、丢最旧 29 条；一次性摘要注入
        val history = (1L..40L).map { msg(it, asciiContent(it, 200)) }

        val result = f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            scope = scope
        )

        assertEquals(1, f.aiSummarizer.calls.size)
        // prewrittenText 直接落库，不调 mergeSummarizer（避免同一批消息摘要两次）
        assertEquals(0, f.mergeSummarizer.calls.size)
        val state = f.rolling.loadState(scope)
        assertNotNull("首启应建立水位线", state)
        assertEquals("首启摘要文本", state!!.summaryText)
        assertEquals(29L, state.coveredUpToMessageId)
        assertEquals(29, state.coveredMessageCount)

        // 输出包含一次性摘要注入与保留的尾部原文
        val all = result.joinToString("\n") { it.content.orEmpty() }
        assertTrue(all.contains("早期对话摘要"))
        assertTrue(all.contains("首启摘要文本"))
        assertTrue(all.contains("[m40]"))
        assertFalse(all.contains("[m1]"))
    }

    // ---------- build：占位 scope 不落 KV ----------

    @Test
    fun `placeholder scope never persists rolling state`() = runBlocking {
        val f = newFixture()
        val history = (1L..40L).map { msg(it, asciiContent(it, 300)) }

        f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            // 默认占位 scope = Single(-1) → 视同无持久化，不落 KV
            scope = ConversationScope.Single(-1)
        )

        assertEquals(0, f.mergeSummarizer.calls.size)
        assertNull(f.rolling.loadState(ConversationScope.Single(-1)))
    }

    // ---------- build：陈旧防护（会话重置后旧 KV 作废） ----------

    @Test
    fun `stale summary cleared when oldest id exceeds watermark plus gap`() = runBlocking {
        val f = newFixture()
        seedState(
            f.dao, key,
            RollingSummaryState(summaryText = "旧摘要", coveredUpToMessageId = 5L, coveredMessageCount = 5, updatedAt = 1L)
        )
        // ROLLING_STALE_GAP=600：oldest=606 > 5+600=605 → 判定会话已重置
        val history = (606L..608L).map { msg(it, asciiContent(it, 12)) }

        val result = f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            scope = scope
        )

        // 旧 KV 已清除
        assertNull(f.rolling.loadState(scope))
        // 无滚动摘要注入，原文全量保留
        val all = result.joinToString("\n") { it.content.orEmpty() }
        assertFalse(all.contains("对话进展摘要"))
        assertTrue(all.contains("[m606]"))
        assertTrue(all.contains("[m608]"))
    }

    @Test
    fun `summary retained when oldest id within stale gap boundary`() = runBlocking {
        val f = newFixture()
        seedState(
            f.dao, key,
            RollingSummaryState(summaryText = "旧摘要", coveredUpToMessageId = 5L, coveredMessageCount = 5, updatedAt = 1L)
        )
        // oldest=605 == 5+600 → 严格大于才重置 → 不触发陈旧
        val history = (605L..607L).map { msg(it, asciiContent(it, 12)) }

        val result = f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = buildConfig(),
            scope = scope
        )

        assertNotNull(f.rolling.loadState(scope))
        assertTrue(result.any { it.content.orEmpty().contains("对话进展摘要") })
    }

    // ---------- build：预算顺序（system → summary → history → turn → memory → lastUser） ----------

    @Test
    fun `build assembles layers in designed order`() = runBlocking {
        val f = newFixture()
        seedState(
            f.dao, key,
            RollingSummaryState(summaryText = "滚动摘要正文", coveredUpToMessageId = 0L, coveredMessageCount = 3, updatedAt = 1L)
        )
        val history = (1L..3L).map { msg(it, asciiContent(it, 12)) }

        val result = f.manager.build(
            history = history,
            systemPrompt = "SYS",
            memoryContext = "记忆内容甲",
            lastUserMessage = "hi",
            config = buildConfig(),
            turnContext = "本轮上下文提示",
            scope = scope
        )

        assertEquals("system", result[0].role)
        assertEquals("SYS", result[0].content)
        assertTrue(result[1].content!!.contains("对话进展摘要"))
        val turnIdx = result.indexOfFirst { it.content == "本轮上下文提示" }
        val memoryIdx = result.indexOfFirst { it.content == "记忆内容甲" }
        val lastHistoryIdx = result.indexOfFirst { it.content == "[m3]" + "x".repeat(8) }
        assertTrue("turnContext 未找到", turnIdx >= 0)
        assertTrue("memory 未找到", memoryIdx >= 0)
        assertTrue("history 未找到", lastHistoryIdx >= 0)
        assertTrue("turnContext 应在 history 之后注入", turnIdx > lastHistoryIdx)
        assertTrue("memory 应在 turnContext 之后注入", memoryIdx > turnIdx)
        assertEquals("user", result.last().role)
        assertEquals("hi", result.last().content)
    }

    // ---------- build：过小预算回退 ----------

    @Test
    fun `tiny budget falls back to minimal messages`() = runBlocking {
        val f = newFixture()
        // totalBudget = 8192 - 8000 - 512 = -320 ≤ 0 → 最小消息集
        val cfg = AutoContextManager.ContextConfig(model = "gpt-4", maxOutputTokens = 8000, safetyMargin = 512)

        val result = f.manager.build(
            history = (1L..3L).map { msg(it, asciiContent(it, 12)) },
            systemPrompt = "SYS",
            memoryContext = "",
            lastUserMessage = "hi",
            config = cfg,
            scope = scope
        )

        assertEquals("system", result[0].role)
        assertTrue(result.any { it.role == "user" && it.content == "hi" })
        assertTrue(result.size <= 3)
    }
}
