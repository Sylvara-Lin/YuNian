package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yunian.ai.common.MessageBodyState
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.feature.chat.ui.viewmodel.ChatRow
import com.yunian.ai.feature.chat.ui.viewmodel.toChatListItems
import com.yunian.ai.uicommon.theme.AdaptiveSizing

/**
 * 结构行渲染器：把 [ChatRow] + 行内正文状态解析为既有 [ChatListItem]，再交给既有
 * [ChatListItemRenderer] 渲染。
 *
 * 性能要点（T02/T03）：
 * - 列表结构（[ChatRow] 列表）只随 `messageMetadata` / `showReasoning` 变化；
 *   本渲染器在**行内**读取 `messageBodies[row.metadata.id]`，故流式 delta 只让**可见行**
 *   的 content lambda 重跑，非可见行不组合。
 * - `remember(row, body)` 对解析结果做记忆化：当 `body`（data class）与上次 `equals` 时，
 *   返回同一 [ChatListItem] 实例 → 内层气泡被 Compose skip。
 * - 头像点击回调不再逐行透传（改为 `ChatScreen` 上提一次 provide 的 CompositionLocal）。
 * - 果冻入场动画的「幂等锁」从组合期 `seen.add` 副作用改为**行级 effect 上报**
 *   （见 [onJellyPlayed]）：首帧组合后回报一次「已播放」，`ChatScreen` 随即把该 id
 *   移出待播放集合 → 滚动回收重组不会重播，且组合期不再写任何共享状态。
 *
 * 复用全部既有 UI 组件，不重写渲染。
 */
@Composable
fun ChatRowRenderer(
    row: ChatRow,
    messageBodies: Map<Long, MessageBodyState<ChatMessage>>,
    companionData: CompanionEntity?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    onRetryBody: (Long) -> Unit = {},
    autoCollapseReasoning: Boolean = true,
    jellyEntrance: Boolean = false,
    // 果冻入场「已播放」回报：由 ChatScreen 维护的幂等锁消费。默认空实现 → 预览 / 其他调用点不受影响。
    onJellyPlayed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 行内读取正文：仅当本行是消息行时才订阅 messageBodies（非消息行不产生依赖）。
    val body = if (row is ChatRow.Message) messageBodies[row.metadata.id] else null
    val item = remember(row, body) { resolveItem(row, body) }

    // 组合期零副作用：播放决策只读 `jellyEntrance`；真正的「标记已播放」放到 effect 阶段，
    // 且只在消息行上报（实时工具卡按既有语义「出现即弹」，不做幂等锁）。
    LaunchedEffect(row.stableId) {
        if (jellyEntrance && row is ChatRow.Message) onJellyPlayed(row.stableId)
    }

    ChatListItemRenderer(
        item = item,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        isDarkTheme = isDarkTheme,
        onRetryBody = onRetryBody,
        autoCollapseReasoning = autoCollapseReasoning,
        jellyEntrance = jellyEntrance,
        modifier = modifier,
    )
}

/**
 * [ChatRow] → [ChatListItem] 的 O(1) 映射。
 *
 * 消息行**复用**既有 `toChatListItems(metadata, bodies, showReasoning)`（单元素）：
 * 该函数必然产出 `[TimeDivider, item]`（时间线恒为第一个），取 `last()` 即正文项，
 * 从而**零重复**地保持与 `ChatListItem.kt:146-157` 完全一致的映射语义
 * （BodyLoading / BodyError / Reasoning / SystemTip / Sticker / Voice / ... 全部分支一致）。
 */
private fun resolveItem(
    row: ChatRow,
    body: MessageBodyState<ChatMessage>?,
): ChatListItem = when (row) {
    is ChatRow.TimeDivider -> ChatListItem.TimeDivider(row.timestamp)

    is ChatRow.LiveToolGroup -> ChatListItem.ToolActivityGroup(
        stableId = ChatListItem.ToolActivityGroup.LIVE_STABLE_ID,
        activities = row.activities,
        message = null,
        isLive = true,
    )

    is ChatRow.Message -> {
        val bodies: Map<Long, MessageBodyState<ChatMessage>> =
            if (body != null) mapOf(row.metadata.id to body) else emptyMap()
        toChatListItems(listOf(row.metadata), bodies, showReasoning = true)
            .lastOrNull()
            ?: ChatListItem.BodyLoading(row.metadata)
    }
}
