package com.yunian.ai.feature.groupchat.ui
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.ImageUtils
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.feature.groupchat.GroupChatViewModel
import com.yunian.ai.feature.groupchat.GroupChatViewModelFactory
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupId: Long,
    onNavigateBack: () -> Unit,
    onGroupDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: GroupChatViewModel = viewModel(
        factory = GroupChatViewModelFactory(context.applicationContext as Application, groupId)
    )
    val groupData by viewModel.groupData.collectAsState()
    val companions by viewModel.allCompanions.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    val activeCompanions = remember(groupData, companions) {
        val activeCompanionIds = groupData?.getCompanionIdList()?.toSet() ?: emptySet()
        companions.filter { activeCompanionIds.contains(it.id) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.viewModelScope.launch {
                val savedPath = ImageUtils.saveUriToInternalStorage(context, selectedUri.toString())
                if (savedPath != null) {
                    viewModel.updateGroupAvatar(savedPath)
                }
            }
        }
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "聊天信息",
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(16.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .padding(20.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(AppTheme.colors.surfaceVariant)
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (groupData?.avatarUrl != null) {
                            AsyncImage(
                                model = groupData?.avatarUrl,
                                contentDescription = groupData?.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = groupData?.name?.firstOrNull()?.toString() ?: "?",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = groupData?.name ?: "群聊",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.onBackground
                        )
                        Icon(
                            imageVector = AppIcons.Pencil,
                            contentDescription = "编辑",
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    newGroupName = groupData?.name ?: ""
                                    showEditNameDialog = true
                                },
                            tint = AppTheme.colors.primary
                        )
                    }
                    Text(
                        text = "${activeCompanions.size} 个AI成员",
                        fontSize = 13.sp,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(16.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "群成员",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    activeCompanions.forEach { companion ->
                        GroupMemberItem(companion = companion)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(16.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .padding(16.dp)
            ) {
                Column {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {  }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.Trash2,
                            contentDescription = null,
                            tint = AppTheme.colors.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "清空聊天记录",
                            fontSize = 15.sp,
                            color = AppTheme.colors.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeleteDialog = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.Trash2,
                            contentDescription = null,
                            tint = AppTheme.colors.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "退出群聊",
                            fontSize = 15.sp,
                            color = AppTheme.colors.error
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("退出群聊") },
            text = { Text("确定要退出并删除这个群聊吗？聊天记录将无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroup()
                        showDeleteDialog = false
                        onGroupDeleted()
                    }
                ) {
                    Text("确定", color = AppTheme.colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("修改群名称") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("群名称") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primary,
                        unfocusedBorderColor = AppTheme.colors.outline,
                        focusedLabelColor = AppTheme.colors.primary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {

                        showEditNameDialog = false
                    }
                ) {
                    Text("确定", color = AppTheme.colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun GroupMemberItem(
    companion: CompanionEntity
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (companion.avatarUrl != null) {
                AsyncImage(
                    model = companion.avatarUrl,
                    contentDescription = companion.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = companion.name.firstOrNull()?.toString() ?: "?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = companion.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onBackground
            )
            Text(
                text = companion.personality,
                fontSize = 12.sp,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
