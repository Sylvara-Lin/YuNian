package com.yunian.ai.common

/**
 * 表情「系统保留名」的**单一真值来源**。
 *
 * 背景（与用户报障同类）：发送侧 [TextProcessor] 会对这些名字**无条件跳过**
 * （见 `features/chat` 的 `SYSTEM_TAGS` 使用点），因为它们代表系统消息类型而非自定义表情。
 * 若用户把自定义表情命名为其中之一（例如「红包」），该表情会：
 *  - 出现在系统提示词的「可用表情包」清单里（AI 看得见）；
 *  - 但 AI 输出 `[红包]` 时被发送侧当作系统标签跳过 → **永远发不出去**。
 *
 * 因此导入 / 重命名 / ZIP 合并都必须拒绝或自动改名保留名。
 * 名单只能从这里取一份，禁止在别处再抄一遍（发送侧 [TextProcessor] 亦引用本清单）。
 */
object StickerReservedNames {

    /** 发送侧无条件跳过的系统标签（语音/图片/视频/文件/位置/红包/转账）。 */
    val SYSTEM_TAGS: Set<String> = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")

    /** 是否为系统保留名（trim 后精确匹配）。 */
    fun isReserved(raw: String): Boolean = raw.trim() in SYSTEM_TAGS

    /**
     * 保留名自动改名（ZIP 批量导入用）：追加后缀，避免与发送侧保留标签冲突。
     * 例：「红包」→「红包表情」。调用方应再经名字清洗限制长度。
     */
    fun resolveForImport(raw: String, suffix: String = "表情"): String = raw.trim() + suffix
}
