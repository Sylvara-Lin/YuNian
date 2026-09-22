package com.yunian.ai.network

import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object TypingIndicator {

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private var typingStartTime = AtomicLong(0L)
    private const val TYPING_TIMEOUT_MS = 60000L

    fun startTyping(source: String = "unknown") {
        _isTyping.value = true
        typingStartTime.set(System.currentTimeMillis())
        SecureLog.typing("START", "Typing started from $source")
    }

    fun stopTyping(source: String = "unknown") {
        if (_isTyping.value) {
            val elapsed = System.currentTimeMillis() - typingStartTime.get()
            _isTyping.value = false
            SecureLog.typing("STOP", "Typing stopped from $source, duration=${elapsed}ms")
        }
    }

    fun checkTimeout(): Boolean {
        if (!_isTyping.value) return false
        val elapsed = System.currentTimeMillis() - typingStartTime.get()
        if (elapsed > TYPING_TIMEOUT_MS) {
            SecureLog.typing("TIMEOUT", "Typing timed out after ${elapsed}ms")
            _isTyping.value = false
            return true
        }
        return false
    }

    fun getTypingDuration(): Long {
        return if (_isTyping.value) System.currentTimeMillis() - typingStartTime.get() else 0
    }

    fun createTypingParameter(enabled: Boolean = true): Map<String, Any> {
        return mapOf(
            "stream" to enabled,
            "stream_options" to mapOf("include_usage" to true)
        )
    }
}

class ChatTypingState {
    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    private val _typingText = MutableStateFlow("")
    val typingText: StateFlow<String> = _typingText.asStateFlow()

    private var typingJob: kotlinx.coroutines.Job? = null
    private val textBuffer = StringBuffer()
    private var lastFlushTime = 0L
    private val flushIntervalMs = 80L

    fun startTyping() {
        _isTyping.value = true
        _typingText.value = ""
        textBuffer.setLength(0)
        lastFlushTime = System.currentTimeMillis()
    }

    fun appendText(text: String) {
        textBuffer.append(text)
        val now = System.currentTimeMillis()
        if (now - lastFlushTime >= flushIntervalMs || textBuffer.length >= 15) {
            _typingText.value += textBuffer.toString()
            textBuffer.setLength(0)
            lastFlushTime = now
        }
    }

    fun stopTyping() {

        textBuffer.setLength(0)
        _typingText.value = ""
        _isTyping.value = false
        typingJob?.cancel()
        typingJob = null
    }

    fun setTypingJob(job: kotlinx.coroutines.Job) {
        typingJob = job
    }
}
