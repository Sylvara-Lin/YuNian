package com.yunian.ai.common.crash

/**
 * 崩溃日志 / breadcrumbs 的**尽力而为**敏感信息脱敏器。
 *
 * 目标：在把诊断信息交给用户「一键复制」并发给开发者之前，抹掉最常见的密钥形态
 * **以及内容类字段**，避免把 API Key / Token / 密码 / 聊天内容带出设备。
 *
 * 设计取舍：
 *  - 只在**落盘/展示那一刻**调用（见 [CrashBreadcrumbs.snapshot] / [CrashReporter]），
 *    绝不进入热路径 —— 逐次调用正则太贵。
 *  - 密钥：`sk-…`（含**任意长度的片段**，防 `take(8)` 之类的截断泄漏）、JWT、
 *    Google API Key、`Bearer …`、`key/secret/password/token/authorization = …` 键值对。
 *  - 内容：`title/content/body/original/prompt/reply/transcript = …` 的值一律抹掉。
 *    过度脱敏是可接受的失败方向 —— 崩溃诊断关心的是「调用序列」，不是内容本身。
 *  - **不**脱敏 `message:`（异常消息是诊断核心，只对其中命中的密钥/内容模式做替换）。
 */
object CrashRedactor {

    private const val MASK = "[REDACTED]"

    /**
     * `sk-` 前缀密钥，阈值压到 4 —— 覆盖 `key.take(8)` 这类**截断片段**（如 `sk-12345...`）。
     * 见 QA 报告：`AiService` 的 `Key失败冷却5s: ${key.take(8)}...`。
     */
    private val skKey = Regex("""(sk-[A-Za-z0-9_\-]{4,})""")

    /** JWT（三段点分 Base64URL）。 */
    private val jwt = Regex("""(eyJ[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{2,})""")

    /** Google API Key（阈值放宽以覆盖截断片段）。 */
    private val googleKey = Regex("""(AIza[0-9A-Za-z_\-]{8,})""")

    /** `Authorization: Bearer xxxxx` 或裸 `Bearer xxxxx`。 */
    private val bearer = Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]{6,}""")

    /**
     * 键值对形态的密钥：`api_key=…`、`"token": "…"`、`password: …` 等。
     * group(1) = 键名 + 分隔符，group(2) = 可选的引号，group(3) = 待抹掉的值。
     */
    private val kvSecret = Regex(
        """(?i)((?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|session[_-]?token|""" +
            """auth[_-]?token|token|key|client[_-]?secret|client[_-]?id|secret|password|passwd|authorization)""" +
            """\s*["']?\s*[=:]\s*)("?)([^\s"'&,;}]+)"""
    )

    /**
     * 内容类字段：`title=…`、`content=…`、`body=…`、`Original: '…'` 等。
     * group(1) = 键名 + 分隔符，group(2) = 可选引号，group(3) = 内容（抹掉）。
     * 覆盖 `PushMessageDispatcher(title/content)`、`DiaryService/SummaryService(body)`、
     * `AiResponseFinalizer/TextProcessor(Original)` 等既有调用点。
     */
    private val kvContent = Regex(
        """(?i)((?:title|content|body|original|prompt|reply|transcript)\s*["']?\s*[=:]\s*)(["']?)([^\n]+)"""
    )

    /**
     * 对输入文本做脱敏。传入 [String.isEmpty] 时原样返回。
     *
     * @param input 原始文本。
     * @return 脱敏后的文本；任何异常都会回退为原始文本（脱敏自身不能拖垮崩溃路径）。
     */
    fun redact(input: String): String {
        if (input.isEmpty()) return input
        return try {
            var out = input
            out = skKey.replace(out, MASK)
            out = jwt.replace(out, MASK)
            out = googleKey.replace(out, MASK)
            out = bearer.replace(out) { "${it.groupValues[1]}$MASK" }
            out = kvSecret.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            out = kvContent.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
            out
        } catch (_: Throwable) {
            input
        }
    }
}
