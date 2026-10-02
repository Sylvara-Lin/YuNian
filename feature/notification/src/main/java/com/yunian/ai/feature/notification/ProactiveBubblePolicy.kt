package com.yunian.ai.feature.notification

import com.yunian.ai.common.text.BubbleTextSplitter

/**
 * 主动消息气泡策略（纯逻辑，可单测）。
 *
 * **为何需要硬上限**：主动消息与聊天路径不同——聊天是 1v1 实时对话（用户正看着屏幕），
 * 可以「条数不限」；主动消息是**推送到通知栏/锁屏**的，用户可能正在忙别的事，连发过多
 * 骚扰感强。故除 [ProactiveMessageInstruction] 的「1~3 条」**软引导**外，这里再加一道
 * **代码侧硬上限**兜底：即便模型失控敲了很多行，也不会真的刷屏。
 *
 * 与既有链路的关系：本策略只做「保序截断」，不改变 [BubbleTextSplitter] 的拆行语义；
 * 截断后的列表仍逐条进入既有的**逐气泡查重**（DedupGuard）与**通知预览合并**逻辑，
 * 因此截断不会破坏这些行为（只是少处理被丢弃的尾部气泡）。
 */
internal object ProactiveBubblePolicy {

    /**
     * 单次主动消息最多落库的气泡数。
     *
     * 取 **3** 的理由：主动消息场景下 3 条已是「连发」的观感上限（真人微信主动找人时通常
     * 1~3 条）；≥4 条开始明显像刷屏/骚扰，且通知栏合并预览也会更长。该值与软引导文案
     * 「连发 1~3 条 / 最多 2~3 条」保持一致，形成「软引导 + 硬兜底」双保险。
     */
    const val MAX_BUBBLES = 3

    /**
     * 按换行拆气泡并施加硬上限（保序、保留前 [MAX_BUBBLES] 条）。
     *
     * 供单测与调用方复用，保证「拆行 + 截断」口径与生产一致。
     */
    fun splitAndCap(text: String): List<String> = cap(BubbleTextSplitter.splitByParagraphs(text))

    /**
     * 对已拆好的气泡列表施加硬上限：超过 [MAX_BUBBLES] 时**保序保留前 [MAX_BUBBLES] 条**，
     * 否则原样返回（不复制、不改变元素）。
     */
    fun cap(bubbles: List<String>): List<String> =
        if (bubbles.size > MAX_BUBBLES) bubbles.take(MAX_BUBBLES) else bubbles
}
