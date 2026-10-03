package com.yunian.ai.uicommon.plugin

import androidx.compose.runtime.Composable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PluginSettingsSections] 的纯 JVM 单测。
 *
 * 注意：不触碰 `android.util.Log` / `org.json`（纯 JVM 单测里是抛 `Stub!` 的空壳）。
 * 本测试只覆盖注册表的容器语义；[PluginSettingsSection.Content] 是 `@Composable`，
 * 不在这里调用（需要 Compose 运行时，属于 instrumented/Compose 测试的范畴）。
 */
class PluginSettingsSectionsTest {

    /** 可参数化的测试替身。 */
    private class FakeSection(
        override val pluginId: String,
        override val category: PluginSettingsCategory = PluginSettingsCategory.GENERAL,
    ) : PluginSettingsSection {
        @Composable
        override fun Content() = Unit
    }

    @Before
    fun setUp() {
        PluginSettingsSections.clear()
    }

    @After
    fun tearDown() {
        PluginSettingsSections.clear()
    }

    @Test
    fun `empty registry returns empty list and null lookup`() {
        assertEquals(emptyList<PluginSettingsSection>(), PluginSettingsSections.all())
        assertNull(PluginSettingsSections.forPlugin("qqbot"))
    }

    @Test
    fun `register then all contains it`() {
        val section = FakeSection("qqbot", PluginSettingsCategory.CHANNEL)
        PluginSettingsSections.register(section)

        assertEquals(1, PluginSettingsSections.all().size)
        assertSame(section, PluginSettingsSections.all().single())
    }

    @Test
    fun `register same pluginId is idempotent and overwrites`() {
        val first = FakeSection("qqbot", PluginSettingsCategory.CHANNEL)
        val second = FakeSection("qqbot", PluginSettingsCategory.GENERAL)

        PluginSettingsSections.register(first)
        PluginSettingsSections.register(second)

        val all = PluginSettingsSections.all()
        assertEquals("同一 pluginId 只能有一个条目", 1, all.size)
        assertSame("后注册的实例整体替换先前的实例", second, all.single())
        assertSame(second, PluginSettingsSections.forPlugin("qqbot"))
    }

    @Test
    fun `all is sorted by pluginId ascending`() {
        listOf("wechat", "automation.core", "qqbot", "aaa").forEach {
            PluginSettingsSections.register(FakeSection(it))
        }

        assertEquals(
            listOf("aaa", "automation.core", "qqbot", "wechat"),
            PluginSettingsSections.all().map { it.pluginId },
        )
    }

    @Test
    fun `all order is stable regardless of registration order`() {
        val ids = listOf("m", "a", "z", "b")
        ids.forEach { PluginSettingsSections.register(FakeSection(it)) }
        val firstRead = PluginSettingsSections.all().map { it.pluginId }

        PluginSettingsSections.register(FakeSection("c"))
        val secondRead = PluginSettingsSections.all().map { it.pluginId }

        assertEquals(listOf("a", "b", "c", "m", "z"), secondRead)
        assertEquals("插入新条目不得改变既有条目的相对顺序", listOf("a", "b", "m", "z"), firstRead)
        assertEquals(secondRead, PluginSettingsSections.all().map { it.pluginId })
    }

    @Test
    fun `all returns a detached snapshot`() {
        PluginSettingsSections.register(FakeSection("qqbot"))
        val snapshot = PluginSettingsSections.all()

        PluginSettingsSections.register(FakeSection("wechat"))

        assertEquals("快照不受后续注册影响", listOf("qqbot"), snapshot.map { it.pluginId })
        assertEquals(listOf("qqbot", "wechat"), PluginSettingsSections.all().map { it.pluginId })
    }

    @Test
    fun `unregister removes only the target and is idempotent`() {
        PluginSettingsSections.register(FakeSection("qqbot"))
        PluginSettingsSections.register(FakeSection("wechat"))

        PluginSettingsSections.unregister("qqbot")
        PluginSettingsSections.unregister("qqbot") // 重复注销不得抛异常

        assertNull(PluginSettingsSections.forPlugin("qqbot"))
        assertNotNull(PluginSettingsSections.forPlugin("wechat"))
        assertEquals(listOf("wechat"), PluginSettingsSections.all().map { it.pluginId })
    }

    @Test
    fun `category is carried through unchanged`() {
        PluginSettingsSections.register(FakeSection("qqbot", PluginSettingsCategory.CHANNEL))
        PluginSettingsSections.register(FakeSection("automation.core", PluginSettingsCategory.GENERAL))

        val byId = PluginSettingsSections.all().associateBy { it.pluginId }
        assertEquals(PluginSettingsCategory.CHANNEL, byId.getValue("qqbot").category)
        assertEquals(PluginSettingsCategory.GENERAL, byId.getValue("automation.core").category)
    }

    @Test
    fun `pluginId is preserved verbatim`() {
        PluginSettingsSections.register(FakeSection("automation.core"))
        assertTrue(PluginSettingsSections.forPlugin("automation.core") != null)
        assertNull("大小写与分隔符必须逐字匹配", PluginSettingsSections.forPlugin("Automation.Core"))
    }

    @Test
    fun `concurrent registration keeps one entry per pluginId`() {
        val threads = (1..8).map { t ->
            Thread {
                repeat(50) { i ->
                    PluginSettingsSections.register(FakeSection("plugin-" + (i % 20)))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val ids = PluginSettingsSections.all().map { it.pluginId }
        assertEquals(20, ids.size)
        assertEquals(ids.sorted(), ids)
    }
}
