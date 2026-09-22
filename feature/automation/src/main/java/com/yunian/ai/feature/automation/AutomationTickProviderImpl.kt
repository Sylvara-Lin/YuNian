package com.yunian.ai.feature.automation

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.AutomationTickProvider
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationStore
import com.yunian.ai.feature.automation.data.AutomationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AutomationTickProviderImpl(private val context: Context) : AutomationTickProvider {

    private val ticking = AtomicBoolean(false)

    override suspend fun onTick() = withContext(Dispatchers.IO) {
        if (!ticking.compareAndSet(false, true)) {
            SecureLog.w("AutomationTick", "previous tick still running, skip this round")
            return@withContext
        }
        try {
            runCatching {
                val store = AutomationStore(context)
                val now = System.currentTimeMillis()
                val all = store.list()

                all.filter { it.enabled && it.type != AutomationType.ONCE && it.triggerAtMillis <= 0 }.forEach { a ->
                    val next = AutomationSchedulePolicy.nextTriggerAtMillis(a, now)
                    if (next != null) {
                        store.upsert(a.copy(triggerAtMillis = next))
                        AutomationScheduler.reschedule(context, a.copy(triggerAtMillis = next))
                    }
                }
                val due = all.filter { automation ->
                    AutomationSchedulePolicy.shouldFire(automation, now)
                }
                if (due.isEmpty()) return@withContext
                SecureLog.i("AutomationTick", "due automations: ${due.map { it.title }}")
                due.forEach { automation ->
                    runCatching { AutomationScheduler.fireDue(context, automation) }
                        .onFailure { SecureLog.e("AutomationTick", "fire '${automation.title}' failed", it) }
                }
            }.onFailure { SecureLog.e("AutomationTick", "onTick failed", it) }
        } finally {
            ticking.set(false)
        }
    }
}
