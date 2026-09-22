package com.yunian.ai.common

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

object AppForegroundTracker {

    @Volatile
    var isInForeground: Boolean = false

    private val initialized = AtomicBoolean(false)

    fun init() {
        if (!initialized.compareAndSet(false, true)) return

        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        isInForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
            }
        })
    }
}
