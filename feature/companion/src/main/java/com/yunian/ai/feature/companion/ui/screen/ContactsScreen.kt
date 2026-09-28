package com.yunian.ai.feature.companion.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import android.icu.text.Transliterator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.companion.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.database.model.ChatGroup
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.viewmodel.ChatGroupViewModel
import com.yunian.ai.database.viewmodel.CompanionListViewModel
import com.yunian.ai.uicommon.component.AppListItemLayout
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.theme.AppTheme
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.launch
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@Composable
fun ContactsScreen(
    onCompanionClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    onCreateGroupClick: () -> Unit,
    viewModel: CompanionListViewModel = viewModel(),
    groupViewModel: ChatGroupViewModel = viewModel(),
    groups: List<ChatGroup>? = null,
    isVisible: Boolean = true
) {
    val companions by viewModel.companions.collectAsState()
    val observedGroups by groupViewModel.groups.collectAsState()
    val groups = groups ?: observedGroups
    val colorScheme = AppTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val visibleCompanions = remember(companions, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) companions else companions.filter {
            it.name.contains(query, ignoreCase = true)
        }
    }
    val backdrop = LocalPageBackdrop.current
    val sortedCompanions = remember(visibleCompanions) {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
        visibleCompanions.sortedWith(compareBy(collator) { it.name })
    }
    val groupedCompanions = remember(sortedCompanions) {
        sortedCompanions.groupBy { getSectionKey(it.name) }
    }
    val sectionKeys = remember(groupedCompanions) {
        groupedCompanions.keys.sorted()
    }

    val sectionIndexMap = remember(groups, groupedCompanions, sectionKeys) {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        if (groups.isNotEmpty()) {
            idx += groups.size + 2
        }
        idx += 1
        for (key in sectionKeys) {
            map[key] = idx
            idx += groupedCompanions[key].orEmpty().size + 1
        }
        map
    }

    GlassPageScaffold(
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()

                .background(Color.Transparent)
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f))

                if (isSearching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        placeholder = { Text("搜索好友") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                } else {
                    Text(
                        text = "通讯录",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = colorScheme.onSurface
                    )
                }

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(
                            onClick = {
                                isSearching = !isSearching
                                if (!isSearching) searchQuery = ""
                            },
                            backdrop = backdrop,
                            height = 36.dp,
                            horizontalPadding = 0.dp,
                            modifier = Modifier.size(36.dp),
                            surfaceColor = colorScheme.surfaceVariant.copy(alpha = 0.85f)
                        ) {
                            Icon(
                                imageVector = if (isSearching) AppIcons.X else AppIcons.Search,
                                contentDescription = if (isSearching) "关闭搜索" else "搜索好友",
                                modifier = Modifier.size(20.dp),
                                tint = colorScheme.onSurface
                            )
                        }
                        GlassButton(
                            onClick = { onAddClick() },
                            backdrop = backdrop,
                            height = 36.dp,
                            horizontalPadding = 0.dp,
                            modifier = Modifier.size(36.dp),
                            surfaceColor = colorScheme.surfaceVariant.copy(alpha = 0.85f)
                        ) {
                            Icon(
                                imageVector = AppIcons.UserPlus,
                                contentDescription = "添加好友",
                                modifier = Modifier.size(20.dp),
                                tint = colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("contacts_friend_list_ready"),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (groups.isNotEmpty()) {
                        item(key = "groups_header") {
                            Text(
                                text = stringResource(R.string.group_chat),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        itemsIndexed(groups, key = { _, g -> "group_${g.id}" }) { _, group ->
                            GroupContactItem(
                                group = group,
                                onClick = { onGroupClick(group.id) }
                            )
                        }
                        item(key = "groups_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                    }

                    if (companions.isNotEmpty()) {
                        item(key = "friends_header") {
                            Text(
                                text = stringResource(R.string.friends),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }

                        sectionKeys.forEach { key ->
                            val itemsInSection = groupedCompanions[key] ?: return@forEach
                            item(key = "section_$key") {
                                Text(
                                    text = key,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colorScheme.primary.copy(alpha = 0.08f))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            itemsIndexed(itemsInSection, key = { _, c -> "companion_${c.id}" }) { _, companion ->
                                ContactItem(
                                    companion = companion,
                                    onClick = { onCompanionClick(companion.id) },
                                    onLongClick = { onEditClick(companion.id) }
                                )
                            }
                        }
                    }

                    if (companions.isEmpty() && groups.isEmpty()) {
                        item(key = "empty_contacts") { EmptyContactsState() }
                    } else if (isSearching && visibleCompanions.isEmpty()) {
                        item(key = "empty_search") { EmptyContactsState() }
                    }
                }

                if (sectionKeys.size > 1) {
                    AlphabetSidebar(
                        letters = sectionKeys,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onLetterClick = { letter ->
                            sectionIndexMap[letter]?.let { index ->
                                scope.launch {
                                    listState.animateScrollToItem(index)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun GroupContactItem(
    group: ChatGroup,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (group.avatarUrl != null) {
                    AsyncImage(
                        model = group.avatarUrl,
                        contentDescription = group.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Users,
                        contentDescription = null,
                        tint = colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = ContinuousCapsule,
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        onClick = onClick,
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = group.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.people_count, group.getCompanionIdList().size),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ContactItem(
    companion: CompanionEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colorScheme = AppTheme.colors

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface),
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
                    Icon(
                        imageVector = AppIcons.User,
                        contentDescription = null,
                        tint = AppTheme.colors.captionContent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = ContinuousCapsule,
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        onClick = onClick,
        onLongClick = onLongClick,
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Text(
            text = companion.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun EmptyContactsState() {
    val colorScheme = AppTheme.colors

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.User,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_contacts),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.add_hint),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getSectionKey(name: String): String {
     val normalized = PINYIN_TRANSLITERATOR.transliterate(name.trim())
     val first = normalized.firstOrNull() ?: return "#"
    return when {
        first in 'A'..'Z' || first in 'a'..'z' -> first.uppercase()
        first in '0'..'9' -> "#"
        else -> "#"
    }
}

private val PINYIN_TRANSLITERATOR: Transliterator by lazy {
    Transliterator.getInstance("Han-Latin; Latin-ASCII")
}

@Composable
private fun AlphabetSidebar(
    letters: List<String>,
    modifier: Modifier = Modifier,
    onLetterClick: (String) -> Unit
) {
    val colorScheme = AppTheme.colors

    Column(
        modifier = modifier
            .padding(end = 2.dp)
            .pointerInput(letters) {
                detectTapGestures { offset ->
                    val letterHeight = size.height.toFloat() / letters.size
                    val index = (offset.y / letterHeight).toInt().coerceIn(0, letters.size - 1)
                    onLetterClick(letters[index])
                }
            },
        verticalArrangement = Arrangement.Center
    ) {
        letters.forEach { letter ->
            Text(
                text = letter,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 1.dp, horizontal = 4.dp)
            )
        }
    }
}
