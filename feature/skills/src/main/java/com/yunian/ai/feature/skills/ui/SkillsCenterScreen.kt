@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.skills.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.SkillMetadata
import com.yunian.ai.feature.skills.accessibility.YuNianAccessibilityService
import com.yunian.ai.feature.skills.tools.ShizukuStatus
import com.yunian.ai.feature.skills.tools.checkShizukuStatus
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * AI 能力中心：展示并管理 AI 的全部扩展能力。
 * - 无障碍控制手机（状态 + 一键跳转系统设置）
 * - Shizuku 特权通道（三态 + 引导）
 * - 技能库（内置 + AI 自主安装的外部技能，可删除）
 */
@Composable
fun SkillsCenterScreen(onNavigateBack: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var a11yReady by remember { mutableStateOf(YuNianAccessibilityService.isReady) }
    var shizuku by remember { mutableStateOf<ShizukuStatus?>(null) }

    fun refreshAll() {
        scope.launch {
            val manager = ServiceRegistry.get(SkillManager::class.java)
            if (manager != null) skills = manager.discoverSkills()
            loading = false
            a11yReady = YuNianAccessibilityService.isReady
            shizuku = runCatching { checkShizukuStatus(context) }.getOrNull()
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    // 从系统设置返回后自动刷新无障碍/Shizuku 状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GlassPageScaffold(
        topBar = { GlassTopBar(title = "AI 能力中心", onBack = onNavigateBack) },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { CapabilitySectionLabel("控制通道") }
                item {
                    CapabilityCard(
                        title = "无障碍控制手机",
                        subtitle = if (a11yReady) "通道就绪：AI 可读屏、点击、滑动（敏感操作会先征求你的确认）"
                        else "未开启：开启后 AI 才能代替你操作手机",
                        statusColor = if (a11yReady) colors.success else colors.warning,
                        statusText = if (a11yReady) "已开启" else "未开启",
                        actionText = if (a11yReady) null else "去开启",
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { SecureLog.w("SkillsCenter", "open a11y settings failed: ${it.message}") }
                        },
                    )
                }
                item {
                    val s = shizuku
                    CapabilityCard(
                        title = "Shizuku 特权通道",
                        subtitle = s?.hint ?: "检测中…",
                        statusColor = when {
                            s == null -> colors.onSurfaceVariant
                            s.granted -> colors.success
                            else -> colors.warning
                        },
                        statusText = when {
                            s == null -> "…"
                            s.granted -> "已授权"
                            s.running -> "未授权"
                            s.installed -> "未运行"
                            else -> "未安装"
                        },
                        actionText = null,
                        onAction = null,
                    )
                }

                item { CapabilitySectionLabel("技能库（AI 可自主联网安装）") }
                if (skills.isEmpty()) {
                    item {
                        CapabilityCard(
                            title = "暂无可用技能",
                            subtitle = "告诉 AI 你想要什么能力，它会自动搜索并安装对应技能；也可在对话中让 AI 展示它安装了什么",
                            statusColor = colors.onSurfaceVariant,
                            statusText = "空",
                            actionText = null,
                            onAction = null,
                        )
                    }
                } else {
                    items(skills, key = { it.name }) { skill ->
                        SkillRow(
                            skill = skill,
                            manager = ServiceRegistry.get(SkillManager::class.java),
                            onChanged = { refreshAll() },
                        )
                    }
                }

                item { CapabilitySectionLabel("设备能力") }
                item {
                    CapabilityCard(
                        title = "设备工具集",
                        subtitle = "AI 已可直接使用：打开应用/网页、读写剪贴板、预填闹钟、发送通知、查电量、看时间；执行敏感操作前会先征求你的同意",
                        statusColor = colors.success,
                        statusText = "已启用",
                        actionText = null,
                        onAction = null,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun CapabilitySectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun CapabilityCard(
    title: String,
    subtitle: String,
    statusColor: Color,
    statusText: String,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = AppTheme.colors.surfaceVariant,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Medium, color = AppTheme.colors.onSurface, modifier = Modifier.weight(1f))
                Text(statusText, color = statusColor, fontSize = 13.sp)
            }
            Text(
                subtitle,
                color = AppTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.primary,
                            contentColor = AppTheme.colors.onPrimary,
                        ),
                    ) { Text(actionText) }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: SkillMetadata,
    manager: SkillManager?,
    onChanged: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var isExternal by remember(skill.name) { mutableStateOf(false) }

    LaunchedEffect(skill.name) {
        isExternal = manager?.isExternalSkill(skill.name) ?: false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(14.dp),
                surfaceColor = colors.surfaceVariant,
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name, fontWeight = FontWeight.Medium, color = colors.onSurface)
                    if (isExternal) {
                        Spacer(Modifier.width(6.dp))
                        Text("AI 安装", color = colors.primary, fontSize = 11.sp)
                    }
                }
                if (skill.description.isNotBlank()) {
                    Text(
                        skill.description,
                        color = colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (isExternal) {
                IconButton(onClick = {
                    scope.launch {
                        val removed = manager?.uninstallSkill(skill.name) ?: false
                        if (removed) onChanged()
                    }
                }) {
                    Icon(AppIcons.Trash2, contentDescription = "删除", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
