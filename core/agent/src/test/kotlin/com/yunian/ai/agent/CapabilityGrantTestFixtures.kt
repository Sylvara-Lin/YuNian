package com.yunian.ai.agent

import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.model.AppMetaEntity
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.CapabilityGrant
import com.yunian.ai.domain.CapabilityGrantStore

/**
 * 工具授权单测的共享替身。
 *
 * `AppMetaDao` 是普通 Kotlin interface（Room 注解只为生成实现），因此纯 JVM 单测可以用
 * 内存 Map 实现它，从而驱动**真实的** [CapabilityGrantStoreImpl] 与真实序列化路径
 * （与 `core:network` 的 RollingSummaryManagerTest 同一手法）。
 */

/** 内存版 KV：可注入读 / 写失败，也可直接塞入脏数据模拟内容损坏。 */
internal class FakeAppMetaDao(
    private val map: MutableMap<String, String> = mutableMapOf(),
    private val failGet: Throwable? = null,
    private val failPut: Throwable? = null,
) : AppMetaDao {

    override suspend fun get(key: String): String? {
        failGet?.let { throw it }
        return map[key]
    }

    override suspend fun put(entity: AppMetaEntity) {
        failPut?.let { throw it }
        map[entity.key] = entity.value
    }

    override suspend fun remove(key: String) {
        map.remove(key)
    }

    override suspend fun getAll(): List<AppMetaEntity> =
        map.map { (key, value) -> AppMetaEntity(key = key, value = value) }

    /** 绕过 store 直接塞原始内容（模拟历史脏数据 / KV 损坏）。 */
    fun putRaw(key: String, value: String) {
        map[key] = value
    }

    /** 直读原始内容（断言落盘格式用）。 */
    fun rawOf(key: String): String? = map[key]
}

/** 日志替身：记录 tag|message，避免纯 JVM 单测触碰 `android.util.Log` 空壳。 */
internal class RecordingCapabilityGrantStoreLog : CapabilityGrantStoreLog {
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<Pair<String, Throwable?>>()

    override fun w(tag: String, message: String) {
        warnings += "$tag|$message"
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        errors += "$tag|$message" to throwable
    }
}

/** 用真实 [CapabilityGrantStoreImpl] 包住给定 dao（日志默认走替身）。 */
internal fun grantStoreOn(
    dao: AppMetaDao,
    log: CapabilityGrantStoreLog = RecordingCapabilityGrantStoreLog(),
): CapabilityGrantStoreImpl = CapabilityGrantStoreImpl(AppMetaStore(dao), log)

/**
 * 假工具：只声明 name / toolsets / requiresConfirmation，取值逐条对齐生产声明。
 * 与 `AgentToolCategoryConfirmationTest.FakeTool` 同形（:core:agent 不能反向依赖 feature 模块）。
 */
internal class FakeTool(
    override val name: String,
    override val toolsets: Set<String> = emptySet(),
    override val requiresConfirmation: Boolean = false,
) : AiTool {
    override val description: String = "fake: $name"
    override val parametersJsonSchema: String = """{"type":"object","properties":{}}"""
    override suspend fun execute(argumentsJson: String): String = "{}"
}

/**
 * 生产侧 **6 个**静态 `requiresConfirmation = true` 的工具名（逐条对齐源码）。
 *
 * ⚠️ **这是手工维护的镜像，不是自动枚举。** 架构上不可能自动校验：`core:*` 不得依赖
 * `feature:*`（AGENTS.md），而其中 5 个工具住在 `feature:skills` / `feature:automation`
 * 里，`core:agent` 的测试源集看不到它们。
 * **因此新增一个 `requiresConfirmation = true` 的生产工具时，必须同步这里。**
 *
 * 另一类**动态**来源不在此列表：`feature/mcp/.../McpToolAdapter.kt:21`
 * （`requiresConfirmation = mcpTool.needsApproval`），由 `AgentToolCategoryConfirmationTest`
 * 的「动态声明的 requiresConfirmation（MCP 审批）同样进入门控」用例单独覆盖。
 */
internal val PRODUCTION_CONFIRM_TOOL_NAMES: List<String> = listOf(
    "screen_tap",                  // feature/skills/.../AccessibilityTools.kt:120
    "screen_swipe",                // feature/skills/.../AccessibilityTools.kt:141
    "screen_click_text",           // feature/skills/.../AccessibilityTools.kt:166
    "automation_create",           // feature/automation/.../AutomationTools.kt:71（get() = true）
    "automation_create_workflow",  // feature/automation/.../AutomationTools.kt:146（get() = true）
    // 第 6 个：通道主动发送工具（P4-2）。此前两处镜像都漏了它。
    "send_channel_message",        // core/agent/.../tools/ChannelSendTool.kt:117
)

/** 6 个生产确认类假工具。 */
internal fun productionConfirmTools(): List<FakeTool> =
    PRODUCTION_CONFIRM_TOOL_NAMES.map { FakeTool(name = it, requiresConfirmation = true) }

/**
 * 内存版授权存储替身：用于「存储抛异常」「查询参数断言」等无法用真实实现表达的用例。
 * 命中规则与 [com.yunian.ai.domain.CapabilityGrantStore.decisionsFor] 契约一致：
 * 通配铺底 + 该伴侣的显式决定覆盖。
 */
internal class FakeCapabilityGrantStore(
    private val records: List<CapabilityGrant> = emptyList(),
    private val failure: Throwable? = null,
) : CapabilityGrantStore {

    /** 折叠点每次装配查询过的伴侣 id（用于断言「按伴侣查、不含通道」）。 */
    val queries = mutableListOf<Long>()

    override suspend fun decisions(): List<CapabilityGrant> {
        failure?.let { throw it }
        return records
    }

    override suspend fun decisionsFor(companionId: Long): Map<String, Boolean> {
        failure?.let { throw it }
        queries += companionId
        val effective = LinkedHashMap<String, Boolean>()
        for (record in records) {
            if (record.companionId == null) effective[record.toolName] = record.allowed
        }
        for (record in records) {
            if (record.companionId == companionId) effective[record.toolName] = record.allowed
        }
        return effective
    }

    override suspend fun decide(grant: CapabilityGrant) = Unit

    override suspend fun clear(companionId: Long?, toolName: String) = Unit
}
