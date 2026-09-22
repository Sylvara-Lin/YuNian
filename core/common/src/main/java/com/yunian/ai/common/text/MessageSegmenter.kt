package com.yunian.ai.common.text

/**
 * 消息文本工具。
 *
 * 历史上的句子级拆分能力（`SplitMode` / `split()` / `splitSimple` / `splitGroup` 等）已全部移除：
 * 「拆不拆、拆几条」的决策权已交还 AI，由气泡协议显式标记决定（见
 * [com.yunian.ai.network.bubble.BubbleJsonProtocol] 与 [BubbleTextSplitter]）。
 * 本对象现在只保留与拆分无关的 [isNoiseText]（被语音条过滤复用）。
 */
object MessageSegmenter {

    /**
     * 判断文本是否为「噪声」——纯语气词 / 纯标点，不值得单独合成语音条。
     *
     * @return true 表示该文本应被语音条合成跳过。
     */
    fun isNoiseText(text: String): Boolean {
        if (text.isBlank()) return true
        val noiseOnly = Regex("^[.…·~～\u2026\u4E00-\u9FFF\u3000\\s!！?？、，,。]+$")
        if (!noiseOnly.matches(text)) return false

        val interjection = setOf(
            "嗯", "嗯嗯", "嗯哼", "唔", "啊", "哦", "噢", "喔", "哈", "哈哈", "呵呵", "嘿", "唉",
            "呀", "嘛", "呢", "吧", "啦", "咯", "呗", "哟", "哇", "诶", "哎", "啧", "嗯呐",
        )
        val core = text.filter { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
        if (core.isEmpty()) return true

        return core.length <= 4 && core.all { it.toString() in interjection }
    }
}
