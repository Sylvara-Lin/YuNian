package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.common.BatteryOptimizationHelper
import com.yunian.ai.common.NativePermissionRequester
import com.yunian.ai.common.OriginOSBatteryOptimizer
import com.yunian.ai.common.RomUtils
import kotlinx.coroutines.delay
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginOSAdaptionScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors

    var refreshTick by remember { mutableStateOf(0) }

    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    GlassPageScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.originos_adaption_title),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
            ) {
                DeviceInfoCard()
            }

            Spacer(modifier = Modifier.height(12.dp))

            AdaptionItemsGroup(isVisible, refreshTick)

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(tween(400, delayMillis = 200)) { it / 4 }
            ) {
                GuideTextCard()
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun DeviceInfoCard() {
    val colorScheme = AppTheme.colors
    val isOriginOS6 = RomUtils.isVivo && RomUtils.isOriginOS6OrAbove()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.ShieldCheck,
                null,
                Modifier.size(28.dp),
                tint = if (isOriginOS6) AppTheme.colors.primary else colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = RomUtils.getRomDisplayName(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
                Text(
                    text = if (isOriginOS6) {
                        stringResource(R.string.originos_adaption_detected_6)
                    } else {
                        stringResource(R.string.originos_adaption_detected_other)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdaptionItemsGroup(isVisible: Boolean, refreshTick: Int) {
    val colorScheme = AppTheme.colors
    val context = LocalContext.current

    val states = remember(refreshTick) { computeAdaptionStates(context) }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = 80)) + slideInVertically(tween(400, delayMillis = 80)) { it / 4 }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = colorScheme.surfaceVariant
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            states.forEachIndexed { index, item ->
                AdaptionItemRow(item)
                if (index < states.size - 1) {
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(start = 44.dp)
                            .height(0.5.dp)
                            .background(colorScheme.outline)
                    )
                }
            }
        }
    }
}

private data class AdaptionItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val configured: Boolean,
    val action: () -> Unit
)

private fun computeAdaptionStates(context: android.content.Context): List<AdaptionItem> {
    return buildList {

        add(
            AdaptionItem(
                icon = AppIcons.Bell,
                title = context.getString(R.string.originos_adaption_notification),
                subtitle = context.getString(R.string.originos_adaption_notification_desc),
                configured = NativePermissionRequester.hasAllRequiredPermissions(context),
                action = { BatteryOptimizationHelper.openNotificationSettings(context) }
            )
        )

        add(
            AdaptionItem(
                icon = AppIcons.BatteryFull,
                title = context.getString(R.string.originos_adaption_battery),
                subtitle = context.getString(R.string.originos_adaption_battery_desc),
                configured = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context),
                action = {

                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                }
            )
        )

        add(
            AdaptionItem(
                icon = AppIcons.Power,
                title = context.getString(R.string.originos_adaption_autostart),
                subtitle = context.getString(R.string.originos_adaption_autostart_desc),

                configured = false,
                action = { BatteryOptimizationHelper.openAutoStartSettings(context) }
            )
        )

        add(
            AdaptionItem(
                icon = AppIcons.Zap,
                title = context.getString(R.string.originos_adaption_background_power),
                subtitle = context.getString(R.string.originos_adaption_background_power_desc),
                configured = false,
                action = { OriginOSBatteryOptimizer.openBackgroundPowerSettings(context) }
            )
        )

        add(
            AdaptionItem(
                icon = AppIcons.Clock,
                title = context.getString(R.string.originos_adaption_popup),
                subtitle = context.getString(R.string.originos_adaption_popup_desc),
                configured = false,
                action = { OriginOSBatteryOptimizer.openBackgroundPopupSettings(context) }
            )
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            add(
                AdaptionItem(
                    icon = AppIcons.AlarmClock,
                    title = context.getString(R.string.originos_adaption_exact_alarm),
                    subtitle = context.getString(R.string.originos_adaption_exact_alarm_desc),
                    configured = alarmManager.canScheduleExactAlarms(),
                    action = {
                        runCatching {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {

                            NativePermissionRequester.openAppDetailsSettings(context)
                        }
                    }
                )
            )
        }
    }
}

@Composable
private fun AdaptionItemRow(item: AdaptionItem) {
    val colorScheme = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = item.action).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, item.title, Modifier.size(24.dp), tint = AppTheme.colors.onSurface)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
                    color = colorScheme.onSurface
                )
                if (item.configured) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        AppIcons.CircleCheckBig,
                        null,
                        Modifier.size(16.dp),
                        tint = AppTheme.colors.primary
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colorScheme.onSurfaceVariant
            )
        }
        Icon(
            AppIcons.ChevronRight,
            null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun GuideTextCard() {
    val colorScheme = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, null, Modifier.size(20.dp), tint = colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.originos_adaption_guide_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            OriginOSBatteryOptimizer.getBatteryOptimizationGuideText(),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
            color = colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.originos_adaption_guide_footer),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colorScheme.onSurfaceVariant
        )
    }
}
