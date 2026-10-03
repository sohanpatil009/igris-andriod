package com.igris.companion.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.igris.companion.permissions.PermissionStatus
import kotlinx.coroutines.delay

/**
 * replica fix4: live permission status instead of a Phase-4 placeholder.
 * Copies travel on their own when the toggles below are green; when one
 * breaks (e.g. HyperOS disables accessibility on update) the banner names it.
 */
@Composable
fun ClipboardTab() {
    val context = LocalContext.current
    var a11yOn by remember { mutableStateOf(true) }
    var overlayOn by remember { mutableStateOf(true) }
    var batteryOk by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            a11yOn = PermissionStatus.accessibilityEnabled(context)
            overlayOn = PermissionStatus.overlayGranted(context)
            batteryOk = PermissionStatus.batteryUnrestricted(context)
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Clipboard", style = MaterialTheme.typography.titleLarge)
        Text(
            "Copies travel on their own. If it stops, the banner names the toggle.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!a11yOn) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "Auto-copy paused: accessibility is off. Re-enable IGRIS in system settings, or use the Sync tile / Share to IGRIS.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (!overlayOn) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "Incoming copies can't land: Display over other apps is denied. PC to phone will look one-way until granted.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (!batteryOk) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    "Battery optimization is on: background sync may pause with the screen off. Exempt IGRIS for steady sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (a11yOn && overlayOn && batteryOk) {
            Text(
                "All green: background copy, incoming paste and keep-alive are on. Password-manager clips stay on this device by design.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
