package com.yunian.ai.common

object TimeoutBudgets {

    const val SM4_DECRYPT_MS = 50L
    const val BAYESIAN_CLASSIFY_MS = 30L
    const val AC_SCAN_MS = 10L
    const val PROTO_CODEC_MS = 20L
    const val IMAGE_PROCESS_MS = 50L

    const val API_CHAT_MS = 15_000L
    const val API_VISION_MS = 30_000L
    const val API_STREAM_MS = 20_000L

    const val TTS_SYNTH_MS = 30_000L

    const val TTS_SYNTH_PER_CHAR_MS = 1_000L
    const val TTS_SYNTH_MAX_MS = 180_000L

    fun ttsSynthTimeoutMs(textLength: Int): Long =
        (textLength.toLong() * TTS_SYNTH_PER_CHAR_MS)
            .coerceIn(TTS_SYNTH_MS, TTS_SYNTH_MAX_MS)
    const val STT_RECOGNIZE_MS = 15_000L

    const val CHAT_VM_API_TIMEOUT_MS = 30_000L
    const val CHAT_VM_VISION_TIMEOUT_MS = 60_000L
    const val CHAT_VM_SAFETY_CLASSIFY_MS = 30_000L
    const val CHAT_VM_MEMORY_EXTRACT_MS = 5_000L

    const val CHAT_VM_TTS_SYNTH_MS = 30_000L
    const val CHAT_VM_BATCH_WINDOW_MS = 2_500L

    const val MODEL_OUTPUT_VERIFY_MS = 5_000L
    const val API_CONFIG_WAIT_MS = 1_500L
    const val PIPELINE_EXECUTE_MS = 8_000L

    const val HTTP_CONNECT_MS = 10_000L
    const val HTTP_READ_MS = 30_000L
    const val HTTP_WRITE_MS = 10_000L
    const val HTTP_PING_MS = 30_000L

    /**
     * 带工具调用的对话请求的单次总预算。
     *
     * 工具轮次每轮都要重发不断膨胀的工具结果历史，上下文显著大于普通对话，
     * 模型端首字延迟也更长；沿用 [CHAT_VM_API_TIMEOUT_MS]（30s）会让靠后的轮次
     * 频繁抛 SocketTimeoutException，被上层归类成「网络连接超时」（误导用户以为是断网）。
     */
    const val HTTP_TOOL_CALL_TIMEOUT_MS = 90_000L

    const val WECHAT_POLL_TIMEOUT_MS = 15_000L
    const val BROADCAST_GOASYNC_MS = 9_500L

    const val MCP_CONNECT_MS = 15_000L
    const val MCP_READ_MS = 30_000L
    const val MCP_WRITE_MS = 15_000L
    const val MCP_SSE_READ_MS = 30_000L

    const val ROOM_WRITE_MS = 5_000L
    const val ROOM_QUERY_MS = 3_000L
    const val MEMORY_EXTRACT_MS = 5_000L

    const val CONTENT_FILTER_MS = 3_000L
    const val SAFETY_CLASSIFY_MS = 30_000L

    const val AUTOMATION_CONFIRM_TIMEOUT_MS = 60_000L

    const val AUTOMATION_AI_TIMEOUT_MS = 45_000L

    const val AUTOMATION_WORKFLOW_TOTAL_MS = 180_000L

    const val CHANNEL_CAPACITY = 100
    const val MAX_CONCURRENT_API = 3
    const val LAZY_COLUMN_MAX_ITEMS = 200
    const val IMAGE_CACHE_MB = 128
    const val MAX_TTS_TASKS = 3
    const val MEMORY_ALERT_RATIO = 0.85f
    const val MEMORY_RECOVER_RATIO = 0.60f
}
