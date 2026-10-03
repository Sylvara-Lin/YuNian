package com.yunian.ai.agent

import com.yunian.ai.domain.CapabilityGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapabilityGrantStoreImpl] 契约测试（真实实现 + 内存 KV，纯 JVM）。
 *
 * 全程走**真实**序列化路径：断言不只落在对象上，也落在落到 KV 里的**原始文本**上，
 * 避免「编码写错但自己解得回来」这类自洽 bug。
 *
 * 授权**不含通道维度**（P2-2d）：存储格式为 `companionId|toolName|0或1`。
 */
class CapabilityGrantStoreImplTest {

    private val tapAllowed = CapabilityGrant(companionId = 42L, toolName = "screen_tap", allowed = true)

    // ── fail-closed：没有任何记录 ⇒ 没有任何显式决定 ──

    @Test
    fun `没有任何记录时决定表为空（fail-closed）`() = runBlocking {
        val store = grantStoreOn(FakeAppMetaDao())
        assertEquals(emptyList<CapabilityGrant>(), store.decisions())
        assertTrue(store.decisionsFor(42L).isEmpty())
    }

    // ── 增删决定 ──

    @Test
    fun `decide 后可查到、且落盘为该格式的一行`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        store.decide(tapAllowed)

        assertEquals(mapOf("screen_tap" to true), store.decisionsFor(42L))
        assertEquals(
            "落盘原始文本必须逐字为 companionId|toolName|0或1",
            "42|screen_tap|1",
            dao.rawOf(CapabilityGrantStoreImpl.KEY),
        )
    }

    @Test
    fun `decide(allowed = false) 落盘为 0`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        store.decide(CapabilityGrant(companionId = 42L, toolName = "screen_read", allowed = false))

        assertEquals("42|screen_read|0", dao.rawOf(CapabilityGrantStoreImpl.KEY))
        assertEquals(mapOf("screen_read" to false), store.decisionsFor(42L))
    }

    @Test
    fun `decide 是同键覆盖：改主意不产生重复行`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        store.decide(tapAllowed)
        store.decide(tapAllowed)
        store.decide(tapAllowed.copy(allowed = false))

        assertEquals("同键只留最新一条", "42|screen_tap|0", dao.rawOf(CapabilityGrantStoreImpl.KEY))
        assertEquals(listOf(tapAllowed.copy(allowed = false)), store.decisions())
    }

    @Test
    fun `clear 移除该键、幂等且不影响其它决定`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        val other = CapabilityGrant(companionId = 42L, toolName = "screen_swipe", allowed = true)
        store.decide(tapAllowed)
        store.decide(other)

        store.clear(42L, "screen_tap")
        assertEquals(mapOf("screen_swipe" to true), store.decisionsFor(42L))
        assertEquals("其它工具的决定不受影响", listOf(other), store.decisions())

        store.clear(42L, "screen_tap")
        assertEquals(listOf(other), store.decisions())
    }

    @Test
    fun `clear 只清当前作用域的键，通配记录不受影响`() = runBlocking {
        val store = grantStoreOn(FakeAppMetaDao())
        store.decide(CapabilityGrant(null, "screen_tap", allowed = true))
        store.decide(CapabilityGrant(42L, "screen_tap", allowed = false))

        store.clear(42L, "screen_tap")
        assertEquals(listOf(CapabilityGrant(null, "screen_tap", allowed = true)), store.decisions())
        assertEquals(
            "清除本伴侣的覆盖后，该伴侣回到通配的允许",
            mapOf("screen_tap" to true),
            store.decisionsFor(42L),
        )
    }

    @Test
    fun `持久化：同一底层 KV 上新建实例仍能读到`() = runBlocking {
        val dao = FakeAppMetaDao()
        grantStoreOn(dao).decide(tapAllowed)
        val reopened = grantStoreOn(dao)
        assertEquals(mapOf("screen_tap" to true), reopened.decisionsFor(42L))
    }

    // ── 解析失败：丢弃该行，绝不抛异常 ──

    @Test
    fun `KV 内容整体损坏时按无决定处理且不抛异常`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(CapabilityGrantStoreImpl.KEY, "\u0000\u0001这不是授权表\r\n也不像\u0002")
        val log = RecordingCapabilityGrantStoreLog()
        val store = grantStoreOn(dao, log)

        assertEquals(emptyList<CapabilityGrant>(), store.decisions())
        assertTrue(store.decisionsFor(42L).isEmpty())
        assertTrue("坏行必须记告警（不静默丢数据）", log.warnings.isNotEmpty())
        assertTrue(
            "告警必须带丢弃条数便于定位",
            log.warnings.all { it.contains("dropped 2 malformed line(s)") },
        )
        assertTrue(log.errors.isEmpty())
    }

    @Test
    fun `部分行损坏时好行照常生效、坏行被丢弃并计数`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(
            CapabilityGrantStoreImpl.KEY,
            listOf("42|screen_tap|1", "GARBAGE", "|bad|1", "*|automation_create|1").joinToString("\n"),
        )
        val log = RecordingCapabilityGrantStoreLog()
        val store = grantStoreOn(dao, log)

        // 通配行对每个伴侣都生效，因此两份结果里都有它；伴侣 42 另有自己的一条。
        assertEquals(mapOf("screen_tap" to true, "automation_create" to true), store.decisionsFor(42L))
        assertEquals(mapOf("automation_create" to true), store.decisionsFor(999L))
        assertTrue("每次读取各记一条告警（2 次查询 = 2 条）", log.warnings.size == 2)
        assertTrue(
            "告警必须带丢弃条数便于定位",
            log.warnings.all { it.contains("dropped 2 malformed line(s)") },
        )
    }

    @Test
    fun `旧的三段式行（含通道维度）一律按坏行丢弃 ⇒ 等同无决定`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(
            CapabilityGrantStoreImpl.KEY,
            listOf("42|qqbot|screen_tap", "*|wechat|automation_create").joinToString("\n"),
        )
        val log = RecordingCapabilityGrantStoreLog()
        val store = grantStoreOn(dao, log)

        assertEquals(emptyList<CapabilityGrant>(), store.decisions())
        assertTrue(store.decisionsFor(42L).isEmpty())
        assertTrue(
            "旧格式必须被计数（可观测），而不是静默消失",
            log.warnings.any { it.contains("dropped 2 malformed line(s)") },
        )
    }

    @Test
    fun `第三段不是 0 或 1 的行被丢弃`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(
            CapabilityGrantStoreImpl.KEY,
            listOf("42|screen_tap|2", "42|screen_swipe|true", "42|screen_read|1").joinToString("\n"),
        )
        val store = grantStoreOn(dao)

        assertEquals(
            "只接受 0 与 1 两个字面量",
            listOf(CapabilityGrant(42L, "screen_read", allowed = true)),
            store.decisions(),
        )
    }

    @Test
    fun `同一键重复出现时后者覆盖前者（与 decide 的同键覆盖语义一致）`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(CapabilityGrantStoreImpl.KEY, listOf("42|screen_tap|1", "42|screen_tap|0").joinToString("\n"))
        val store = grantStoreOn(dao)

        assertEquals(listOf(CapabilityGrant(42L, "screen_tap", allowed = false)), store.decisions())
        assertEquals(mapOf("screen_tap" to false), store.decisionsFor(42L))
    }

    @Test
    fun `损坏内容下仍可继续写入：坏行被丢弃后覆盖为干净内容`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(CapabilityGrantStoreImpl.KEY, "GARBAGE\n42|screen_tap|1")
        val store = grantStoreOn(dao)
        store.decide(CapabilityGrant(42L, "screen_swipe", allowed = true))
        assertEquals(
            "写回时只保留能解析的记录 + 新记录",
            "42|screen_tap|1\n42|screen_swipe|1",
            dao.rawOf(CapabilityGrantStoreImpl.KEY),
        )
    }

    // ── 伴侣隔离 + 通配 ──

    @Test
    fun `伴侣隔离：为伴侣 42 决定不影响伴侣 43`() = runBlocking {
        val store = grantStoreOn(FakeAppMetaDao())
        store.decide(tapAllowed)
        assertEquals(mapOf("screen_tap" to true), store.decisionsFor(42L))
        assertTrue(store.decisionsFor(43L).isEmpty())
    }

    @Test
    fun `通配对所有伴侣生效且落盘为星号`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)
        store.decide(CapabilityGrant(companionId = null, toolName = "screen_tap", allowed = true))

        assertEquals(mapOf("screen_tap" to true), store.decisionsFor(1L))
        assertEquals(mapOf("screen_tap" to true), store.decisionsFor(99999L))
        assertEquals("通配在磁盘上编码为 *", "*|screen_tap|1", dao.rawOf(CapabilityGrantStoreImpl.KEY))
    }

    @Test
    fun `通配与具体伴侣决定合并返回，具体伴侣优先`() = runBlocking {
        val store = grantStoreOn(FakeAppMetaDao())
        store.decide(CapabilityGrant(null, "screen_tap", allowed = true))
        store.decide(CapabilityGrant(null, "screen_swipe", allowed = true))
        store.decide(CapabilityGrant(42L, "screen_swipe", allowed = false))

        assertEquals(
            "通配铺底，伴侣 42 的显式禁止覆盖它",
            mapOf("screen_tap" to true, "screen_swipe" to false),
            store.decisionsFor(42L),
        )
        assertEquals(
            "其它伴侣只吃通配",
            mapOf("screen_tap" to true, "screen_swipe" to true),
            store.decisionsFor(43L),
        )
    }

    @Test
    fun `decisions 是原始表：通配与具体伴侣两条都在，不做合并`() = runBlocking {
        val store = grantStoreOn(FakeAppMetaDao())
        store.decide(CapabilityGrant(null, "screen_tap", allowed = true))
        store.decide(CapabilityGrant(42L, "screen_tap", allowed = false))

        assertEquals(
            "原始表必须两条都在（UI 的「全部伴侣」视角要直接读它）",
            listOf(
                CapabilityGrant(null, "screen_tap", allowed = true),
                CapabilityGrant(42L, "screen_tap", allowed = false),
            ),
            store.decisions(),
        )
    }

    // ── 读写异常 ──

    @Test
    fun `读取抛异常时按无决定处理、记错误日志且不向上抛`() = runBlocking {
        val dao = FakeAppMetaDao(failGet = IllegalStateException("db closed"))
        val log = RecordingCapabilityGrantStoreLog()
        val store = grantStoreOn(dao, log)

        assertEquals(emptyList<CapabilityGrant>(), store.decisions())
        assertTrue(store.decisionsFor(42L).isEmpty())
        assertEquals("每次读取各记一条错误日志（2 次查询 = 2 条）", 2, log.errors.size)
        assertTrue(log.errors.all { it.second?.message == "db closed" })
    }

    @Test
    fun `读取被取消时 CancellationException 必须向上传播`() = runBlocking {
        val dao = FakeAppMetaDao(failGet = CancellationException("scope cancelled"))
        val store = grantStoreOn(dao)
        var thrown: Throwable? = null
        try {
            store.decisions()
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown)
        assertTrue(thrown is CancellationException)
        assertEquals("scope cancelled", thrown?.message)
    }

    @Test
    fun `写入失败时向上抛出（不静默吞）`() = runBlocking {
        val dao = FakeAppMetaDao(failPut = IllegalStateException("disk full"))
        val log = RecordingCapabilityGrantStoreLog()
        val store = grantStoreOn(dao, log)

        var thrown: Throwable? = null
        try {
            store.decide(tapAllowed)
        } catch (e: Throwable) {
            thrown = e
        }
        assertEquals("disk full", thrown?.message)
        assertEquals(1, log.errors.size)
    }

    @Test
    fun `工具名为空白或含分隔符的决定不落盘（宁可不写也不写脏数据）`() = runBlocking {
        val dao = FakeAppMetaDao()
        val store = grantStoreOn(dao)

        store.decide(CapabilityGrant(42L, "   ", allowed = true))
        assertEquals("空白工具名不落盘", "", dao.rawOf(CapabilityGrantStoreImpl.KEY))
        assertTrue(store.decisions().isEmpty())

        store.decide(CapabilityGrant(42L, "a|b", allowed = true))
        assertTrue(store.decisions().isEmpty())

        store.decide(tapAllowed)
        assertEquals("合法决定照常写入", "42|screen_tap|1", dao.rawOf(CapabilityGrantStoreImpl.KEY))
    }

    @Test
    fun `空文本等同没有任何决定`() = runBlocking {
        val dao = FakeAppMetaDao()
        dao.putRaw(CapabilityGrantStoreImpl.KEY, "")
        val log = RecordingCapabilityGrantStoreLog()
        val store = grantStoreOn(dao, log)

        assertEquals(emptyList<CapabilityGrant>(), store.decisions())
        assertTrue("空文本不是坏行，不该记告警", log.warnings.isEmpty())
        assertNull(dao.rawOf(CapabilityGrantStoreImpl.KEY)?.takeIf { it.isNotEmpty() })
    }
}
