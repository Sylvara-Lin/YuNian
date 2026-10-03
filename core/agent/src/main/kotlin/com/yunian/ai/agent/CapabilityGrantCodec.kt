package com.yunian.ai.agent

import com.yunian.ai.domain.CapabilityGrant

/**
 * [CapabilityGrantCodec.decode] 的结果。
 *
 * [droppedLineCount] 供可观测性使用：坏行被丢弃是**预期内**的容错行为（不是异常），
 * 但静默丢数据会掩盖问题，因此单独计数、由调用方记日志。
 */
internal data class CapabilityGrantDecodeResult(
    /** 解析成功的决定（保序、已去重）。 */
    val decisions: List<CapabilityGrant>,
    /** 因无法解析而被丢弃的行数（不含空白行）。 */
    val droppedLineCount: Int,
)

/**
 * 工具授权表的**纯文本编解码**（无 Android / 无 IO，可在纯 JVM 单测里直接驱动）。
 *
 * 持久化格式：**每条决定一行**，行内以 `|` 分隔三个字段：
 *
 * ```
 * <companionId>|<toolName>|0或1
 * ```
 *
 * - `<companionId>`：十进制 Long；**通配写 `*`**（= 适用所有伴侣）；
 * - `<toolName>`：工具名（`AiTool.name`），不得为空；
 * - `0或1`：`1` = 允许（不需要确认），`0` = 需要确认。**只接受这两个字面量**。
 *
 * 例：
 * ```
 * *|automation_create|1
 * 42|screen_tap|0
 * ```
 *
 * 解析容错（**硬约束**）：任何无法解析的行一律**丢弃该行**，绝不抛异常。
 * 授权表是「放松确认门」的开关，坏数据只能让它更保守（该工具视为无决定 ⇒ 回到工具自身默认），
 * 绝不能让它把对话 / 启动链路带崩。丢弃条件见 [decode]。
 *
 * 旧格式（三段式 `companionId|channelKey|toolName`）的第三段是工具名而非 `0`/`1`，
 * 因此整体落进「丢弃」路径 ⇒ 等同无决定 ⇒ fail-closed。这是**可接受**的：
 * 通道维度版本从未发布，不存在真实用户数据需要迁移。
 *
 * 编码侧的对称约束：字段含分隔符 `|` 或为空白时，该条决定**无法安全编码**，
 * 编码时直接跳过（不写出会被解错的脏数据）；因此「解码 → 编码」是稳定的。
 */
internal object CapabilityGrantCodec {

    /** 字段分隔符。 */
    const val FIELD_SEPARATOR: Char = '|'

    /** 通配伴侣 ID 的文本表示（= 适用所有伴侣）。 */
    const val WILDCARD_COMPANION: String = "*"

    /** 允许（不需要确认）的文本表示。 */
    const val ALLOWED_TRUE: String = "1"

    /** 需要确认的文本表示。 */
    const val ALLOWED_FALSE: String = "0"

    /** 一行必须恰好三个字段。 */
    private const val FIELD_COUNT = 3

    /**
     * 编码为多行文本；无有效决定时返回空字符串。
     *
     * 跳过条件（宁可不写也不写脏数据）：[CapabilityGrant.toolName] 为空白 / 含分隔符。
     * 返回文本**不带**结尾换行。
     */
    fun encode(decisions: Collection<CapabilityGrant>): String {
        val lines = LinkedHashSet<String>()
        for (decision in decisions) {
            val line = encodeLine(decision) ?: continue
            lines += line
        }
        return lines.joinToString("\n")
    }

    /**
     * 解码多行文本（[raw] 为 null / 空串 = 「没有任何决定」，返回空结果）。
     *
     * 逐行处理，**丢弃**下列行（其余行照常解析，全程绝不抛异常）：
     * 1. 空白行（不计入 [CapabilityGrantDecodeResult.droppedLineCount]）；
     * 2. 字段个数不等于 3 的行（含旧的三段式 `a|b|c` 与其它脏数据）；
     * 3. 第一字段既不是 `*` 也无法解析成 Long 的行；
     * 4. 工具名字段为空白（或解码后为空白）的行；
     * 5. 第三字段不是 `0` / `1` 的行；
     * 6. 任何解析过程中抛异常的行走同一条「丢弃」路径。
     *
     * 行首尾空白（含 CRLF 残留的 `\r`）在解析前被裁剪；
     * **同一 (companionId, toolName) 键重复出现时，后者覆盖前者**（与 [CapabilityGrantStoreImpl.decide]
     * 的「同键覆盖」语义一致，并保证 [CapabilityGrantStoreImpl.decisions] 里同一个键最多出现一次）。
     */
    fun decode(raw: String?): CapabilityGrantDecodeResult {
        if (raw.isNullOrEmpty()) return CapabilityGrantDecodeResult(emptyList(), 0)
        val decisions = LinkedHashMap<Pair<Long?, String>, CapabilityGrant>()
        var dropped = 0
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val decision = parseLine(line)
            if (decision == null) {
                dropped += 1
            } else {
                decisions[decision.companionId to decision.toolName] = decision
            }
        }
        return CapabilityGrantDecodeResult(decisions.values.toList(), dropped)
    }

    /** 编码单条决定；无法安全编码时返回 null。 */
    private fun encodeLine(decision: CapabilityGrant): String? {
        val toolName = decision.toolName
        if (toolName.isBlank() || toolName.contains(FIELD_SEPARATOR)) return null
        val companionId = decision.companionId?.toString() ?: WILDCARD_COMPANION
        val allowed = if (decision.allowed) ALLOWED_TRUE else ALLOWED_FALSE
        return companionId + FIELD_SEPARATOR + toolName + FIELD_SEPARATOR + allowed
    }

    /**
     * 解析单行；无法解析时返回 null（调用方按「丢弃该行」处理）。
     *
     * 整体包在 [runCatching] 里是**刻意的兜底**：即使将来有人往这里加了会抛异常的校验，
     * 本函数也仍然只返回 null，绝不把异常泄漏到对话链路上。
     */
    private fun parseLine(line: String): CapabilityGrant? = runCatching {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) return@runCatching null
        val companionIdText = parts[0].trim()
        val toolName = parts[1].trim()
        val allowedText = parts[2].trim()
        if (toolName.isEmpty()) return@runCatching null
        val allowed = when (allowedText) {
            ALLOWED_TRUE -> true
            ALLOWED_FALSE -> false
            else -> return@runCatching null
        }
        val companionId: Long? = if (companionIdText == WILDCARD_COMPANION) {
            null
        } else {
            companionIdText.toLongOrNull() ?: return@runCatching null
        }
        CapabilityGrant(companionId = companionId, toolName = toolName, allowed = allowed)
    }.getOrNull()
}
