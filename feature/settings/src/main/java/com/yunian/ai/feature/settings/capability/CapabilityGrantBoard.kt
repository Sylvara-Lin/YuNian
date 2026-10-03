package com.yunian.ai.feature.settings.capability

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.CapabilityDefaults
import com.yunian.ai.domain.CapabilityGrant

/**
 * 「工具授权」设置页的**纯逻辑**：列出全部 Agent tools / 决定某工具是否允许 / 拨动开关 → decide·clear 的映射。
 *
 * 为什么单独成类：本仓库没有 Robolectric，Compose 也不做仪器测试，页面逻辑若写在
 * Composable 里就等于零覆盖。这里全部是纯 Kotlin（只依赖 core:domain 的
 * [AiTool] / [CapabilityDefaults] / [CapabilityGrant]，不碰 Android、不碰 Room、不碰 Compose），
 * 因此可以在 :feature:settings 的 JVM 单测里逐条钉死。
 *
 * ## 为什么没有通道维度（P2-2d 重构）
 * 授权对象是**工具能力本身**。用户要的是一个工具能力集合，成员由用户决定，
 * 所以页面列出**全部** Agent tools，开关是**两向**的（能允许、也能改成「必须先确认」）；
 * 通道只影响「确认界面有没有」，不参与「这个工具是否已被授权」的判定。
 *
 * ## 语义对齐（与 core:agent 的折叠点保持同一套判定）
 * 折叠点 `AgentFacade.toolDefinitionsFor(companionId, tools)` 读取的是
 * `CapabilityGrantStore.decisionsFor(companionId)`（通配铺底 + 该伴侣覆盖），
 * 再交给 core:domain 的 [CapabilityDefaults.requiresConfirm] 判定「要不要确认」。
 * 本类的 [GrantToolItem.allowed] 用**同一套规则 + 同一个函数**计算，
 * 保证「设置页显示的开关状态」与「装配期实际的放行结果」不可能漂移。
 *
 * ## 展示与写入的分工（避免跨伴侣误伤）
 * - 展示：**合并**通配与本伴侣决定（[GrantToolItem.allowed]），具体伴侣的显式决定优先；
 * - 写入：只写**当前作用域自己的那一条**（[CapabilityGrantBoard.write]）——在具体伴侣视图里
 *   永远不会去动通配记录，否则「改某个伴侣的一项」会静默把其它伴侣一起改掉。
 */
sealed interface GrantScope {

    /** 「全部伴侣（通配）」：对应 [CapabilityGrant.companionId] = `null` 的通配记录。 */
    data object AllCompanions : GrantScope

    /** 某个具体伴侣：对应 `companionId = id` 的记录。 */
    data class Companion(val companionId: Long) : GrantScope
}

/** 写入记录时使用的 companionId（`null` = 通配）。 */
fun GrantScope.companionIdOrNull(): Long? = when (this) {
    GrantScope.AllCompanions -> null
    is GrantScope.Companion -> companionId
}

/** 伴侣下拉项（来自 core:database 的既有伴侣列表，不新增查询）。 */
data class CompanionOption(val id: Long, val name: String)

/**
 * 下拉框 / 标题里显示当前作用域的名字。
 *
 * [GrantScope.AllCompanions] 显式写成「全部伴侣（通配）」，是为了让用户看到「这一改会作用于谁」；
 * 查不到名字时回退成 `伴侣 #id`（伴侣被删除后的残留作用域不该让页面显示空白）。
 */
fun scopeDisplayName(scope: GrantScope, companions: List<CompanionOption>): String = when (scope) {
    GrantScope.AllCompanions -> "全部伴侣（通配）"
    is GrantScope.Companion -> companions.firstOrNull { it.id == scope.companionId }?.name
        ?: "伴侣 #${scope.companionId}"
}

/**
 * 清单里的一行（**全部** Agent tools 逐条对应一行）。
 *
 * [requiresConfirmation] **逐字透传** [AiTool.requiresConfirmation]：它是「这个工具自己说要不要确认」
 * 的唯一权威声明，也是「没有显式决定时的默认值」，页面据此标注「默认需确认 / 默认允许」。
 */
data class GrantableTool(
    val name: String,
    val displayName: String,
    val description: String,
    val requiresConfirmation: Boolean,
)

/**
 * 页面上单个工具项的展示状态。
 *
 * [allowed] 是**合并展示**结果（本伴侣决定优先，其次通配，都没有才回到工具默认），
 * 也就是装配期 `decisionsFor()` + [CapabilityDefaults.requiresConfirm] 会看到的结果；
 * [explicit] 表示当前作用域**自己**是否已有显式决定；
 * [inheritedAllowed] 是**通配**给出的允许状态（`null` = 通配没有决定），
 * 只在具体伴侣视图下非空，用于判断「清掉本伴侣的决定后会不会被通配重新放行」；
 * [showsWildcardSource] 是「这一行的当前状态来自通配」的**唯一**判据，渲染层只读它、不再自己拼条件。
 */
