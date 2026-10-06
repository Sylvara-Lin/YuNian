package com.yunian.ai

import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.model.AppMetaEntity
import com.yunian.ai.database.repository.AppMetaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginEnablementStoreImpl] 的纯 JVM 单测（无 Robolectric）。
 *
 * 不碰 `android.util.Log`（日志走 [RecordingPluginEnablementStoreLog] 替身）、
 * 不碰 Room 运行时（[AppMetaDao] 是普通 interface，用内存 Map 实现它即可驱动**真实的**
 * [AppMetaStore] 与真实编解码路径）——与 `core:agent` 的 `CapabilityGrantTestFixtures` 同一手法。
 *
 * 重点覆盖契约的**硬要求**：读失败 / 首启 / 内容损坏 ⇒ 空集（= 全部启用）。
 */
class PluginEnablementStoreImplTest {

    private class FakeDao(
        private val map: MutableMap<String, String> = mutableMapOf(),
        private val failGet: Throwable? = null,
        private val failPut: Throwable? = null,
    ) : AppMetaDao {

        private val flowStates = mutableMapOf<String, MutableStateFlow<String?>>()

        var putCount: Int = 0
            private set

        override suspend fun get(key: String): String? {
            failGet?.let { throw it }
            return map[key]
        }

        override suspend fun put(entity: AppMetaEntity) {
            failPut?.let { throw it }
            putCount++
            map[entity.key] = entity.value
            flowStates[entity.key]?.value = entity.value
        }

        override suspend fun remove(key: String) {
            map.remove(key)
            flowStates[key]?.value = null
        }

        override suspend fun getAll(): List<AppMetaEntity> =
            map.map { (key, value) -> AppMetaEntity(key = key, value = value) }

        override fun getFlow(key: String): Flow<String?> =
            flowStates.getOrPut(key) { MutableStateFlow(map[key]) }

        /** 绕过 store 直接塞原始内容（模拟历史脏数据 / KV 损坏）。 */
        fun putRaw(key: String, value: String) {
            map[key] = value
        }

        /** 直读原始内容（断言落盘格式用）。 */
        fun rawOf(key: String): String? = map[key]
    }

    private class RecordingPluginEnablementStoreLog : PluginEnablementStoreLog {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()

        override fun w(tag: String, message: String) {
            warnings += "$tag|$message"
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            errors += "$tag|$message" to throwable
        }
    }

    private fun storeOn(dao: AppMetaDao, log: PluginEnablementStoreLog) =
        PluginEnablementStoreImpl(AppMetaStore(dao), log)

    // ── fail-safe：读不到 = 全部启用 ──────────────────────────────────────────

    @Test
    fun readFailureReturnsEmptySetSoEveryPluginStaysEnabled() = runBlocking {
        val log = RecordingPluginEnablementStoreLog()
        val store = storeOn(FakeDao(failGet = IllegalStateException("db closed")), log)

        assertEquals(emptySet<String>(), store.disabledIds())
        assertEquals(1, log.errors.size)
        assertTrue(log.errors.single().second is IllegalStateException)
    }

    @Test
    fun firstLaunchWithNoStoredValueReturnsEmptySet() = runBlocking {
        val store = storeOn(FakeDao(), RecordingPluginEnablementStoreLog())

        assertEquals(emptySet<String>(), store.disabledIds())
    }

    @Test
    fun blankStoredValueReturnsEmptySet() = runBlocking {
        val dao = FakeDao()
        dao.putRaw(PluginEnablementStoreImpl.KEY, "   \n\n  ")
        val store = storeOn(dao, RecordingPluginEnablementStoreLog())

        assertEquals(emptySet<String>(), store.disabledIds())
    }

    @Test
    fun corruptLinesAreDroppedAndGoodLinesSurvive() = runBlocking {
        val log = RecordingPluginEnablementStoreLog()
        val dao = FakeDao()
        dao.putRaw(PluginEnablementStoreImpl.KEY, "wechat.channel\n\tbad id here\n\nqqbot.channel\n")
        val store = storeOn(dao, log)

        assertEquals(setOf("wechat.channel", "qqbot.channel"), store.disabledIds())
        assertEquals(1, log.warnings.size)
    }

