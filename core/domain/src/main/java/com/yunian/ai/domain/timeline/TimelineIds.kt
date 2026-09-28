package com.yunian.ai.domain.timeline

@JvmInline
value class TurnId(val value: String) {
    init {
        require(value.isNotBlank()) { "TurnId must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class EventId(val value: Long) {
    init {
        require(value > 0L) { "EventId must be positive" }
    }

    override fun toString(): String = value.toString()
}