data class GrantToolItem(
    val tool: GrantableTool,
    val allowed: Boolean,
    val explicit: Boolean,
    val inheritedAllowed: Boolean? = null,
) {
    val toolName: String get() = tool.name

    val displayName: String get() = tool.displayName

    val description: String get() = tool.description

    /** 工具自身声明的默认：`true` = 默认就需要确认（页面上标注「默认需确认」）。 */
    val requiresConfirmation: Boolean get() = tool.requiresConfirmation

    /**
     * 是否显示「当前状态来自『全部伴侣（通配）』设置」这行来源说明。
     *
     * 判据只有一个：[inheritedAllowed] 非空——即**通配确实有决定**、且当前作用域没有自己的记录覆盖它。
     * 放在这里而不是 Composable 里的原因：这个判断曾经写在渲染层，写成
     * `!explicit && !allowed`，漏掉了作用域条件——「全部伴侣」视图下
     * [inheritedAllowed] 恒为 null（该视图写的就是通配本身），`explicit` 对没设过的工具也恒为 false，
     * 于是存储为空时**任何默认关闭的工具**都会声称「状态来自通配」，而那时并不存在任何通配决定。
     * 渲染层的条件没有测试能到达，所以把判断收进这个纯逻辑类，由 JVM 单测钉死。
     */
    val showsWildcardSource: Boolean get() = inheritedAllowed != null
}

/** 一次开关拨动要落到的写入动作。 */
sealed interface GrantWrite {

    /** 写入一条显式决定（同键覆盖）。 */
    data class Decide(val grant: CapabilityGrant) : GrantWrite

    /** 清除该作用域自己的显式决定（该键回到默认）。 */
    data class Clear(val companionId: Long?, val toolName: String) : GrantWrite

    /** 不产生任何写入（工具名为空等非法输入）。 */
    data object None : GrantWrite
}

object CapabilityGrantBoard {

