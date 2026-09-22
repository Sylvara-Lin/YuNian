package com.yunian.ai.feature.companion.ui.screen
import android.widget.Toast
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.companion.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.feature.companion.ui.viewmodel.CreateCompanionViewModel
import com.yunian.ai.uicommon.image.cropper.ImageCropperDialog
import com.yunian.ai.uicommon.image.decodeUriSampledForCrop
import com.yunian.ai.uicommon.image.viewer.FullscreenImageViewer
import com.yunian.ai.uicommon.picker.ui.CustomImagePicker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CreateCompanionScreen(
    companionId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: CreateCompanionViewModel = viewModel()
) {
    val context = LocalContext.current
    val isEditMode = companionId != null
    val existingCompanion by viewModel.existingCompanion.collectAsState()
    val globalRole by viewModel.selectedRole.collectAsState()

    var role by remember { mutableStateOf(globalRole) }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var bodyType by remember { mutableStateOf("") }
    var profession by remember { mutableStateOf("") }
    var selectedPersonalityTags by remember { mutableStateOf(listOf<String>()) }
    var aiGeneratedTags by remember { mutableStateOf(listOf<String>()) }
    var rawPrompt by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showImportErrorDialog by remember { mutableStateOf(false) }
    var importErrorMessage by remember { mutableStateOf("") }
    var referenceCharacter by remember { mutableStateOf("") }

    // API 隔离：该角色绑定的专属 API 配置；null = 跟随全局启用配置
    val apiConfigs by viewModel.apiConfigs.collectAsState()
    var selectedApiConfigId by remember { mutableStateOf<Long?>(null) }

    // 世界书绑定：勾选该角色生效的全局世界书；空集合 = 沿用旧行为（全部全局书自动生效）
    var selectedLorebookIds by remember { mutableStateOf(setOf<Long>()) }
    var globalLorebooks by remember { mutableStateOf(listOf<com.yunian.ai.domain.Lorebook>()) }

    var showAvatarPicker by remember { mutableStateOf(false) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewAvatarModel by remember { mutableStateOf<Any?>(null) }
    val isGenerating by viewModel.isGenerating.collectAsState()

    val saveCompleted by viewModel.saveCompleted.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    LaunchedEffect(saveCompleted) {
        if (saveCompleted) {
            viewModel.resetSaveCompleted()
            onNavigateBack()
        }
    }

    LaunchedEffect(saveError) {
        saveError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeSaveError()
        }
    }

    fun populateExistingCompanion(companion: CompanionEntity) {
        name = companion.name
        age = companion.age?.toString().orEmpty()
        rawPrompt = companion.rawPrompt.orEmpty()
        systemPrompt = companion.systemPrompt.orEmpty()
        avatarUri = companion.avatarUrl
        selectedApiConfigId = companion.apiConfigId
        selectedLorebookIds = runCatching {
            Json.decodeFromString(ListSerializer(Long.serializer()), companion.lorebookIdsJson).toSet()
        }.getOrDefault(emptySet())
    }

    fun handleImportError(message: String) {
        importErrorMessage = message
        showImportErrorDialog = true
    }

    fun updateRawPromptFromImport(content: String) {
        rawPrompt = content
    }

    fun handleGenerateResult(result: String) {
        if (result.isNotBlank()) rawPrompt = result
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            try {
                context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { reader ->
                    val content = reader?.readText().orEmpty()
                    if (content.isNotBlank()) {
                        updateRawPromptFromImport(content)
                    } else {
                        handleImportError("文件内容为空")
                    }
                }
            } catch (e: Exception) {
                handleImportError("读取文件失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (isEditMode) {
            viewModel.loadCompanion(companionId!!)
        }
        withContext(Dispatchers.IO) { viewModel.loadApiConfigs() }
        delay(100)
        isVisible = true
    }

    LaunchedEffect(existingCompanion) {
        existingCompanion?.let(::populateExistingCompanion)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val provider = com.yunian.ai.domain.ServiceRegistry.get(com.yunian.ai.domain.LorebookProvider::class.java)
            val books = provider?.getAllLorebooks().orEmpty().filter { it.companionId == null && it.enabled }
            kotlinx.coroutines.withContext(Dispatchers.Main) { globalLorebooks = books }
        }
    }

    LaunchedEffect(globalRole) {
        if (!isEditMode && existingCompanion == null) {
            role = globalRole
        }
    }

    val isFormValid = name.isNotBlank() && rawPrompt.isNotBlank()
    val buttonScale by animateFloatAsState(
        targetValue = if (isFormValid) 1f else 0.95f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "buttonScale"
    )

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = with(density) { imeInsets.getBottom(density) > 0 }

    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val colorScheme = AppTheme.colors
    val accentColor = when (role) {
        CompanionRole.GIRLFRIEND -> Color(0xFFFF6B9D)
        CompanionRole.BOYFRIEND -> Color(0xFF4A90E2)
    }
    val accentGradient = when (role) {
        CompanionRole.GIRLFRIEND -> listOf(
            Color(0xFFFF6B9D).copy(alpha = 0.9f),
            Color(0xFFFF8FB3).copy(alpha = 0.8f)
        )
        CompanionRole.BOYFRIEND -> listOf(
            Color(0xFF4A90E2).copy(alpha = 0.9f),
            Color(0xFF6BA5E7).copy(alpha = 0.8f)
        )
    }
    val roleIcon: ImageVector = when (role) {
        CompanionRole.GIRLFRIEND -> AppIcons.Heart
        CompanionRole.BOYFRIEND -> AppIcons.Shield
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = when {
                    isEditMode && role == CompanionRole.BOYFRIEND -> stringResource(R.string.edit_companion_boyfriend)
                    isEditMode -> stringResource(R.string.edit_companion_girlfriend)
                    role == CompanionRole.BOYFRIEND -> stringResource(R.string.create_companion_boyfriend)
                    else -> stringResource(R.string.create_companion_girlfriend)
                },
                onBack = onNavigateBack,
                actions = {
                    if (isEditMode) {
                        IconButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.Trash2,
                                contentDescription = stringResource(R.string.delete),
                                tint = AppTheme.colors.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            accentColor.copy(alpha = 0.8f),
                                            accentColor.copy(alpha = 0.5f)
                                        )
                                    )
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (avatarUri != null) {

                                            previewAvatarModel = avatarUri
                                        } else {

                                            showAvatarPicker = true
                                        }
                                    },
                                    onLongClick = {

                                        showAvatarPicker = true
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarUri != null) {
                                AsyncImage(
                                    model = avatarUri,
                                    contentDescription = stringResource(R.string.add_avatar),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = roleIcon,
                                        contentDescription = stringResource(R.string.add_avatar),
                                        tint = AppTheme.colors.staticWhite.copy(alpha = 0.9f),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.select_avatar),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp
                                        ),
                                        color = AppTheme.colors.staticWhite.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }

                        if (avatarUri != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击查看 · 长按更换",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.clickable { showAvatarPicker = true }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (name.isBlank()) {
                                when (role) {
                                    CompanionRole.GIRLFRIEND -> stringResource(R.string.name_hint_girlfriend)
                                    CompanionRole.BOYFRIEND -> stringResource(R.string.name_hint_boyfriend)
                                }
                            } else name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = if (name.isBlank()) colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else colorScheme.onSurface
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 80)) +
                            slideInVertically(tween(400, delayMillis = 80)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = colorScheme.surfaceVariant),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.role_type),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                ),
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RoleToggleChip(
                                    role = CompanionRole.GIRLFRIEND,
                                    icon = AppIcons.Heart,
                                    label = stringResource(R.string.role_girlfriend),
                                    accentColor = Color(0xFFFF6B9D),
                                    selected = role == CompanionRole.GIRLFRIEND,
                                    onClick = { role = CompanionRole.GIRLFRIEND },
                                    modifier = Modifier.weight(1f)
                                )
                                RoleToggleChip(
                                    role = CompanionRole.BOYFRIEND,
                                    icon = AppIcons.Shield,
                                    label = stringResource(R.string.role_boyfriend),
                                    accentColor = Color(0xFF4A90E2),
                                    selected = role == CompanionRole.BOYFRIEND,
                                    onClick = { role = CompanionRole.BOYFRIEND },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 100,
                    label = stringResource(R.string.name_label),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = when (role) {
                        CompanionRole.GIRLFRIEND -> stringResource(R.string.name_placeholder_girlfriend)
                        CompanionRole.BOYFRIEND -> stringResource(R.string.name_placeholder_boyfriend)
                    },
                    imeAction = ImeAction.Next,
                    accentColor = accentColor
                )

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 130,
                    label = stringResource(R.string.age_label),
                    value = age,
                    onValueChange = { age = it },
                    placeholder = stringResource(R.string.age_placeholder),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                    accentColor = accentColor
                )

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 160,
                    label = stringResource(R.string.body_type_label),
                    value = bodyType,
                    onValueChange = { bodyType = it },
                    placeholder = when (role) {
                        CompanionRole.GIRLFRIEND -> stringResource(R.string.body_type_placeholder_girlfriend)
                        CompanionRole.BOYFRIEND -> stringResource(R.string.body_type_placeholder_boyfriend)
                    },
                    imeAction = ImeAction.Next,
                    accentColor = accentColor,
                    suggestionChips = viewModel.bodyTypeSuggestions(role),
                    onSuggestionClick = { bodyType = it }
                )

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 190,
                    label = stringResource(R.string.profession_label),
                    value = profession,
                    onValueChange = { profession = it },
                    placeholder = when (role) {
                        CompanionRole.GIRLFRIEND -> stringResource(R.string.profession_placeholder_girlfriend)
                        CompanionRole.BOYFRIEND -> stringResource(R.string.profession_placeholder_boyfriend)
                    },
                    imeAction = ImeAction.Next,
                    accentColor = accentColor,
                    suggestionChips = viewModel.professionSuggestions(role),
                    onSuggestionClick = { profession = it }
                )

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 220)) +
                            slideInVertically(tween(400, delayMillis = 220)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = colorScheme.surfaceVariant),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.personality_tags_label),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                ),
                                color = colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.personality_tags_hint),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                (viewModel.personalityTags(role) + aiGeneratedTags).distinct().forEach { tag ->
                                    val selected = selectedPersonalityTags.contains(tag)
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            selectedPersonalityTags = if (selected) {
                                                selectedPersonalityTags - tag
                                            } else {
                                                selectedPersonalityTags + tag
                                            }
                                        },
                                        label = {
                                            Text(
                                                tag,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 12.sp
                                                )
                                            )
                                        },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = AppIcons.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = accentColor.copy(alpha = 0.15f),
                                            selectedLabelColor = accentColor,
                                            selectedLeadingIconColor = accentColor,
                                            containerColor = colorScheme.surface.copy(alpha = 0.5f)
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = selected,
                                            borderColor = if (selected) accentColor else colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 250)) +
                            slideInVertically(tween(400, delayMillis = 250)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = colorScheme.surfaceVariant),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.role_setting),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    ),
                                    color = colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = !isGenerating) {
                                                val effectiveName = name.trim().takeIf { it.isNotBlank() }
                                                    ?: referenceCharacter.trim().takeIf { it.isNotBlank() }
                                                    ?: "未知角色"
                                                viewModel.generatePersonaByAi(
                                                    name = effectiveName,
                                                    role = role,
                                                    referenceCharacter = referenceCharacter.trim().takeIf { it.isNotBlank() && it != effectiveName },
                                                    bodyType = bodyType.trim().takeIf { it.isNotBlank() },
                                                    profession = profession.trim().takeIf { it.isNotBlank() },
                                                    personalityTags = selectedPersonalityTags,
                                                    onResult = { draft ->
                                                        // 一次生成、全字段填好：只填空位，不覆盖用户已输入的内容
                                                        if (!draft.name.isNullOrBlank() && name.isBlank()) name = draft.name
                                                        if (!draft.age.isNullOrBlank() && age.isBlank()) age = draft.age
                                                        if (!draft.bodyType.isNullOrBlank() && bodyType.isBlank()) bodyType = draft.bodyType
                                                        if (!draft.profession.isNullOrBlank() && profession.isBlank()) profession = draft.profession
                                                        if (draft.personalityTags.isNotEmpty()) {
                                                            aiGeneratedTags = draft.personalityTags
                                                            selectedPersonalityTags = draft.personalityTags
                                                        }
                                                        if (draft.persona.isNotBlank()) {
                                                            rawPrompt = draft.persona
                                                        }
                                                    },
                                                    onError = { msg ->
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isGenerating) AppIcons.Pencil else AppIcons.Sparkles,
                                            contentDescription = "AI生成设定",
                                            tint = if (isGenerating) accentColor.copy(alpha = 0.5f)
                                            else accentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (isGenerating) stringResource(R.string.ai_generating) else stringResource(R.string.ai_generate),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = if (isGenerating) accentColor.copy(alpha = 0.5f)
                                            else accentColor
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { filePicker.launch("*/*") }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.FolderOpen,
                                            contentDescription = stringResource(R.string.import_from_file),
                                            tint = accentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.import_from_file),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp
                                            ),
                                            color = accentColor
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = referenceCharacter,
                                onValueChange = { referenceCharacter = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentColor.copy(alpha = 0.3f),
                                    unfocusedBorderColor = colorScheme.outline.copy(alpha = 0.5f),
                                    focusedContainerColor = colorScheme.surface.copy(alpha = 0.3f),
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = colorScheme.onSurfaceVariant,
                                    unfocusedTextColor = colorScheme.onSurfaceVariant
                                ),
                                placeholder = {
                                    Text(
                                        when (role) {
                                            CompanionRole.GIRLFRIEND -> stringResource(R.string.reference_character)
                                            CompanionRole.BOYFRIEND -> stringResource(R.string.reference_character_boyfriend)
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when (role) {
                                    CompanionRole.GIRLFRIEND -> stringResource(R.string.role_hint_girlfriend)
                                    CompanionRole.BOYFRIEND -> stringResource(R.string.role_hint_boyfriend)
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp
                                ),
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = rawPrompt,
                                onValueChange = { rawPrompt = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentColor.copy(alpha = 0.4f),
                                    unfocusedBorderColor = colorScheme.outline,
                                    focusedLabelColor = accentColor.copy(alpha = 0.6f),
                                    unfocusedLabelColor = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    focusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    unfocusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    focusedTextColor = colorScheme.onSurface,
                                    unfocusedTextColor = colorScheme.onSurface
                                ),
                                minLines = 6,
                                maxLines = 10,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp
                                )
                            )
                        }
                    }
                }

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 300,
                    label = stringResource(R.string.system_prompt),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = stringResource(R.string.system_prompt_hint),
                    imeAction = ImeAction.Done,
                    minLines = 3,
                    accentColor = accentColor
                )

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 320)) +
                            slideInVertically(tween(500, delayMillis = 320)) { it / 2 }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(14.dp),
                                surfaceColor = colorScheme.surfaceVariant
                            )
                            .padding(14.dp)
                    ) {
                        Text(
                            "世界书绑定",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                            color = colorScheme.onSurface
                        )
                        Text(
                            "不勾选时，所有全局世界书均对该角色生效；勾选后仅生效勾选的世界书",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (globalLorebooks.isEmpty()) {
                            Text(
                                "暂无全局世界书。绑定仅对「全局世界书」生效：可先在「世界书」页面创建全局世界书，再回到这里为该角色挑选生效的书目；角色专属世界书无需在此绑定。",
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        globalLorebooks.forEach { book ->
                            val checked = selectedLorebookIds.contains(book.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedLorebookIds = if (checked) {
                                            selectedLorebookIds - book.id
                                        } else {
                                            selectedLorebookIds + book.id
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { c ->
                                    selectedLorebookIds = if (c) selectedLorebookIds + book.id
                                    else selectedLorebookIds - book.id
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(book.name, fontSize = 13.sp, color = colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (book.description.isNotBlank()) {
                                        Text(
                                            book.description, fontSize = 11.sp,
                                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 330)) +
                            slideInVertically(tween(500, delayMillis = 330)) { it / 2 }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(14.dp),
                                surfaceColor = colorScheme.surfaceVariant
                            )
                            .padding(14.dp)
                    ) {
                        Text(
                            "API 隔离",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                            color = colorScheme.onSurface
                        )
                        Text(
                            "为该角色指定专属 API 配置（独立 Key），避免多角色共用同一 Key 导致的人设串台；不选择则使用全局启用的配置",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedApiConfigId = null }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedApiConfigId == null,
                                onClick = { selectedApiConfigId = null }
                            )
                            Column(Modifier.weight(1f)) {
                                Text("跟随全局设置", fontSize = 13.sp, color = colorScheme.onSurface)
                                Text(
                                    "使用「API 设置」中当前启用的配置",
                                    fontSize = 11.sp,
                                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        apiConfigs.forEach { config ->
                            val boundId = config.id
                            val checked = selectedApiConfigId == boundId
                            val displayKey = config.apiKey.takeIf { it.isNotBlank() }?.let { key ->
                                if (key.length > 10) "${key.take(6)}****${key.takeLast(4)}" else "****"
                            } ?: "远程密钥"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedApiConfigId = boundId }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = checked,
                                    onClick = { selectedApiConfigId = boundId }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        config.name.ifBlank { config.provider.displayName },
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${config.model.ifBlank { "自动选择" }} · $displayKey",
                                        fontSize = 11.sp,
                                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (apiConfigs.isEmpty()) {
                            Text(
                                "暂无其他 API 配置。可先在「我 → API设置」添加多个配置，再回来为不同角色分配不同 Key",
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 350)) +
                            slideInVertically(tween(500, delayMillis = 350)) { it / 2 }
                ) {
                    Button(
                        onClick = {
                            if (isFormValid) {
                                val companion = CompanionEntity(
                                    id = companionId ?: 0,
                                    name = name.trim(),
                                    avatarUrl = avatarUri,
                                    age = age.toIntOrNull(),
                                    personality = rawPrompt.trim(),
                                    backstory = null,
                                    speakingStyle = null,
                                    tags = null,
                                    rawPrompt = rawPrompt.trim(),
                                    systemPrompt = systemPrompt.trim().takeIf { it.isNotBlank() },
                                    // 编辑模式保留已有字段，避免整行更新时被默认值重置
                                    intimacy = existingCompanion?.intimacy ?: 0,
                                    lorebookIdsJson = Json.encodeToString(ListSerializer(Long.serializer()), selectedLorebookIds.toList()),
                                    apiConfigId = selectedApiConfigId,
                                    createdAt = existingCompanion?.createdAt ?: System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis()
                                )
                                viewModel.saveCompanion(companion, isEditMode)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .scale(buttonScale),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = AppTheme.colors.staticWhite,
                            disabledContainerColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.2f),
                            disabledContentColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        enabled = isFormValid && !isSaving,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isFormValid && !isSaving) {
                                        Brush.horizontalGradient(colors = accentGradient)
                                    } else {
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                AppTheme.colors.onSurfaceVariant.copy(alpha = 0.2f),
                                                AppTheme.colors.onSurfaceVariant.copy(alpha = 0.1f)
                                            )
                                        )
                                    },
                                    RoundedCornerShape(25.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = AppTheme.colors.staticWhite,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isEditMode) AppIcons.Pencil else roleIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        isSaving -> "保存中..."
                                        isEditMode -> stringResource(R.string.save_changes)
                                        role == CompanionRole.BOYFRIEND -> stringResource(R.string.create_boyfriend)
                                        else -> stringResource(R.string.create_girlfriend)
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    stringResource(R.string.confirm_delete),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    stringResource(R.string.delete_confirm_msg, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        existingCompanion?.let {
                            viewModel.deleteCompanion(it)
                        }
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel), color = colorScheme.onSurfaceVariant)
                }
            },
            containerColor = colorScheme.surfaceVariant
        )
    }

    if (showImportErrorDialog) {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog = false },
            title = {
                Text(
                    stringResource(R.string.import_failed),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = colorScheme.onSurface
                )
            },
            text = {
                Text(
                    importErrorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showImportErrorDialog = false }) {
                    Text(stringResource(R.string.ok), color = accentColor)
                }
            },
            containerColor = colorScheme.surfaceVariant
        )
    }

    if (showAvatarPicker) {
        CustomImagePicker(
            maxSelection = 1,
            onConfirmed = { uris ->
                showAvatarPicker = false
                if (uris.isNotEmpty()) {
                    pendingCropUri = uris.first()
                }
            },
            onDismiss = { showAvatarPicker = false }
        )
    }

    LaunchedEffect(pendingCropUri) {
        val uri = pendingCropUri ?: return@LaunchedEffect
        // 采样解码 + OOM 兜底（修 FIX-1）：全尺寸 1080×2400 截图解码即 ≈10MB，再进裁剪峰值翻倍，
        // MIUI/MTK 机型易 OOM 闪退（OOM 是 Error，原 catch(Exception) 抓不到）。
        cropBitmap = withContext(Dispatchers.IO) {
            decodeUriSampledForCrop(context, uri)?.asImageBitmap()
        }
        if (cropBitmap == null) {
            pendingCropUri = null
            android.widget.Toast.makeText(context, "图片加载失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (cropBitmap != null) {
        ImageCropperDialog(
            bitmap = cropBitmap!!,
            cropRatio = 1f,
            onConfirm = { cropped ->
                try {
                    val avatarsDir = File(context.filesDir, "avatars").apply {
                        if (!exists()) mkdirs()
                    }
                    val outFile = File(avatarsDir, "avatar_${UUID.randomUUID()}.jpg")
                    outFile.outputStream().use { out ->
                        cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    avatarUri = outFile.absolutePath
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "头像保存失败", android.widget.Toast.LENGTH_SHORT).show()
                }
                cropBitmap = null
                pendingCropUri = null
            },
            onDismiss = {
                cropBitmap = null
                pendingCropUri = null
            }
        )
    }

    FullscreenImageViewer(
        models = listOfNotNull(previewAvatarModel),
        initialIndex = 0,
        visible = previewAvatarModel != null,
        onDismiss = { previewAvatarModel = null }
    )
}

@Composable
private fun RoleToggleChip(
    role: CompanionRole,
    icon: ImageVector,
    label: String,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = AppTheme.colors
    val backgroundColor = if (selected) accentColor.copy(alpha = 0.12f) else colorScheme.surface.copy(alpha = 0.5f)
    val borderColor = if (selected) accentColor else colorScheme.outline.copy(alpha = 0.3f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 14.sp
            ),
            color = if (selected) accentColor else colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimatedFormField(
    visible: Boolean,
    delayMillis: Int,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    minLines: Int = 1,
    accentColor: Color = AppTheme.colors.primary,
    suggestionChips: List<String> = emptyList(),
    onSuggestionClick: ((String) -> Unit)? = null
) {
    val colorScheme = AppTheme.colors

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400, delayMillis = delayMillis)) +
                slideInVertically(tween(400, delayMillis = delayMillis)) { it / 3 }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = colorScheme.surfaceVariant),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp
                            ),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor.copy(alpha = 0.4f),
                        unfocusedBorderColor = colorScheme.outline,
                        focusedLabelColor = accentColor.copy(alpha = 0.6f),
                        unfocusedLabelColor = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        focusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedTextColor = colorScheme.onSurface,
                        unfocusedTextColor = colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    minLines = minLines,
                    maxLines = if (minLines > 1) 5 else 1,
                    singleLine = minLines == 1,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp
                    )
                )

                if (suggestionChips.isNotEmpty() && onSuggestionClick != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestionChips.forEach { chip ->
                            InputChip(
                                selected = value == chip,
                                onClick = { onSuggestionClick(chip) },
                                label = {
                                    Text(
                                        chip,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = accentColor.copy(alpha = 0.12f),
                                    selectedLabelColor = accentColor,
                                    containerColor = colorScheme.surface.copy(alpha = 0.5f)
                                ),
                                border = InputChipDefaults.inputChipBorder(
                                    enabled = true,
                                    selected = value == chip,
                                    borderColor = if (value == chip) accentColor else colorScheme.outline.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
