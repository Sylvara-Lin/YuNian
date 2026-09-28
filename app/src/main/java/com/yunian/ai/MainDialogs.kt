package com.yunian.ai

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.yunian.ai.R
import com.yunian.ai.common.BatteryOptimizationHelper

@Composable
fun MainScreenDialogs(
    onAutoStartSettings: () -> Unit = {},
    onBatterySettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }

    var showAutoStart by remember { mutableStateOf(false) }
    var showBattery by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val autoStartShown = prefs.getBoolean("auto_start_dialog_shown", false)
        val batteryShown = prefs.getBoolean("battery_dialog_shown", false)

        if (!autoStartShown) showAutoStart = true
        if (!batteryShown && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
            showBattery = true
        }
    }

    if (showAutoStart) {
        AlertDialog(
            onDismissRequest = { showAutoStart = false },
            title = { Text(stringResource(R.string.dialog_auto_start_title)) },
            text = { Text(stringResource(R.string.dialog_auto_start_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutoStart = false
                    prefs.edit { putBoolean("auto_start_dialog_shown", true) }
                    onAutoStartSettings()
                }) {
                    Text(stringResource(R.string.dialog_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAutoStart = false
                    prefs.edit { putBoolean("auto_start_dialog_shown", true) }
                }) {
                    Text(stringResource(R.string.dialog_later))
                }
            }
        )
    }

    if (showBattery) {
        AlertDialog(
            onDismissRequest = { showBattery = false },
            title = { Text(stringResource(R.string.dialog_battery_title)) },
            text = { Text(stringResource(R.string.dialog_battery_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showBattery = false
                    prefs.edit { putBoolean("battery_dialog_shown", true) }
                    onBatterySettings()
                }) {
                    Text(stringResource(R.string.dialog_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBattery = false
                    prefs.edit { putBoolean("battery_dialog_shown", true) }
                }) {
                    Text(stringResource(R.string.dialog_later))
                }
            }
        )
    }
}