    /**
     * 已知工具的中文名（与 feature:chat 的 ToolActivityBar 命名逐条一致）。
     * 未收录的工具回退显示 [AiTool.name]——新工具一注册就能用，不会因为这里没登记而消失。
     */
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "screen_tap" to "点击屏幕",
        "screen_swipe" to "滑动屏幕",
        "screen_click_text" to "点击屏幕元素",
        "automation_create" to "创建自动化",
        "automation_create_workflow" to "创建工作流",
    )

    /**
     * 把注册池里的**全部** Agent tools 转成清单行（**不再过滤** `requiresConfirmation`）。
     *
     * 为什么不过滤：用户要的是「一个工具能力集合，成员由用户决定」。只列确认类工具会让用户
     * 无法表达「这个安全工具我也要它先确认」，而那正是两向开关存在的理由。
     *
     * 去重（同名只留一条：ToolRegistry 本身同名覆盖，但调用方可能已经把多个来源拼在一起）
     * 并按工具名排序，保证同一份注册池渲染出的顺序**稳定**（不随注册顺序 / 进入页面次数跳动）。
     */
    fun grantableTools(tools: List<AiTool>): List<GrantableTool> = tools
        .asSequence()
        .map { tool ->
            GrantableTool(
                name = tool.name,
                displayName = DISPLAY_NAMES[tool.name] ?: tool.name,
                description = tool.description,
                requiresConfirmation = tool.requiresConfirmation,
            )
        }
        .distinctBy { it.name }
        .sortedBy { it.name }
        .toList()

    /**
     * 某作用域视角下的**有效决定表**（工具名 → 是否允许），与折叠点的输入同源。
     *
     * - [GrantScope.AllCompanions]：只认通配记录（`companionId == null`）。
     *   刻意**不**合并具体伴侣的记录：这个视图的开关写的就是通配记录，
     *   若把某个伴侣的记录也显示成生效，用户会以为通配已开，实际其它伴侣仍被确认门拦下；
     * - 具体伴侣：通配先铺底，该伴侣自己的记录再覆盖（**该伴侣的显式决定优先**）。
     *
     * 这与 `CapabilityGrantStoreImpl.decisionsFor` 的命中规则逐字一致，
     * 只是多了一个「全部伴侣」视角（存储契约按单个伴侣取，通配视角在页面侧就地计算）。
     */
    fun effectiveDecisions(
        scope: GrantScope,
        decisions: List<CapabilityGrant>,
    ): Map<String, Boolean> = when (scope) {
        GrantScope.AllCompanions -> decisions
            .filter { it.companionId == null }
            .associate { it.toolName to it.allowed }

        is GrantScope.Companion -> {
            val effective = LinkedHashMap<String, Boolean>()
            for (decision in decisions) {
                if (decision.companionId == null) effective[decision.toolName] = decision.allowed
            }
            for (decision in decisions) {
                if (decision.companionId == scope.companionId) effective[decision.toolName] = decision.allowed
            }
            effective
        }
    }

    /**
     * 整个页面主体：**一张清单**，每个工具一行。
     *
     * 单点入口：[tools] 为**全量**工具池（由 `ToolRegistry.availableTools(includeAppLocal = true)`
     * 得来），列表顺序沿用 [grantableTools] 的稳定排序。
     */
    fun items(
        tools: List<GrantableTool>,
        decisions: List<CapabilityGrant>,
        scope: GrantScope,
    ): List<GrantToolItem> {
        val effective = effectiveDecisions(scope, decisions)
        return tools.map { tool -> item(tool, effective, decisions, scope) }
    }

    /**
     * 单个工具项在**当前作用域**下的展示状态。
     *
     * - 开关值 = 该工具在当前作用域的有效显式决定；没有决定则**回到工具自身默认**
     *   （`!tool.requiresConfirmation`），判定收在 [CapabilityDefaults.requiresConfirm] 里，
     *   与折叠点共用同一实现；
     * - [GrantToolItem.explicit] 只表示「当前作用域自己有没有记录」：
     *   具体伴侣视图下若只是被通配覆盖，开关照样反映通配的效果，但标注会说明来源。
     */
    fun item(
        tool: GrantableTool,
        effective: Map<String, Boolean>,
        decisions: List<CapabilityGrant>,
        scope: GrantScope,
    ): GrantToolItem {
        val companionId = scope.companionIdOrNull()
        val explicitDecision = decisions.any { it.companionId == companionId && it.toolName == tool.name }
        val effectiveDecision = effective[tool.name]
        val allowed = !CapabilityDefaults.requiresConfirm(
            explicit = effectiveDecision,
            toolRequiresConfirmation = tool.requiresConfirmation,
        )
        return GrantToolItem(
            tool = tool,
            allowed = allowed,
            explicit = explicitDecision,
            // 通配给出的值只有在「本伴侣自己没有覆盖」时才等于生效值；
            // 全部伴侣视图下没有继承来源（该视图写的就是通配本身）。
            inheritedAllowed = when {
                scope is GrantScope.AllCompanions -> null
                effectiveDecision == null -> null
                explicitDecision -> null
                else -> effectiveDecision
            },
        )
    }

    /**
     * 开关拨动 → 写入动作的映射。
     *
     * 两条规则（**存储最小化** + 两向可控）：
     * 1. 用户选的值**既等于工具自身的默认行为、又不会被通配重新覆盖** ⇒ [GrantWrite.Clear]：
     *    这一拨只是「回到默认」，留一条与默认同义的记录纯属冗余，清掉即可；
     * 2. 否则 ⇒ [GrantWrite.Decide]：在当前作用域写一条显式决定
     *    （全部伴侣 ⇒ `companionId = null`；具体伴侣 ⇒ 该 id）。
     *
     * 规则 1 的两个条件缺一不可：
     * - 「等于工具默认」= `allowed == !requiresConfirmation`；
     * - 「不会被通配重新覆盖」= 通配没有决定，或通配给的值恰好与用户选的一致
     *   （[GrantToolItem.inheritedAllowed]）。
     *
     * 于是「所有伴侣都允许某危险工具、唯独伴侣 42 不允许」可以这样表达：
     * 在「全部伴侣」打开该工具 ⇒ 写通配 `*|tool|1`；
     * 切到伴侣 42 把它关掉 ⇒ 不能 clear（clear 之后它又会被通配放行），
     * 必须写 `42|tool|0` —— 这正是两向开关能表达「通配 + 单点例外」的原因。
     *
     * 工具名为空白 ⇒ [GrantWrite.None]（写了也匹配不到任何工具定义，属脏数据）。
     */
    fun write(
        scope: GrantScope,
        tool: GrantableTool,
        allowed: Boolean,
        inheritedAllowed: Boolean? = null,
    ): GrantWrite {
        if (tool.name.isBlank()) return GrantWrite.None
        val companionId = scope.companionIdOrNull()
        val matchesToolDefault = allowed == !tool.requiresConfirmation
        val wildcardKeepsIt = inheritedAllowed == null || inheritedAllowed == allowed
        if (matchesToolDefault && wildcardKeepsIt) return GrantWrite.Clear(companionId, tool.name)
        return GrantWrite.Decide(
            CapabilityGrant(companionId = companionId, toolName = tool.name, allowed = allowed),
        )
    }
}
