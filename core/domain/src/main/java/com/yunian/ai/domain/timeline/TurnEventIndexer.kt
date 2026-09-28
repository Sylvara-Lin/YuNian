package com.yunian.ai.domain.timeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TurnEventIndexer {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun next(turnId: TurnId): Int {
        val counter = counters.getOrPut(turnId.value) { AtomicInteger(0) }
        return counter.getAndIncrement()
    }

    fun peek(turnId: TurnId): Int =
        counters[turnId.value]?.get() ?: 0

    fun reset(turnId: TurnId) {
        counters.remove(turnId.value)
    }

    fun clear() {
        counters.clear()
    }
}
