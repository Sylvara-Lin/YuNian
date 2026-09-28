package com.yunian.ai.wechat

import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatMediaRef
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.wechat.ilink.IlinkAccount
import com.yunian.ai.wechat.ilink.IlinkSessionStore
import com.yunian.ai.wechat.outbox.WeChatOutboxCoordinator
import com.yunian.ai.wechat.transport.WeChatTransportPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WeChatOutboxCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val coordinator = WeChatOutboxCoordinator(
        dao = FakeOutboxDao(),
        transport = object : WeChatTransportPort {
            override suspend fun sendText(toUserId: String, text: String, contextToken: String?) =
                Result.success(Unit)

            override suspend fun sendImage(
                toUserId: String,
                imageBytes: ByteArray,
                fileName: String,
                description: String?,
                contextToken: String?,
            ) = Result.success(Unit)
        },
    )

    @Test
    fun buildEntities_simpleTextSegments() {
        val entities = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 9L, text = "你好。世界！"),
            wechatUserId = "u1",
            rootId = "r1",
            createdAtMs = 1000L,
        )
        assertEquals(2, entities.size)
        assertEquals("r1#0", entities[0].id)
        assertEquals("r1#1", entities[1].id)
        assertEquals(WeChatContentKind.TEXT.wireType, entities[0].kind)
        assertEquals(0, entities[0].segmentIndex)
        assertEquals(2, entities[0].segmentCount)
        assertEquals(9L, entities[0].companionId)
        assertEquals("PENDING", entities[0].status)
    }

    @Test
    fun buildEntities_imageSingle() {
        val entities = coordinator.buildEntities(
            request = WeChatOutboundRequest(
                companionId = 1L,
                media = WeChatMediaRef(kind = WeChatContentKind.IMAGE, localPath = "/a.png"),
            ),
            wechatUserId = "u2",
            rootId = "img",
            createdAtMs = 1L,
        )
        assertEquals(1, entities.size)
        assertEquals(WeChatContentKind.IMAGE.wireType, entities.single().kind)
        assertEquals("/a.png", entities.single().mediaLocalPath)
    }

    @Test
    fun buildEntities_blankEmpty() {
        val entities = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "  "),
            wechatUserId = "u3",
            rootId = "e",
            createdAtMs = 1L,
        )
        assertTrue(entities.isEmpty())
    }

    @Test
    fun drain_resolvesLatestContextTokenFromSessionStore() = runBlocking {
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "hello", contextToken = "stale-token"),
            wechatUserId = "user-1",
            rootId = "secure",
            createdAtMs = 1L,
        )
        val dao = FakeOutboxDao(ready)
        var sentToken: String? = null
        val secureCoordinator = WeChatOutboxCoordinator(
            dao = dao,
            transport = object : WeChatTransportPort {
                override suspend fun sendText(toUserId: String, text: String, contextToken: String?): Result<Unit> {
                    sentToken = contextToken
                    return Result.success(Unit)
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            sessionStore = FakeSessionStore("latest-token"),
            segmentGapMs = 0L,
        )

        assertEquals(1, secureCoordinator.drain())
        assertEquals("latest-token", sentToken)
        assertEquals("SENT", dao.lastStatus)
    }

    @Test
    fun drain_sendsEveryTextSegmentInOrder() = runBlocking {
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "第一句。第二句！第三句？"),
            wechatUserId = "user-1",
            rootId = "multi",
            createdAtMs = 1L,
        )
        val sentTexts = mutableListOf<String>()
        val multiSegmentCoordinator = WeChatOutboxCoordinator(
            dao = FakeOutboxDao(ready),
            transport = object : WeChatTransportPort {
                override suspend fun sendText(toUserId: String, text: String, contextToken: String?): Result<Unit> {
                    sentTexts += text
                    return Result.success(Unit)
                }

                override suspend fun sendTextSegments(
                    toUserId: String,
                    segments: List<String>,
                    contextToken: String?,
                ): Result<Unit> {
                    error("batch transport must not be used")
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            segmentGapMs = 0L,
        )

        assertEquals(3, multiSegmentCoordinator.drain(limit = 1))
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), sentTexts)
    }

    @Test
    fun cleanupManagedCache_deletesOnlyExpiredUnreferencedFiles() {
        val cacheDir = temporaryFolder.newFolder("stickers")
        val referenced = File(cacheDir, "referenced.png").apply { writeText("keep") }
        val expired = File(cacheDir, "expired.png").apply { writeText("delete") }
        val recent = File(cacheDir, "recent.png").apply { writeText("keep") }
        referenced.setLastModified(100L)
        expired.setLastModified(100L)
        recent.setLastModified(2_000L)
        val cacheCoordinator = WeChatOutboxCoordinator(
            dao = FakeOutboxDao(),
            transport = SuccessfulTransport,
            managedMediaCacheDir = cacheDir,
        )

        val deleted = cacheCoordinator.cleanupManagedCache(
            referencedPaths = setOf(referenced.absolutePath),
            cutoffMs = 1_000L,
        )

        assertEquals(1, deleted)
        assertTrue(referenced.exists())
        assertTrue(recent.exists())
        assertTrue(!expired.exists())
    }

    @Test
    fun drain_failsFastWithoutRetryWhenContextTokenMissing() = runBlocking {
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "hello"),
            wechatUserId = "user-1",
            rootId = "no-token",
            createdAtMs = 1L,
        )
        val dao = FakeOutboxDao(ready)
        val sendAttempts = mutableListOf<String?>()
        val guardedCoordinator = WeChatOutboxCoordinator(
            dao = dao,
            transport = object : WeChatTransportPort {
                override suspend fun sendText(toUserId: String, text: String, contextToken: String?): Result<Unit> {
                    sendAttempts += contextToken
                    return Result.success(Unit)
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            sessionStore = FakeSessionStore(contextToken = null),
        )

        assertEquals(0, guardedCoordinator.drain())
        assertTrue(sendAttempts.isEmpty())
        assertEquals("FAILED", dao.lastAttemptStatus)
        assertTrue(dao.lastAttemptError!!.contains("context_token 缺失"))
    }

    @Test
    fun drain_failsFastWithoutRetryWhenContextTokenExpired() = runBlocking {
        val now = 10_000_000_000L
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "hello"),
            wechatUserId = "user-1",
            rootId = "expired-token",
            createdAtMs = 1L,
        )
        val dao = FakeOutboxDao(ready)
        val sendAttempts = mutableListOf<String?>()
        val guardedCoordinator = WeChatOutboxCoordinator(
            dao = dao,
            transport = object : WeChatTransportPort {
                override suspend fun sendText(toUserId: String, text: String, contextToken: String?): Result<Unit> {
                    sendAttempts += contextToken
                    return Result.success(Unit)
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            sessionStore = FakeSessionStore(
                contextToken = "stale-token",
                savedAtMs = now - (WeChatOutboxCoordinator.CONTEXT_TOKEN_MAX_AGE_MS + 1),
            ),
            nowMs = { now },
        )

        assertEquals(0, guardedCoordinator.drain())
        assertTrue(sendAttempts.isEmpty())
        assertEquals("FAILED", dao.lastAttemptStatus)
        assertTrue(dao.lastAttemptError!!.contains("context_token 已过期"))
    }

    @Test
    fun drain_sendsWhenContextTokenFresh() = runBlocking {
        val now = 10_000_000_000L
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "hello"),
            wechatUserId = "user-1",
            rootId = "fresh-token",
            createdAtMs = 1L,
        )
        val dao = FakeOutboxDao(ready)
        val freshCoordinator = WeChatOutboxCoordinator(
            dao = dao,
            transport = SuccessfulTransport,
            sessionStore = FakeSessionStore(contextToken = "fresh-token", savedAtMs = now),
            nowMs = { now },
        )

        assertEquals(1, freshCoordinator.drain())
        assertEquals("SENT", dao.lastStatus)
    }

    @Test
    fun drain_deadLettersImmediatelyWhenSessionExpired() = runBlocking {
        val now = 10_000_000_000L
        val ready = coordinator.buildEntities(
            request = WeChatOutboundRequest(companionId = 1L, text = "hello", contextToken = "tok"),
            wechatUserId = "user-1",
            rootId = "session-expired",
            createdAtMs = 1L,
        )
        val dao = FakeOutboxDao(ready)
        var sendAttempts = 0
        val expiredCoordinator = WeChatOutboxCoordinator(
            dao = dao,
            transport = object : WeChatTransportPort {
                override suspend fun sendText(
                    toUserId: String,
                    text: String,
                    contextToken: String?,
                ): Result<Unit> {
                    sendAttempts++
                    throw com.yunian.ai.wechat.ilink.IlinkSessionExpiredException()
                }

                override suspend fun sendImage(
                    toUserId: String,
                    imageBytes: ByteArray,
                    fileName: String,
                    description: String?,
                    contextToken: String?,
                ) = Result.success(Unit)
            },
            sessionStore = FakeSessionStore(contextToken = "tok", savedAtMs = now),
            nowMs = { now },
        )

        // 首次发送即判死：不进入重试退避，errcode=-14 保留在 lastError
        assertEquals(0, expiredCoordinator.drain())
        assertEquals(1, sendAttempts)
        assertEquals("FAILED", dao.lastAttemptStatus)
        assertTrue(dao.lastAttemptError!!.contains("outbox_dead"))
        assertTrue(dao.lastAttemptError!!.contains("errcode=-14"))
    }
}

