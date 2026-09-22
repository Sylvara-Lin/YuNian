package com.yunian.ai.feature.automation

import android.content.Context
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AutomationExecutor(private val context: Context) {

    companion object {

        private val locks = ConcurrentHashMap<String, Mutex>()
    }

    suspend fun execute(automation: Automation, idempotentCheck: Boolean = false): WorkflowEngine.Result {
        val mutex = locks.computeIfAbsent(automation.id) { Mutex() }
        return mutex.withLock {

            val latest = runCatching {
                AutomationStore(context).list().firstOrNull { it.id == automation.id }
            }.getOrNull()
            if (latest == null) {
                SecureLog.w("AutomationExecutor", "automation '${automation.id}' not found or deleted, skip")
                return@withLock WorkflowEngine.Result.Success("已删除或不存在")
            }
            if (idempotentCheck && !AutomationSchedulePolicy.shouldFire(latest, System.currentTimeMillis())) {
                SecureLog.i("AutomationExecutor", "skip fired automation '${latest.title}' (idempotent)")
                return@withLock WorkflowEngine.Result.Success("本次触发已执行过")
            }
            doExecute(latest)
        }
    }

    private suspend fun doExecute(automation: Automation): WorkflowEngine.Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val messageWriter = ServiceRegistry.get(MessageWriteCoordinator::class.java)
        val chatRepository = ServiceRegistry.get(ChatRepository::class.java)

        val result = if (automation.isWorkflow && messageWriter != null) {
            val engine = WorkflowEngine(context, messageWriter, chatRepository)
            engine.execute(automation)
        } else {
            runLegacy(automation, messageWriter)
        }

        val success = result is WorkflowEngine.Result.Success
        val baseStats = automation.stats
        val updated = automation.copy(
            stats = baseStats.copy(
                fireCount = baseStats.fireCount + 1,
                successCount = baseStats.successCount + if (success) 1 else 0,
                failCount = baseStats.failCount + if (success) 0 else 1,
                lastFiredAt = now,

                lastScheduledFiredAt = maxOf(
                    baseStats.lastScheduledFiredAt,
                    minOf(now, automation.triggerAtMillis)
                ),
                lastResult = if (success) "success" else "failure",
                lastMessage = (result as? WorkflowEngine.Result.Success)?.message.orEmpty().take(40)
            )
        )
        runCatching { AutomationStore(context).upsert(updated) }
            .onFailure { SecureLog.e("AutomationExecutor", "update stats failed", it) }

        if (!success && result is WorkflowEngine.Result.Failure) {
            SecureLog.e("AutomationExecutor", "automation '${automation.title}' failed: ${result.reason}")
        }
        result
    }

    private suspend fun runLegacy(
        automation: Automation,
        messageWriter: MessageWriteCoordinator?
    ): WorkflowEngine.Result {
        AutomationNotifier.show(context, automation.title, automation.message, automation.companionId)

        val outputSafety = ContentFilter.checkOutputSafety(automation.message)
        if (outputSafety.isSafe) {
            messageWriter?.let { writer ->
                runCatching {
                    writer.enqueueChat(
                        ChatMessage(
                            companionId = automation.companionId,
                            content = automation.message,
                            isFromUser = false
                        )
                    )
                }.onFailure { SecureLog.e("AutomationExecutor", "write chat message failed", it) }
            } ?: SecureLog.w("AutomationExecutor", "MessageWriteCoordinator not registered")
        } else {
            SecureLog.w("AutomationExecutor", "Automation message blocked by safety: ${outputSafety.reason}")
        }
        return WorkflowEngine.Result.Success(automation.message)
    }
}
