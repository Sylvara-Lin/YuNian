package com.yunian.ai.feature.automation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.feature.automation.AutomationExecutor
import com.yunian.ai.feature.automation.AutomationScheduler
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationStore
import com.yunian.ai.feature.automation.data.AutomationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationListViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AutomationStore(application)

    val automations: StateFlow<List<Automation>> = store.flow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val events: SharedFlow<String> = _events.asSharedFlow()

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setEnabled(id, enabled)
            val target = store.list().firstOrNull { it.id == id } ?: return@launch
            if (enabled) {
                AutomationScheduler.reschedule(getApplication(), target)
            } else {
                AutomationScheduler.cancel(getApplication(), id)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            AutomationScheduler.cancel(getApplication(), id)
        }
    }

    fun fire(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = store.list().firstOrNull { it.id == id } ?: return@launch
            val result = AutomationExecutor(getApplication()).execute(target)
            val ok = result is com.yunian.ai.feature.automation.WorkflowEngine.Result.Success
            _events.tryEmit(if (ok) "「${target.title}」执行成功" else "「${target.title}」执行失败")
        }
    }

    fun update(automation: Automation) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val refreshed = if (automation.type != AutomationType.ONCE) {
                val next = AutomationSchedulePolicy.nextTriggerAtMillis(automation, now)
                if (next != null) automation.copy(triggerAtMillis = next) else automation
            } else {
                automation
            }
            store.upsert(refreshed)
            if (refreshed.enabled) {
                AutomationScheduler.reschedule(getApplication(), refreshed)
            }
        }
    }
}
