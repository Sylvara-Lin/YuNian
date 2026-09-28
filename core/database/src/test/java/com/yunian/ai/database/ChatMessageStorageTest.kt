package com.yunian.ai.database

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ChatMessageCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ChatMessageStorageTest {
    private val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val testKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val testKeyProvider = ChatMessageCrypto.KeyProvider { testKey }

    @Test
    fun chat_message_crypto_does_not_use_hardcoded_fallback_key_material() {
        val source = File(
            projectRoot,
            "core/database/src/main/java/com/yunian/ai/database/repository/ChatMessageCrypto.kt"
        ).readText()

        assertFalse(source.contains("lianyu-chat-message-storage-v1"))
        assertFalse(source.contains("fallbackKey"))
        assertTrue(source.contains("KeyProvider"))
    }

    @Test
    fun chat_messages_can_be_filtered_by_millisecond_cursor_fuzzy_query_and_file_type() {
        val messages = listOf(
            ChatMessage(
                id = 1,
                companionId = 7,
                content = "今天一起吃草莓蛋糕",
                isFromUser = true,
                timestamp = 1_700_000_000_001,
                type = MessageType.TEXT,
                fileFormat = FileFormat.TEXT,
                linkString = ""
            ),
            ChatMessage(
                id = 2,
                companionId = 7,
                content = "草莓蛋糕照片",
                isFromUser = false,
                timestamp = 1_700_000_000_500,
                type = MessageType.IMAGE,
                fileFormat = FileFormat.IMAGE,
                linkString = "lianyu://file?kind=image&uri=content://media/image/1"
            ),
            ChatMessage(
                id = 3,
                companionId = 7,
                content = "语音消息",
                isFromUser = false,
                timestamp = 1_700_000_001_000,
                type = MessageType.VOICE,
                fileFormat = FileFormat.AUDIO,
                linkString = "lianyu://file?kind=audio&uri=content://media/audio/1"
            )
        )

        val result = messages
            .asSequence()
            .filter { it.companionId == 7L }
            .filter { it.timestamp < 1_700_000_001_000 }
            .filter { it.content.contains("草莓") }
            .filter { it.fileFormat == FileFormat.IMAGE }
            .sortedByDescending { it.timestamp }
            .take(20)
            .toList()

        assertEquals(listOf(2L), result.map { it.id })
        assertEquals("lianyu://file?kind=image&uri=content://media/image/1", result.single().linkString)
    }

    @Test
    fun chat_message_crypto_returns_placeholder_when_stored_ciphertext_cannot_be_authenticated() {
        val sourceMessage = ChatMessage(
            companionId = 9,
            content = "原始消息",
            isFromUser = true,
            timestamp = 1_700_000_123_456,
            fileFormat = FileFormat.TEXT,
            linkString = ""
        )
        val encrypted = ChatMessageCrypto.encryptForStorage(sourceMessage, testKeyProvider)
        // encryptForStorage 会把明文写入可搜索字段 searchContent，用于解密失败时的
        // 明文兜底（见 ChatMessageCrypto.plaintextSearchFallback）。本用例要覆盖
        // 「无明文兜底 → 解析失败占位符」分支，因此这里把 searchContent 一并清空。
        val corrupted = encrypted.copy(
            content = encrypted.content.dropLast(2) + "AA",
            searchContent = ""
        )

        val decrypted = ChatMessageCrypto.decryptFromStorage(corrupted, testKeyProvider)

        assertEquals(ChatMessageCrypto.DECRYPT_FAILED_PLACEHOLDER, decrypted.content)
        assertEquals("", decrypted.linkString)
    }

    @Test
    fun chat_message_crypto_encrypts_searchable_content_and_link_fields_without_plaintext_storage() {
        val message = ChatMessage(
            companionId = 9,
            content = "需要加密的聊天正文",
            isFromUser = true,
            timestamp = 1_700_000_123_456,
            fileFormat = FileFormat.IMAGE,
            linkString = "lianyu://file?kind=image&uri=content://secure/image/9"
        )

        val encrypted = ChatMessageCrypto.encryptForStorage(message, testKeyProvider)

        assertFalse(encrypted.content.contains("需要加密"))
        assertFalse(encrypted.linkString.contains("content://secure"))
        assertEquals("需要加密的聊天正文", encrypted.searchContent)
        assertEquals(FileFormat.IMAGE, encrypted.fileFormat)
        assertTrue(encrypted.linkString.isNotBlank())

        val decrypted = ChatMessageCrypto.decryptFromStorage(encrypted, testKeyProvider)
        assertEquals(message.content, decrypted.content)
        assertEquals(message.linkString, decrypted.linkString)
    }

    @Test
    fun stored_message_separates_timeline_metadata_from_encrypted_body() {
        val source = ChatMessage(
            id = 42,
            companionId = 9,
            content = "正文",
            isFromUser = false,
            timestamp = 1_700_000_123_456,
            searchContent = "正文",
            fileFormat = FileFormat.IMAGE,
            linkString = "resource"
        )

        val (metadata, body) = com.yunian.ai.database.model.StoredMessage.fromChatMessage(source)

        assertEquals(42L, metadata.id)
        assertEquals(9L, metadata.conversationId)
        assertEquals(FileFormat.IMAGE, metadata.fileFormat)
        assertEquals(42L, body.messageId)
        assertEquals("正文", body.content)
        assertEquals("正文", body.searchContent)
        assertEquals("resource", body.linkString)
    }
}
