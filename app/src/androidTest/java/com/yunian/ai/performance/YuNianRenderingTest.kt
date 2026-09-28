package com.yunian.ai.performance

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunian.ai.MainActivity
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.PerformanceTrace
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.cache.MessageCache
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.StoredMessage
import com.yunian.ai.database.repository.ChatMessageCrypto
import com.yunian.ai.database.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class YuNianRenderingTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val database by lazy { AppDatabase.getDatabase(targetContext) }
    private val chatRepository by lazy {
        ChatRepository(database.messageDao(), database.conversationSummaryDao(), database)
    }
    private val runId = UUID.randomUUID().toString().take(8)
    private val companionName = "PERF_FRIEND_$runId"
    private val messageSentinel = "PERF_MESSAGE_$runId"
    private var companionId = 0L

    private val fixtureRule = object : ExternalResource() {
        override fun before() {
            runBlocking {
                targetContext.getSharedPreferences("agreement_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("agreement_accepted", true).commit()
                targetContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .edit().putString("selected_role", CompanionRole.GIRLFRIEND.name).commit()

                companionId = database.companionDao().insertCompanion(
                    CompanionEntity(
                        name = companionName,
                        personality = "performance fixture",
                        updatedAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
                    )
                )
                val baseTimestamp = System.currentTimeMillis()
                database.messageDao().insertStoredMessages(
                    (0 until MESSAGE_COUNT).map { index ->
                        val content = "$messageSentinel-$index"
                        StoredMessage.fromChatMessage(
                            ChatMessageCrypto.encryptForStorage(ChatMessage(
                                companionId = companionId,
                                content = content,
                                isFromUser = index % 2 == 0,
                                timestamp = baseTimestamp + index,
                                searchContent = content
                            ))
                        )
                    }
                )
            }
        }

        override fun after() {
            runBlocking {
                if (companionId == 0L) return@runBlocking
                database.messageDao().deleteAllMessagesForConversation(companionId, "chat")
                database.companionDao().getCompanionById(companionId)?.let {
                    database.companionDao().deleteCompanion(it)
                }
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixtureRule).around(composeRule)

    @Test
    fun measureStartupContactsChatAndMessages() {
        composeRule.onNodeWithTag("app_main_ready").assertExists()
        val startupMs = nanosToMs(PerformanceTrace.startupNanos())

        composeRule.onNode(hasText("通讯录") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("contacts_friend_list_ready").assertExists()
        val contactMatcher = hasText(companionName) and
            hasAnyAncestor(hasTestTag("contacts_friend_list_ready"))
        composeRule.onNode(contactMatcher).assertExists()
        val contactsMs = nanosToMs(PerformanceTrace.contactsNanos())

        runBlocking { chatRepository.getRecentMessagesSync(companionId, MESSAGE_COUNT) }
        composeRule.onNode(contactMatcher).performClick()
        composeRule.onNodeWithTag("chat_shell_ready").assertExists()
        composeRule.onNodeWithTag("chat_messages_ready").assertExists()
        composeRule.onNodeWithText("$messageSentinel-${MESSAGE_COUNT - 1}").assertExists()
        val chatShellMs = nanosToMs(PerformanceTrace.chatShellNanos())
        val messagesMs = nanosToMs(PerformanceTrace.chatMessagesNanos())
        val chatTotalMs = nanosToMs(PerformanceTrace.chatTotalNanos())

        val results = Bundle().apply {
            putString("performance.startup_ms", startupMs.toString())
            putString("performance.contacts_ms", contactsMs.toString())
            putString("performance.chat_shell_ms", chatShellMs.toString())
            putString("performance.messages_ms", messagesMs.toString())
            putString("performance.chat_to_messages_ms", chatTotalMs.toString())
        }
        Log.i(LOG_TAG, results.keySet().sorted().joinToString { "$it=${results.getString(it)}" })
        instrumentation.sendStatus(2, results)

        assertWithinBudget("startup", startupMs, STARTUP_BUDGET_MS)
        assertWithinBudget("contacts", contactsMs, CONTACTS_BUDGET_MS)
        assertWithinBudget("chat shell", chatShellMs, CHAT_SHELL_BUDGET_MS)
        assertWithinBudget("messages", messagesMs, MESSAGES_BUDGET_MS)
        assertWithinBudget("chat total", chatTotalMs, CHAT_TOTAL_BUDGET_MS)
    }

    @Test
    fun measureMessageDatabaseAndCacheLoading() = runBlocking {
        val metadata = chatRepository.getRecentMetadata(companionId, MESSAGE_COUNT)
        MessageCache.evictChat(companionId)

        val databaseStart = SystemClock.elapsedRealtimeNanos()
        val databaseMessages = chatRepository.loadMessages(metadata)
        val databaseMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - databaseStart)

        chatRepository.warmCache(companionId, databaseMessages.values.toList())
        val cacheStart = SystemClock.elapsedRealtimeNanos()
        val cachedMessages = chatRepository.loadMessages(metadata)
        val cacheMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - cacheStart)

        assertTrue("database load returned ${databaseMessages.size} messages", databaseMessages.size == MESSAGE_COUNT)
        assertTrue("cache load returned ${cachedMessages.size} messages", cachedMessages.size == MESSAGE_COUNT)
        assertTrue(
            "decrypted database messages did not contain the sentinel",
            databaseMessages.values.any { it.content == "$messageSentinel-${MESSAGE_COUNT - 1}" }
        )

        val results = Bundle().apply {
            putString("performance.message_database_body_decrypt_ms", databaseMs.toString())
            putString("performance.message_cache_hit_ms", cacheMs.toString())
        }
        Log.i(LOG_TAG, results.keySet().sorted().joinToString { "$it=${results.getString(it)}" })
        instrumentation.sendStatus(2, results)
    }

    @Test
    fun cacheHitReusesImmutableSnapshotAndIndexUntilWrite() {
        val firstMessage = ChatMessage(
            id = 1,
            companionId = companionId,
            content = "first",
            isFromUser = true,
            timestamp = 1
        )
        MessageCache.putChatMessages(companionId, listOf(firstMessage))

        val firstSnapshot = MessageCache.getChatMessages(companionId)
        val firstIndex = MessageCache.getChatMessagesById(companionId)

        assertSame(firstSnapshot, MessageCache.getChatMessages(companionId))
        assertSame(firstIndex, MessageCache.getChatMessagesById(companionId))
        assertSame(firstMessage, firstIndex?.get(1))

        MessageCache.appendChatMessage(companionId, firstMessage.copy(id = 2, content = "second"))

        val updatedSnapshot = MessageCache.getChatMessages(companionId)
        assertNotSame(firstSnapshot, updatedSnapshot)
        assertNotSame(firstIndex, MessageCache.getChatMessagesById(companionId))
        assertEquals(listOf(1L), firstSnapshot?.map { it.id })
        assertEquals(listOf(1L, 2L), updatedSnapshot?.map { it.id })
    }

    @Test
    fun measureReleaseShellAndSecurityInitialization() {
        val preferences = targetContext.getSharedPreferences(
            PerformanceTrace.RELEASE_METRICS_PREFS,
            Context.MODE_PRIVATE
        )
        val metricNames = listOf(
            "shell_anti_hook",
            "shell_native_init",
            "shell_recovery_oat",
            "shell_memory_guard",
            "shell_preflight",
            "shell_total",
            "security_tink",
            "security_white_box",
            "security_attestation",
            "security_signature",
            "security_kms",
            "security_integrity",
            "security_finalize",
            "security_total"
        )
        val results = Bundle()
        metricNames.forEach { name ->
            val nanos = preferences.getLong(name, 0L)
            assertTrue("Release metric $name was not recorded", nanos > 0L)
            results.putString("performance.${name}_ms", nanosToMs(nanos).toString())
        }
        instrumentation.sendStatus(2, results)
    }

    @Test
    fun measureJavaThinShellInitialization() {
        val preferences = targetContext.getSharedPreferences(
            PerformanceTrace.RELEASE_METRICS_PREFS,
            Context.MODE_PRIVATE
        )
        val metricNames = listOf(
            "java_shell_anti_hook",
            "java_shell_certificate",
            "java_shell_vmp_payload",
            "java_shell_dex_load",
            "java_shell_recovery",
            "java_shell_memory_guard",
            "java_shell_real_app_create",
            "java_shell_real_app_attach",
            "java_shell_attach_total",
            "java_shell_business_on_create",
            "java_shell_to_business_ready"
        )
        val results = Bundle()
        metricNames.forEach { name ->
            val nanos = preferences.getLong(name, 0L)
            assertTrue("Java thin-shell metric $name was not recorded", nanos > 0L)
            results.putString("performance.${name}_ms", nanosToMs(nanos).toString())
        }
        instrumentation.sendStatus(2, results)
    }

    private fun assertWithinBudget(metric: String, actualMs: Double, budgetMs: Double) {
        assertTrue("$metric took $actualMs ms; budget is $budgetMs ms", actualMs in 0.001..budgetMs)
    }

    private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

    private companion object {
        const val LOG_TAG = "YuNianPerformance"
        const val MESSAGE_COUNT = 40
        const val STARTUP_BUDGET_MS = 500.0
        const val CONTACTS_BUDGET_MS = 150.0
        const val CHAT_SHELL_BUDGET_MS = 550.0
        const val MESSAGES_BUDGET_MS = 100.0
        const val CHAT_TOTAL_BUDGET_MS = 600.0
    }
}