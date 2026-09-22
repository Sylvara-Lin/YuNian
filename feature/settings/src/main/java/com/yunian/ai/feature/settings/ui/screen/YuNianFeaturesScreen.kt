@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.SkillMetadata
import com.yunian.ai.domain.McpManager
import com.yunian.ai.domain.McpServerStatus
import kotlinx.coroutines.launch
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import androidx.compose.ui.graphics.Color

@Composable
fun SkillsScreen(onNavigateBack: () -> Unit) {
    val colorScheme = AppTheme.colors
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val manager = ServiceRegistry.get(SkillManager::class.java)
        if (manager != null) skills = manager.discoverSkills()
        loading = false
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "技能库",
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (skills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.Book, null, Modifier.size(64.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("暂无可用技能", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Text("AI 可通过 load_skill 工具加载技能文档，获得专业领域的操作方法", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(skills) { skill ->
                    Card(
                        modifier = Modifier.drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(16.dp),
                            surfaceColor = colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(skill.name, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                            Text(skill.description, color = colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun McpSettingsScreen(onNavigateBack: () -> Unit) {
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<com.yunian.ai.domain.McpServerStatus>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var manager by remember { mutableStateOf<com.yunian.ai.domain.McpManager?>(null) }

    fun refresh() {
        scope.launch {
            val mgr = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.McpManager::class.java)
            manager = mgr
            if (mgr != null) servers = mgr.getServerStatuses()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, transportType ->
                showAddDialog = false
                scope.launch {
                    val mgr = manager
                    val config = com.yunian.ai.domain.McpServerConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url,
                        transportType = transportType
                    )
                    mgr?.upsertServer(config)
                    refresh()
                }
            }
        )
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "MCP 服务管理",
                onBack = onNavigateBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary) {
                Icon(AppIcons.Plus, null)
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AppIcons.Network, null, Modifier.size(64.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("暂无 MCP 服务", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Text("点击右下角 + 添加 MCP 服务器，为 AI 扩展外部工具能力", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(servers) { server ->
                    Card(
                        modifier = Modifier.drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(16.dp),
                            surfaceColor = colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(server.config.name, fontWeight = FontWeight.Medium, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Text(
                                    if (server.connected) "已连接" else "未连接",
                                    color = if (server.connected) colorScheme.primary else colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                            Text(server.config.url, color = colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            Text(
                                "传输: ${server.config.transportType.name}  工具数: ${server.tools.size}",
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            if (!server.lastError.isNullOrBlank()) {
                                Text("错误: ${server.lastError}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, com.yunian.ai.domain.TransportType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(com.yunian.ai.domain.TransportType.STREAMABLE_HTTP) }
    var showTransportMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务器", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("服务器 URL") }, singleLine = true,
                    placeholder = { Text("https://example.com/mcp") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = showTransportMenu, onExpandedChange = { showTransportMenu = it }) {
                    OutlinedTextField(
                        value = if (transportType == com.yunian.ai.domain.TransportType.SSE) "SSE" else "Streamable HTTP",
                        onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        label = { Text("传输协议") }
                    )
                    ExposedDropdownMenu(expanded = showTransportMenu, onDismissRequest = { showTransportMenu = false }) {
                        DropdownMenuItem(text = { Text("Streamable HTTP (推荐)") }, onClick = {
                            transportType = com.yunian.ai.domain.TransportType.STREAMABLE_HTTP
                            showTransportMenu = false
                        })
                        DropdownMenuItem(text = { Text("SSE (旧版规范)") }, onClick = {
                            transportType = com.yunian.ai.domain.TransportType.SSE
                            showTransportMenu = false
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onConfirm(name.trim(), url.trim(), transportType) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
