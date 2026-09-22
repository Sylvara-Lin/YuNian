package com.yunian.ai.domain.wechat

enum class WeChatContentKind(val wireType: Int) {
    TEXT(1),
    IMAGE(2),
    VOICE(3),
    FILE(4),
    VIDEO(5),
    UNKNOWN(-1);

    companion object {
        fun fromWireType(type: Int?): WeChatContentKind =
            entries.firstOrNull { it.wireType == type } ?: UNKNOWN
    }
}

enum class AppContentType(val wireName: String) {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    VOICE("voice"),
    FILE("file"),

    REASONING("reasoning");

    companion object {
        fun fromWireName(name: String?): AppContentType? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

enum class WeChatMessageDirection {

    INBOUND,

    OUTBOUND,
}

enum class WeChatDeliveryStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    CANCELLED,
}

enum class WeChatConnectionState {
    DISCONNECTED,
    LOGGING_IN,
    CONNECTED,
    DEGRADED,
    ERROR,
}

enum class WeChatInboundAction {
    IGNORE,
    NOTIFY_ONLY,
    ENQUEUE_DIALOGUE,
}

enum class WeChatFailureReason(val wireName: String) {
    POLL_TIMEOUT("poll_timeout"),
    POLL_CONNECTION("poll_connection"),
    POLL_AUTH("poll_auth"),
    POLL_UNKNOWN("poll_unknown"),
    OUTBOX_SEND("outbox_send"),
    OUTBOX_DEAD("outbox_dead"),
    MAPPING_MISSING("mapping_missing"),
    MAPPING_INVALID("mapping_invalid"),
    DIALOGUE_FAILED("dialogue_failed"),
    TRANSPORT_REBUILD("transport_rebuild");

    companion object {
        fun fromPollMessage(message: String?): WeChatFailureReason {
            val msg = message.orEmpty()
            return when {
                // iLink 官方协议 errcode=-14：会话过期，需要重新扫码登录
                msg.contains("errcode=-14") || msg.contains("会话已过期") -> POLL_AUTH
                msg.contains("timeout", ignoreCase = true) -> POLL_TIMEOUT
                msg.contains("connection", ignoreCase = true) ||
                    msg.contains("unable to resolve", ignoreCase = true) ||
                    msg.contains("failed to connect", ignoreCase = true) -> POLL_CONNECTION
                msg.contains("401") ||
                    msg.contains("403") ||
                    msg.contains("unauthorized", ignoreCase = true) ||
                    msg.contains("token", ignoreCase = true) && msg.contains("invalid", ignoreCase = true) -> POLL_AUTH
                else -> POLL_UNKNOWN
            }
        }
    }
}
