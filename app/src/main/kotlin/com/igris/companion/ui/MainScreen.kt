package com.igris.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.igris.companion.ui.tabs.ClipboardTab
import com.igris.companion.ui.tabs.DevicesTab
import com.igris.companion.ui.tabs.NotificationsTab
import com.igris.companion.ui.tabs.SettingsTab
import com.igris.companion.ui.tabs.TransfersTab

private data class TabSpec(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabSpec("Devices", Icons.Filled.Home),
    TabSpec("Clipboard", Icons.Filled.Create),
    TabSpec("Notifications", Icons.Filled.Notifications),
    TabSpec("Transfers", Icons.AutoMirrored.Filled.Send),
    TabSpec("Settings", Icons.Filled.Settings),
)

@Composable
fun MainScreen() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, spec ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(spec.icon, contentDescription = spec.label) },
                        label = { Text(spec.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> DevicesTab()
                1 -> ClipboardTab()
                2 -> NotificationsTab()
                3 -> TransfersTab()
                else -> SettingsTab()
            }
        }
    }
}
