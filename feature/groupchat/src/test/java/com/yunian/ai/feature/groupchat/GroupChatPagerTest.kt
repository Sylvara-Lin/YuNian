package com.yunian.ai.feature.groupchat

import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.GroupMessage
import com.yunian.ai.database.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GroupChatPager 纯逻辑单测（JVM 直测，无 Android / 无 Room / 无协程依赖）。
 *
 * 覆盖外提自 GroupChatViewModel 的分页/正文状态机全部纯函数：
 * 元数据投影、三种 hasMore 口径、实时流合并、上翻页合并、
 * 正文播种/投影、待加载筛选、成功与失败状态迁移。
 */
class GroupChatPagerTest {

    private val conversationId = 42L

    private fun groupMessage(
        id: Long,
        companionId: Long = -1L,
        content: String = "内容$id",
        timestamp: Long = id * 10
    ) = GroupMessage(
        id = id,
        groupId = conversationId,
        companionId = companionId,
        content = content,
        timestamp = timestamp
    )

    private fun message(id: Long, timestamp: Long = id * 10) = Message(
        id = id,
        conversationId = conversationId,
        conversationType = "group",
        isFromUser = false,
        senderId = 1L,
        timestamp = timestamp
    )

    // ── 元数据投影 ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMessage maps user message`() {
        val source = groupMessage(id = 1, companionId = -1L, timestamp = 111L)
        val meta = GroupChatPager.toMetadataMessage(source, conversationId)

