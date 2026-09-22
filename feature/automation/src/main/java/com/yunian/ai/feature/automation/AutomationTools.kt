package com.yunian.ai.feature.automation

import android.app.Application
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationStore
import com.yunian.ai.feature.automation.data.AutomationType
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object AutomationTools {

    fun registerAll(store: AutomationStore, app: Application) {
        ToolRegistry.register(CreateAutomationTool(store, app))
        ToolRegistry.register(CreateWorkflowTool(store, app))
        ToolRegistry.register(CancelAutomationTool(store, app))
        ToolRegistry.register(ListAutomationsTool(store))
        ToolRegistry.register(FireAutomationTool(store, app))
    }

    // ── 工具工厂（Cordis 插件装配用；与 registerAll 注册同一批实现） ──

    fun createAutomationTool(store: AutomationStore, app: Application): AiTool =
        CreateAutomationTool(store, app)

    fun createWorkflowTool(store: AutomationStore, app: Application): AiTool =
        CreateWorkflowTool(store, app)

    fun cancelAutomationTool(store: AutomationStore, app: Application): AiTool =
        CancelAutomationTool(store, app)

    fun listAutomationsTool(store: AutomationStore): AiTool = ListAutomationsTool(store)

    fun fireAutomationTool(store: AutomationStore, app: Application): AiTool =
        FireAutomationTool(store, app)

    private class CreateAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_create"
        override val description = "创建定时自动化任务（如 每天早8点提醒喝水）。" +
            "参数：title 任务名，type=once|daily|weekly，" +
            "once 用 triggerAt(epoch毫秒)，daily/weekly 用 hour+minute（weekly 加 dayOfWeek，1=周一..7=周日），" +
            "message 为到点时伴侣发的自然文案（可选，缺省自动生成）。此操作需用户确认。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "title":{"type":"string","description":"任务名，如 喝水"},
                "companionId":{"type":"integer","description":"从哪个伴侣对话创建"},
                "type":{"type":"string","enum":["once","daily","weekly"]},
                "triggerAt":{"type":"integer","description":"ONCE 触发时间 epoch 毫秒"},
                "hour":{"type":"integer","description":"DAILY/WEEKLY 触发小时 0-23"},
                "minute":{"type":"integer","description":"DAILY/WEEKLY 触发分钟 0-59"},
                "dayOfWeek":{"type":"integer","description":"WEEKLY 周几，1=周一..7=周日"},
                "message":{"type":"string","description":"到点时伴侣发的自然文案"}
            },"required":["title","companionId","type"]}
        """.trimIndent()
        override val requiresConfirmation: Boolean get() = true

        override fun summarizeArguments(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateParams(argumentsJson) ?: return argumentsJson.take(120)
            val whenText = when (p.type) {
                AutomationType.ONCE -> "一次（${formatOnce(p.triggerAtMillis)}）"
                AutomationType.DAILY -> "每天 ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
                AutomationType.WEEKLY -> "每周${weekdayName(p.dayOfWeekCalendar)} ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
            }
            return "自动化「${p.title}」· $whenText"
        }

        override suspend fun execute(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateParams(argumentsJson)
                ?: return """{"error":"参数解析失败，请重试"}"""
            val raw = Automation(
                id = UUID.randomUUID().toString(),
                title = p.title,
                companionId = p.companionId,
                type = p.type,
                triggerAtMillis = p.triggerAtMillis,
                hourOfDay = p.hourOfDay,
                minuteOfHour = p.minuteOfHour,
                dayOfWeek = p.dayOfWeekCalendar,
                message = p.message
            )

            val automation = AutomationSchedulePolicy.normalizedAutomation(raw, System.currentTimeMillis())
            store.upsert(automation)
            AutomationScheduler.reschedule(app, automation)
            return """{"id":"${automation.id}","title":"${p.title}","created":true}"""
        }

        private fun formatOnce(ms: Long): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
                "${cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:${cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')}"
        }

        private fun weekdayName(dayOfWeekCalendar: Int?): String = when (dayOfWeekCalendar) {
            2 -> "一"; 3 -> "二"; 4 -> "三"; 5 -> "四"; 6 -> "五"; 7 -> "六"; 1 -> "日"; else -> "?"
        }
    }

    private class CreateWorkflowTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_create_workflow"
        override val description = "创建可视化工作流（如 吃醋巡检、早安问候），支持多节点流程图。" +
            "参数：title 工作流名称，description 描述，type=once|daily|weekly 触发方式，" +
            "nodes 节点列表（id/type[start/end/action/ai_generate/condition]/title/prompt/outputVar/message/actionType/conditionExpr），" +
            "edges 连线列表（id/source/target/label）。触发时间参数同 automation_create。此操作需用户确认。" +
            "示例：吃醋巡检 daily 20:00，nodes=[{id:start,type:start},{id:gen,type:ai_generate,title:生成吃醋文案,prompt:'用撒娇吃醋语气写一句30字内提醒',outputVar:msg},{id:send,type:action,title:发送,message:'{{msg}}',actionType:companion},{id:end,type:end}]，" +
            "edges=[{id:e1,source:start,target:gen},{id:e2,source:gen,target:send},{id:e3,source:send,target:end}]"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "title":{"type":"string","description":"工作流名称"},
                "description":{"type":"string","description":"工作流描述"},
                "companionId":{"type":"integer","description":"从哪个伴侣对话创建"},
                "type":{"type":"string","enum":["once","daily","weekly"]},
                "triggerAt":{"type":"integer","description":"ONCE 触发时间 epoch 毫秒"},
                "hour":{"type":"integer","description":"DAILY/WEEKLY 触发小时 0-23"},
                "minute":{"type":"integer","description":"DAILY/WEEKLY 触发分钟 0-59"},
                "dayOfWeek":{"type":"integer","description":"WEEKLY 周几，1=周一..7=周日"},
                "nodes":{"type":"array","description":"节点数组","items":{"type":"object","properties":{
                    "id":{"type":"string"},"type":{"type":"string","enum":["start","end","action","ai_generate","condition"]},
                    "title":{"type":"string"},"prompt":{"type":"string"},"outputVar":{"type":"string"},
                    "message":{"type":"string"},"actionType":{"type":"string"},"conditionExpr":{"type":"string"}
                },"required":["id","type"]}},
                "edges":{"type":"array","description":"连线数组","items":{"type":"object","properties":{
                    "id":{"type":"string"},"source":{"type":"string"},"target":{"type":"string"},"label":{"type":"string"}
                },"required":["id","source","target"]}}
            },"required":["title","companionId","type","nodes","edges"]}
        """.trimIndent()
        override val requiresConfirmation: Boolean get() = true

        override fun summarizeArguments(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateWorkflowParams(argumentsJson) ?: return argumentsJson.take(120)
            val whenText = when (p.type) {
                AutomationType.ONCE -> "一次（${formatOnce(p.triggerAtMillis)}）"
                AutomationType.DAILY -> "每天 ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
                AutomationType.WEEKLY -> "每周${weekdayName(p.dayOfWeekCalendar)} ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
            }
            return "工作流「${p.title}」· ${p.nodes.size}节点/${p.edges.size}连线 · $whenText"
        }

        override suspend fun execute(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateWorkflowParams(argumentsJson)
                ?: return """{"error":"参数解析失败，请重试"}"""
            val raw = Automation(
                id = UUID.randomUUID().toString(),
                title = p.title,
                companionId = p.companionId,
                type = p.type,
                triggerAtMillis = p.triggerAtMillis,
                hourOfDay = p.hourOfDay,
                minuteOfHour = p.minuteOfHour,
                dayOfWeek = p.dayOfWeekCalendar,
                message = p.description,
                isWorkflow = true,
                nodes = p.nodes,
                edges = p.edges
            )

            val automation = AutomationSchedulePolicy.normalizedAutomation(raw, System.currentTimeMillis())
            store.upsert(automation)
            AutomationScheduler.reschedule(app, automation)
            return """{"id":"${automation.id}","title":"${p.title}","created":true,"nodes":${p.nodes.size},"edges":${p.edges.size}}"""
        }

        private fun formatOnce(ms: Long): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
                "${cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:${cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')}"
        }

        private fun weekdayName(dayOfWeekCalendar: Int?): String = when (dayOfWeekCalendar) {
            2 -> "一"; 3 -> "二"; 4 -> "三"; 5 -> "四"; 6 -> "五"; 7 -> "六"; 1 -> "日"; else -> "?"
        }
    }

    private class CancelAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_cancel"
        override val description = "取消定时自动化任务。参数 id 为自动化ID；若只给 title 则按名称模糊匹配，唯一命中时取消，多个匹配返回候选列表。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "id":{"type":"string","description":"自动化ID"},
                "title":{"type":"string","description":"任务名（模糊匹配）"}
            }}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(argumentsJson).jsonObject
            }.getOrNull() ?: return """{"error":"参数解析失败"}"""
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val all = store.list()
            if (!id.isNullOrBlank()) {
                val target = all.firstOrNull { it.id == id } ?: return """{"error":"自动化不存在"}"""
                store.delete(id)
                AutomationScheduler.cancel(app, id)
                return """{"cancelled":true,"title":"${target.title}"}"""
            }
            val matches = AutomationToolLogic.matchByTitle(all, title.orEmpty())
            return when {
                matches.isEmpty() -> """{"cancelled":false,"error":"没有找到匹配的自动化"}"""
                matches.size == 1 -> {
                    store.delete(matches[0].id)
                    AutomationScheduler.cancel(app, matches[0].id)
                    """{"cancelled":true,"title":"${matches[0].title}"}"""
                }
                else -> """{"cancelled":false,"candidates":[${matches.joinToString(",") { "\"${it.title}\"" }}]}"""
            }
        }
    }

    private class ListAutomationsTool(
        private val store: AutomationStore
    ) : AiTool {
        override val name = "automation_list"
        override val description = "列出当前全部自动化/工作流任务，返回 id、title、type、触发时间、启用状态、节点/连线数。"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""

        override suspend fun execute(argumentsJson: String): String {
            val list = store.list()
            val sb = StringBuilder("[")
            list.forEachIndexed { i, a ->
                if (i > 0) sb.append(",")
                sb.append("""{"id":"${a.id}","title":"${a.title}","type":"${a.type.name.lowercase()}","enabled":${a.enabled},"isWorkflow":${a.isWorkflow},"nodes":${a.nodes.size},"edges":${a.edges.size}}""")
            }
            sb.append("]")
            return sb.toString()
        }
    }

    private class FireAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_fire"
        override val description =
            "立即触发某个已存在的自动化或工作流并同步返回执行结果。参数 id 为自动化ID；" +
                "或给 title 按名称模糊匹配唯一命中后触发。" +
                "注意：本工具只能立即执行，不能预约未来时间——需要定时请改用 " +
                "automation_create 创建带触发时间的自动化。"

        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "id":{"type":"string","description":"自动化ID"},
                "title":{"type":"string","description":"任务名（模糊匹配）"}
            }}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(argumentsJson).jsonObject
            }.getOrNull() ?: return """{"error":"参数解析失败"}"""
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val all = store.list()
            val target = if (!id.isNullOrBlank()) {
                all.firstOrNull { it.id == id }
            } else {
                AutomationToolLogic.matchByTitle(all, title.orEmpty()).singleOrNull()
            }
                ?: return """{"error":"找不到要触发的自动化"}"""
            val result = AutomationExecutor(app).execute(target)
            val ok = result is WorkflowEngine.Result.Success
            return """{"fired":true,"title":"${target.title}","success":$ok}"""
        }
    }
}