private object SuccessfulTransport : WeChatTransportPort {
    override suspend fun sendText(toUserId: String, text: String, contextToken: String?) =
        Result.success(Unit)

    override suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String?,
        contextToken: String?,
    ) = Result.success(Unit)
}

private class FakeOutboxDao(
    private val ready: List<com.yunian.ai.database.model.WeChatOutboxEntity> = emptyList(),
) : com.yunian.ai.database.dao.WeChatOutboxDao {
    var lastStatus: String? = null
    var lastAttemptStatus: String? = null
    var lastAttemptError: String? = null

    override suspend fun insertAll(items: List<com.yunian.ai.database.model.WeChatOutboxEntity>) = Unit
    override suspend fun insert(item: com.yunian.ai.database.model.WeChatOutboxEntity) = Unit
    override suspend fun listReady(nowMs: Long, limit: Int) = ready.take(limit)
    override suspend fun listOpenByRootId(rootId: String) = ready.filter { it.rootId == rootId }
    override suspend fun updateAttempt(
        id: String,
        status: String,
        retryCount: Int,
        nextAttemptAtMs: Long,
        lastError: String?,
        updatedAtMs: Long,
    ) {
        lastAttemptStatus = status
        lastAttemptError = lastError
    }
    override suspend fun updateStatus(id: String, status: String, lastError: String?, updatedAtMs: Long) {
        lastStatus = status
    }
    override suspend fun countOpen(): Int = 0
    override suspend fun countByStatus(status: String): Int = 0
    override suspend fun listRecentFailed(limit: Int) =
        emptyList<com.yunian.ai.database.model.WeChatOutboxEntity>()
    override suspend fun deleteSentBefore(cutoffMs: Long): Int = 0
    override suspend fun deleteDeadBefore(maxRetry: Int, cutoffMs: Long): Int = 0
    override suspend fun listMediaLocalPaths(): List<String> = emptyList()
}

private class FakeSessionStore(
    private val contextToken: String?,
    private val savedAtMs: Long? = null,
) : IlinkSessionStore {
    override suspend fun getSessionAccount() = IlinkAccount("bot", "bot-id", "user-id", accountId = "account-1")
    override suspend fun saveSessionAccount(account: IlinkAccount) = Unit
    override suspend fun clearSessionAccount() = Unit
    override suspend fun getCursor() = ""
    override suspend fun saveCursor(cursor: String) = Unit
    override suspend fun getContextToken(accountId: String, userId: String) = contextToken
    override suspend fun getContextTokenSavedAt(accountId: String, userId: String) = savedAtMs
    override suspend fun saveContextToken(accountId: String, userId: String, token: String) = Unit
    override suspend fun getContextTokens(accountId: String) = emptyMap<String, String>()
}