        assertEquals(1L, meta.id)
        assertEquals(conversationId, meta.conversationId)
        assertEquals("group", meta.conversationType)
        assertTrue(meta.isFromUser)
        assertEquals(-1L, meta.senderId)
        assertEquals(111L, meta.timestamp)
        assertEquals(FileFormat.TEXT, meta.fileFormat)
    }

    @Test
    fun `toMetadataMessage maps companion message`() {
        val source = groupMessage(id = 2, companionId = 7L, timestamp = 222L)
        val meta = GroupChatPager.toMetadataMessage(source, conversationId)

        assertTrue(!meta.isFromUser)
        assertEquals(7L, meta.senderId)
        assertEquals(222L, meta.timestamp)
    }

    @Test
    fun `toMetadataMessage preserves non-text file format`() {
        val source = GroupMessage(
            id = 3,
            groupId = conversationId,
            companionId = -1L,
            content = "[图片] x",
            fileFormat = FileFormat.IMAGE
        )
        assertEquals(FileFormat.IMAGE, GroupChatPager.toMetadataMessage(source, conversationId).fileFormat)
    }

    // ── hasMore 三种口径（语义刻意不同，回归保护） ──────────────────────────

    @Test
    fun `initialHasMore is true only when cache is at least a full page`() {
        assertTrue(GroupChatPager.initialHasMore(cachedSize = 30, limit = 30))
        assertTrue(GroupChatPager.initialHasMore(cachedSize = 31, limit = 30))
        assertTrue(!GroupChatPager.initialHasMore(cachedSize = 29, limit = 30))
    }

    @Test
    fun `hasMoreAfterSeed is true only when visible is below total`() {
        assertTrue(GroupChatPager.hasMoreAfterSeed(visibleCount = 9, totalCount = 10))
        assertTrue(!GroupChatPager.hasMoreAfterSeed(visibleCount = 10, totalCount = 10))
        assertTrue(!GroupChatPager.hasMoreAfterSeed(visibleCount = 11, totalCount = 10))
    }

    @Test
    fun `hasMoreAfterLoadMore is true only when the page was exactly full`() {
        assertTrue(GroupChatPager.hasMoreAfterLoadMore(loadedCount = 30, limit = 30))
        assertTrue(!GroupChatPager.hasMoreAfterLoadMore(loadedCount = 29, limit = 30))
        assertTrue(!GroupChatPager.hasMoreAfterLoadMore(loadedCount = 31, limit = 30))
    }

    // ── 实时流合并 ─────────────────────────────────────────────────────────

    @Test
    fun `mergeIncomingMetadata drops covered entries and keeps older ones sorted`() {
        val current = listOf(
            message(id = 5, timestamp = 50),
            message(id = 6, timestamp = 60),
            message(id = 9, timestamp = 90)
        )
        val recent = listOf(message(id = 6, timestamp = 60), message(id = 7, timestamp = 70))

        val merged = GroupChatPager.mergeIncomingMetadata(current, recent)

        // id=6 被新流覆盖 → 不重复；id=5/9 保留；整体按 (timestamp, id) 升序
        assertEquals(listOf(5L, 6L, 7L, 9L), merged.map { it.id })
    }

    @Test
    fun `mergeIncomingMetadata dedupes by id within the incoming batch`() {
        val current = emptyList<Message>()
        val recent = listOf(message(id = 1, timestamp = 10), message(id = 1, timestamp = 10))

        assertEquals(listOf(1L), GroupChatPager.mergeIncomingMetadata(current, recent).map { it.id })
    }

    @Test
    fun `mergeIncomingMetadata breaks timestamp ties by id`() {
        val current = listOf(message(id = 2, timestamp = 10))
        val recent = listOf(message(id = 1, timestamp = 10))

        val merged = GroupChatPager.mergeIncomingMetadata(current, recent)
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    // ── 上翻页合并 ─────────────────────────────────────────────────────────

    @Test
    fun `prependOlder puts the older page first in chronological order and dedupes`() {
        // getMetadataBefore 按时间倒序返回（新→旧），prependOlder 负责反转成时间正序后接在头部
        val current = listOf(message(id = 8, timestamp = 80), message(id = 9, timestamp = 90))
        val older = listOf(message(id = 7, timestamp = 70), message(id = 6, timestamp = 60))

        val merged = GroupChatPager.prependOlder(current, older)
        assertEquals(listOf(6L, 7L, 8L, 9L), merged.map { it.id })
    }

    @Test
    fun `prependOlder drops ids already present in current`() {
        val current = listOf(message(id = 7, timestamp = 70))
        val older = listOf(message(id = 7, timestamp = 70), message(id = 6, timestamp = 60))

        assertEquals(listOf(6L, 7L), GroupChatPager.prependOlder(current, older).map { it.id })
    }

    // ── 正文播种 ───────────────────────────────────────────────────────────

    @Test
    fun `seedBodies fills only entries without a Ready body`() {
        val metadata = listOf(message(1), message(2), message(3))
        val cachedById = mapOf(
            1L to groupMessage(1),
            2L to groupMessage(2)
        )
        val existing = mapOf(1L to MessageBodyState.Ready(groupMessage(1)) as MessageBodyState<GroupMessage>)

        val seeded = GroupChatPager.seedBodies(metadata, cachedById, existing)

        // 1 已 Ready → 不覆盖；2 有缓存 → 播种；3 无缓存 → 不动
        assertTrue(seeded[1L] is MessageBodyState.Ready)
        assertTrue(seeded[2L] is MessageBodyState.Ready)
        assertEquals(null, seeded[3L])
        assertEquals(2, seeded.size)
    }

    @Test
    fun `seedBodies returns input untouched for empty metadata or cache`() {
        val bodies = mapOf(1L to MessageBodyState.Loading as MessageBodyState<GroupMessage>)
        assertEquals(bodies, GroupChatPager.seedBodies(emptyList(), mapOf(1L to groupMessage(1)), bodies))
        assertEquals(bodies, GroupChatPager.seedBodies(listOf(message(1)), emptyMap(), bodies))
    }

    @Test
    fun `seedBodies keeps Loading entries when cache has no body for them`() {
        val metadata = listOf(message(1))
        val bodies = mapOf(1L to MessageBodyState.Loading as MessageBodyState<GroupMessage>)
        assertEquals(bodies, GroupChatPager.seedBodies(metadata, emptyMap(), bodies))
    }

    // ── 正文投影 ───────────────────────────────────────────────────────────

    @Test
    fun `publishLoaded projects only Ready bodies in metadata order`() {
        val metadata = listOf(message(1), message(2), message(3))
        val bodies = mapOf(
            1L to MessageBodyState.Ready(groupMessage(1)) as MessageBodyState<GroupMessage>,
            2L to MessageBodyState.Loading,
            3L to MessageBodyState.Error("正文不存在")
        )

        val loaded = GroupChatPager.publishLoaded(metadata, bodies)
        assertEquals(listOf(1L), loaded.map { it.id })
    }

    // ── 待加载筛选 ─────────────────────────────────────────────────────────

    @Test
    fun `pendingBodyIds keeps only visible entries with missing or failed bodies`() {
        val metadata = listOf(message(1), message(2), message(3), message(4))
        val bodies = mapOf(
            1L to MessageBodyState.Ready(groupMessage(1)) as MessageBodyState<GroupMessage>,
            2L to MessageBodyState.Loading,
            3L to MessageBodyState.Error("x")
        )
        val visible = setOf(1L, 3L, 4L)

        // 1 已 Ready → 跳过；2 不可见 → 跳过；3 Error → 重试；4 缺失 → 加载
        assertEquals(listOf(3L, 4L), GroupChatPager.pendingBodyIds(visible, metadata, bodies).map { it.id })
    }

    // ── 状态迁移 ───────────────────────────────────────────────────────────

    @Test
    fun `applyLoadedResults marks hits Ready and misses Error`() {
        val pending = listOf(message(1), message(2))
        val loaded = mapOf(1L to groupMessage(1))

        val next = GroupChatPager.applyLoadedResults(emptyMap(), pending, loaded)

        assertTrue(next[1L] is MessageBodyState.Ready)
        val missed = next[2L]
        assertTrue(missed is MessageBodyState.Error)
        assertEquals("正文不存在", (missed as MessageBodyState.Error).message)
    }

    @Test
    fun `applyLoadFailure marks every pending entry as Error with the original reason`() {
        val pending = listOf(message(1), message(2))

        val withReason = GroupChatPager.applyLoadFailure(emptyMap(), pending, "网络超时")
        withReason.values.forEach {
            assertEquals("网络超时", (it as MessageBodyState.Error).message)
        }

        val withoutReason = GroupChatPager.applyLoadFailure(emptyMap(), pending, null)
        withoutReason.values.forEach {
            assertEquals("正文加载失败", (it as MessageBodyState.Error).message)
        }
    }

    @Test
    fun `applyLoadFailure preserves unrelated existing bodies`() {
        val existing = mapOf(9L to MessageBodyState.Ready(groupMessage(9)) as MessageBodyState<GroupMessage>)
        val next = GroupChatPager.applyLoadFailure(existing, listOf(message(1)), "boom")

        assertTrue(next[9L] is MessageBodyState.Ready)
        assertTrue(next[1L] is MessageBodyState.Error)
        assertEquals(2, next.size)
    }
}