/**
 * 自动化工具插件（Cordis 双层插件模板 · 代码插件，kind = TOOL）。
 *
 * 与 [AutomationTools.registerAll] 的差异：逐工具注册 [PluginContext.effect] 注销副作用，
 * 插件卸载时工具随之清理（「卸载不留鸡毛」语义）。
 * 装配契约：requires = [PluginServices.TOOLS]（宿主预置 ToolRegistry）。
 *
 * ⚠️ 调度层（AutomationScheduler / AutomationFireWorker / AutomationTickProviderImpl）
 * 不在插件范围内 —— 定时器由 Kotlin 侧持有（Rust 无定时器）。
 */
class AutomationPlugin(
    private val store: AutomationStore,
    private val app: Application,
) : LianYuPlugin {

    companion object {
        const val ID = "automation.core"
    }

    override val id: String = ID
    override val name: String = "自动化工具"
    override val requires: Set<String> = setOf(PluginServices.TOOLS)
    override val configSchema: String? = null

    override fun setup(ctx: PluginContext) {
        val registry = ctx.inject<ToolRegistry>(PluginServices.TOOLS)
        val tools = listOf(
            AutomationTools.createAutomationTool(store, app),
            AutomationTools.createWorkflowTool(store, app),
            AutomationTools.cancelAutomationTool(store, app),
            AutomationTools.listAutomationsTool(store),
            AutomationTools.fireAutomationTool(store, app),
        )
        tools.forEach { registry.register(it) }
        tools.forEach { tool ->
            ctx.effect({ registry.unregister(tool.name) }, "unregister:${tool.name}")
        }
    }
}
