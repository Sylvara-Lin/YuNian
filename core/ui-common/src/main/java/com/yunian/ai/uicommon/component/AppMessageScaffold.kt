package com.yunian.ai.uicommon.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun AppMessageScaffold(
    frame: @Composable (menuExpanded: Boolean, onLongClick: () -> Unit) -> Unit,
    menu: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        frame(showMenu) { showMenu = true }
        menu(showMenu) { showMenu = false }
    }
}