    @Test
    fun fullyCorruptContentStillYieldsEmptySetNotFailure() = runBlocking {
        val dao = FakeDao()
        dao.putRaw(PluginEnablementStoreImpl.KEY, "not a plugin id\n\u0000\n")
        val store = storeOn(dao, RecordingPluginEnablementStoreLog())

        assertEquals(emptySet<String>(), store.disabledIds())
    }

    // ── 持久化语义 ────────────────────────────────────────────────────────────

    @Test
    fun disablingAPluginSurvivesARestart() = runBlocking {
        val dao = FakeDao()
        storeOn(dao, RecordingPluginEnablementStoreLog()).setEnabled("wechat.channel", false)

        // 新实例 + 新 store = 模拟进程重启后重新读盘
        val afterRestart = storeOn(dao, RecordingPluginEnablementStoreLog())
        assertEquals(setOf("wechat.channel"), afterRestart.disabledIds())
        assertEquals("wechat.channel", dao.rawOf(PluginEnablementStoreImpl.KEY))
    }

    @Test
    fun enablingAPluginRemovesItFromTheDisabledSet() = runBlocking {
        val dao = FakeDao()
        val store = storeOn(dao, RecordingPluginEnablementStoreLog())
        store.setEnabled("wechat.channel", false)
        store.setEnabled("qqbot.channel", false)

        store.setEnabled("wechat.channel", true)

        assertEquals(setOf("qqbot.channel"), storeOn(dao, RecordingPluginEnablementStoreLog()).disabledIds())
    }

    @Test
    fun repeatedIdenticalToggleDoesNotWriteAgain() = runBlocking {
        val dao = FakeDao()
        val store = storeOn(dao, RecordingPluginEnablementStoreLog())
        store.setEnabled("wechat.channel", false)
        val writesAfterFirst = dao.putCount

        store.setEnabled("wechat.channel", false)

        assertEquals(writesAfterFirst, dao.putCount)
    }

    @Test
    fun writeFailureIsSwallowedAndReportedNotThrown() = runBlocking {
        val log = RecordingPluginEnablementStoreLog()
        val store = storeOn(FakeDao(failPut = IllegalStateException("disk full")), log)

        // 不抛异常（契约：写失败由实现方自行记录并吞掉）
        store.setEnabled("wechat.channel", false)

        assertEquals(1, log.errors.size)
        assertEquals(emptySet<String>(), store.disabledIds())
    }

    @Test
    fun malformedPluginIdIsNeverWrittenToKv() = runBlocking {
        val log = RecordingPluginEnablementStoreLog()
        val dao = FakeDao()
        val store = storeOn(dao, log)

        store.setEnabled("bad id", false)
        store.setEnabled("", false)

        assertEquals(0, dao.putCount)
        assertNull(dao.rawOf(PluginEnablementStoreImpl.KEY))
        assertEquals(2, log.warnings.size)
    }

    @Test
    fun codecEncodesSortedOneIdPerLineAndRoundTrips() {
        val encoded = PluginEnablementCodec.encode(setOf("qqbot.channel", "wechat.channel"))

        assertEquals("qqbot.channel\nwechat.channel", encoded)
        val decoded = PluginEnablementCodec.decode(encoded)
        assertEquals(setOf("qqbot.channel", "wechat.channel"), decoded.ids)
        assertEquals(0, decoded.droppedLineCount)
    }

    @Test
    fun codecNeverThrowsOnNullInput() {
        val decoded = PluginEnablementCodec.decode(null)

        assertEquals(emptySet<String>(), decoded.ids)
        assertEquals(0, decoded.droppedLineCount)
        assertFalse(PluginEnablementCodec.isValidId(""))
        assertFalse(PluginEnablementCodec.isValidId("a b"))
        assertTrue(PluginEnablementCodec.isValidId("wechat.channel"))
    }
}
