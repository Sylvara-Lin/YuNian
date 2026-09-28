package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.StickerInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

class ChatTurnState {

    var stickerSentThisTurn: Boolean = false

    @Volatile var pendingSticker: StickerInfo? = null

    var lastStickerMsgId: Long = -1

    var lastStickerContent: String = ""

    @Volatile var sendMessageJob: Job? = null

    /** 查重窗口所属的轮次 key（TurnId.value）；与当前轮不一致时重建窗口。 */
    var dedupTurnKey: String? = null

    /** 本轮已发气泡 + 最近历史 AI 消息的归一化查重窗口（随本轮逐条送达累积）。 */
    var dedupWindow: MutableList<String>? = null

    /**
     * 跨轮滚动查重窗口（P1-4）：最近若干轮**已落实**气泡的归一化内容，
     * 容量滚动 ≤ [RECENT_DEDUP_WINDOW_CAP]。
     * 价值恰恰在跨轮（防「历史 3 条窗口随轮次滚动而失效」的复读）——因此 [reset] 绝不清空它。
     *
     * 并发说明：deliverResponse 在逐条送达协程 [pushRecentDedup]，loadDedupWindow 在生成协程
     * 读（且 stale-job 取消不 join），窗口会跨线程并发读改。容器保持 ArrayDeque 不换，
     * 写入与读取快照均以 [@Synchronized] 串行化（同一实例监视器互斥）。
     */
    val recentDedupWindow: ArrayDeque<String> = ArrayDeque()

    /** 把一条已落实气泡的归一化内容压入跨轮窗口；超出容量时从队首滚出。 */
    @Synchronized
    fun pushRecentDedup(normalized: String) {
        if (normalized.isEmpty()) return
        recentDedupWindow.addLast(normalized)
        while (recentDedupWindow.size > RECENT_DEDUP_WINDOW_CAP) recentDedupWindow.removeFirst()
    }

    /**
     * 读取侧快照（与 [pushRecentDedup] 同一监视器互斥）。
     * 查重判定必须先取快照再遍历，禁止直接边遍历边改本窗口（理论 CME / 窗口内容丢失）。
     */
    @Synchronized
    fun snapshotRecentDedup(): List<String> = recentDedupWindow.toList()

    val stickerMutex: Mutex = Mutex()

    fun reset() {
        stickerSentThisTurn = false
        pendingSticker = null
        lastStickerMsgId = -1
        lastStickerContent = ""
        dedupTurnKey = null
        dedupWindow = null
    }

    fun cancelSendJob() {
        sendMessageJob?.cancel()
    }

    fun replaceSendJob(job: Job) {
        sendMessageJob?.cancel()
        sendMessageJob = job
    }

    fun flushStaleSticker(broadcast: (Long, String) -> Unit): Boolean {
        if (lastStickerMsgId > 0) {
            broadcast(lastStickerMsgId, lastStickerContent)
            lastStickerMsgId = -1
            lastStickerContent = ""
            return true
        }
        return false
    }

    fun enqueueStaleSticker(msgId: Long, content: String) {
        lastStickerMsgId = msgId
        lastStickerContent = content
    }
}

/** 跨轮滚动查重窗口容量（见 [ChatTurnState.recentDedupWindow]）。 */
internal const val RECENT_DEDUP_WINDOW_CAP = 16
