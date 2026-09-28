package com.yunian.ai.feature.chat.ui.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.screen.ToolActivityGroupItem
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.uicommon.component.jellyEntrance
import com.yunian.ai.uicommon.theme.AdaptiveSizing

/**
 * 单个列表项的渲染入口（消息 / 时间线 / 系统提示 / 工具卡 / 正文状态占位）。
 *
 * 头像点击回调（`LocalCompanionAvatarClick` / `LocalUserAvatarClick`）已**上提**到
 * `ChatScreen` 的 `LazyColumn` 外层统一 provide 一次（见 T03），此处不再逐项包裹
 * `CompositionLocalProvider`，消除每项两个 static local 节点的开销。
 * 头像组件 [ChatMessageAvatar] 直接读取 `LocalXxxAvatarClick.current`。
 */
@Composable
fun ChatListItemRenderer(
    item: ChatListItem,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    onRetryBody: (Long) -> Unit = {},
    autoCollapseReasoning: Boolean = true,
    // 是否播放「果冻」入场动画。默认 false —— 既有调用点 / 预览不受影响。
    // 这是所有消息类型的唯一分发点，所以动画挂在外层 Box 上一次即可覆盖全部类型。
    jellyEntrance: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .jellyEntrance(
                play = jellyEntrance,
                // 从气泡「根部」那一侧的下角长出来：我方消息贴右、对方消息贴左。
                // 注意 TransformOrigin 的具名参数是 pivotFractionX/Y，这里用位置参数。
                transformOrigin = TransformOrigin(
                    if (item.messageOrNull?.isFromUser == true) 1f else 0f,
                    1f
                )
            )
    ) {
        when (item) {
            is ChatListItem.BodyLoading -> BodyStateItem(isError = false)
            is ChatListItem.BodyError -> BodyStateItem(
                isError = true,
                onClick = { onRetryBody(item.metadata.id) }
            )
            is ChatListItem.TimeDivider -> TimeDividerItem(item = item)
            is ChatListItem.SystemTip -> SystemTipItem(item = item)
            is ChatListItem.ReasoningMessage -> ReasoningItem(
                reasoningText = item.text,
                adaptiveSizing = adaptiveSizing,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                autoCollapse = autoCollapseReasoning,
                isStreaming = item.isStreaming,
                durationMs = item.durationMs,
            )
            is ChatListItem.TextMessage -> TextMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.ImageMessage -> ImageMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.VoiceMessage -> VoiceMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.StickerMessage -> StickerMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.VideoMessage -> VideoMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.FileMessage -> FileMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.ToolActivityGroup -> ToolActivityGroupItem(
                activities = item.activities,
                isLive = item.isLive,
            )
        }
    }
}

@Composable
private fun BodyStateItem(isError: Boolean, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isError, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isError) {
            Text("正文加载失败，点击重试")
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}
