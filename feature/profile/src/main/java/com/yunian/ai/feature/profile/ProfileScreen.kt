package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.yunian.ai.uicommon.component.bounceVerticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.feature.profile.R
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onMemoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onThemeClick: () -> Unit,
    onBackgroundSettingsClick: () -> Unit = {},
    onGeneralSettingsClick: () -> Unit,
    onRoleManagerClick: () -> Unit,
    onTeamClick: () -> Unit = {},
    onSupportClick: () -> Unit = {},
    onThanksClick: () -> Unit = {},
    onAboutClick: () -> Unit,
    onSendMessageClick: () -> Unit = {},
    onVoiceCallClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    onFollowersClick: () -> Unit = {},
    onFollowingClick: () -> Unit = {},
    isSelf: Boolean = true,
    isOnline: Boolean = true,
    onProfileSettingsClick: () -> Unit = {},
    viewModel: ProfileViewModel = viewModel()
) {
    val colorScheme = AppTheme.colors
    val clipboard = LocalClipboardManager.current

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val selectedRole by viewModel.selectedRole.collectAsState()
    val userStatus by viewModel.userStatus.collectAsState()
    val userSignature by viewModel.userSignature.collectAsState()
    val companionCount by viewModel.companionCount.collectAsState()

    var showMoreSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()

            .background(Color.Transparent)

            .bounceVerticalScroll(resistance = 0.28f, maxOverscrollDp = 128f)
            .verticalScroll(rememberScrollState())
            .padding(top = 48.dp)
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = colorScheme.surface.copy(alpha = 0.85f),
                )
        ) {
            ProfileSectionRow(onClick = onProfileSettingsClick) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (userAvatar != null) {
                        AsyncImage(
                            model = userAvatar,
                            contentDescription = stringResource(R.string.profile_avatar),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            AppIcons.User,
                            stringResource(R.string.profile_avatar),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        userName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        userSignature.ifBlank { "点击编辑个人资料" },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = if (userSignature.isBlank()) colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = colorScheme.surface.copy(alpha = 0.85f),
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("$companionCount", "AI 伴侣", Modifier.weight(1f))
            StatDivider()
            StatItem(
                when (selectedRole) {
                    com.yunian.ai.common.CompanionRole.GIRLFRIEND -> "女友"
                    com.yunian.ai.common.CompanionRole.BOYFRIEND -> "男友"
                }, "角色", Modifier.weight(1f)
            )
            StatDivider()
            StatItem(userStatus.ifBlank { "—" }, "状态", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        val roleSubtitle = when (selectedRole) {
            com.yunian.ai.common.CompanionRole.GIRLFRIEND -> stringResource(R.string.role_manager_desc_girlfriend)
            com.yunian.ai.common.CompanionRole.BOYFRIEND -> stringResource(R.string.role_manager_desc_boyfriend)
        }
        SolidMenuGroup(listOf(
            MenuItemData(AppIcons.Heart, stringResource(R.string.role_manager), roleSubtitle, onRoleManagerClick),
            MenuItemData(AppIcons.MemoryStick, stringResource(R.string.memory_management), stringResource(R.string.memory_management_desc), onMemoryClick)
        ))
        Spacer(modifier = Modifier.height(12.dp))
        SolidMenuGroup(listOf(
            MenuItemData(AppIcons.Settings, stringResource(R.string.api_settings), stringResource(R.string.api_settings_desc), onSettingsClick),
            MenuItemData(AppIcons.Brush, stringResource(R.string.theme_mode), stringResource(R.string.theme_mode_desc), onThemeClick),
            MenuItemData(AppIcons.Palette, stringResource(R.string.background_settings), stringResource(R.string.background_settings_desc), onBackgroundSettingsClick)
        ))
        Spacer(modifier = Modifier.height(12.dp))
        SolidMenuGroup(listOf(
            MenuItemData(AppIcons.Settings, stringResource(R.string.general_settings), stringResource(R.string.general_settings_desc), onGeneralSettingsClick),
            MenuItemData(AppIcons.Info, stringResource(R.string.about_app), stringResource(R.string.about_app_desc), onAboutClick)
        ))
        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                BottomSheetItem(AppIcons.Send, "分享资料卡") { showMoreSheet = false }
                BottomSheetItem(AppIcons.Info, "复制ID") {
                    clipboard.setText(AnnotatedString("@$userName"))
                    showMoreSheet = false
                }
                BottomSheetItem(AppIcons.X, "举报", destructive = true) { showMoreSheet = false }
                BottomSheetItem(AppIcons.X, "拉黑", destructive = true) { showMoreSheet = false }
            }
        }
    }

}

@Composable
private fun StatItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp), color = AppTheme.colors.onSurface)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = AppTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(36.dp).background(AppTheme.colors.outline.copy(alpha = 0.3f)))
}

internal data class MenuItemData(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
internal fun SolidMenuGroup(items: List<MenuItemData>) {
    val cs = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = cs.surfaceVariant.copy(alpha = 0.9f),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items.forEachIndexed { i, item -> SolidMenuItem(item.icon, item.title, item.subtitle, item.onClick, i < items.size - 1) }
    }
}

@Composable
internal fun SolidMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, showDivider: Boolean) {
    val cs = AppTheme.colors
    Column {
        Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, title, Modifier.size(24.dp), tint = AppTheme.colors.onSurface)
            Spacer(modifier = Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = cs.onSurface)
                if (subtitle.isNotBlank()) { Spacer(modifier = Modifier.height(2.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cs.onSurfaceVariant) }
            }
            Icon(AppIcons.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (showDivider) Box(Modifier.fillMaxWidth().height(0.5.dp).background(cs.outline).padding(start = 36.dp))
    }
}

@Composable
internal fun BottomSheetItem(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = if (destructive) Color(0xFFFF3B30) else AppTheme.colors.onSurface, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = if (destructive) Color(0xFFFF3B30) else AppTheme.colors.onSurface)
    }
}
