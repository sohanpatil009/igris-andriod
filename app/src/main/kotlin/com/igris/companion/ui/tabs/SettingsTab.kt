package com.igris.companion.ui.tabs

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.igris.companion.identity.IdentityStore
import com.igris.companion.permissions.PermissionStatus
import com.igris.companion.power.OemPower
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    var deviceId by remember { mutableStateOf("") }
    var a11yEnabled by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        deviceId = withContext(Dispatchers.IO) { IdentityStore.get(context).deviceId }
        while (true) {
            a11yEnabled = PermissionStatus.accessibilityEnabled(context)
            overlayGranted = PermissionStatus.overlayGranted(context)
            batteryUnrestricted = PermissionStatus.batteryUnrestricted(context)
            delay(2000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(
            "Device ID (lowercase UUID, persisted in DataStore):",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            deviceId.ifEmpty { "loading…" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            "Background clipboard sync",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Android 10+ blocks clipboard reads while IGRIS is not in focus. " +
                "Enable the accessibility service to detect copies made in any app " +
                "and sync them to your linked devices.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                )
            }) {
                Text(if (a11yEnabled) "Accessibility: ON (tap to open)" else "Enable accessibility")
            }
        }

        Text(
            "Receiving clipboard from your other devices",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Without \"display over other apps\", IGRIS can read and send your " +
                "clipboard fine, but can't apply clipboard content pushed *from* your " +
                "PC or other devices while running in the background — sync will look " +
                "like it only works one way.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Button(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) {
                Text(
                    if (overlayGranted) "Display over other apps: ON"
                    else "Enable display over other apps"
                )
            }
        }

        Text(
            "Keep running in background",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Aggressive battery management on many phones (MIUI/HyperOS, ColorOS, " +
                "Funtouch/OriginOS, OxygenOS, One UI, EMUI …) can freeze IGRIS after the " +
                "screen turns off. Grant both of these so sync keeps working while your " +
                "devices stay linked.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Button(onClick = { OemPower.requestBatteryUnrestricted(context) }) {
                Text(
                    if (batteryUnrestricted) "Battery: unrestricted ✓"
                    else "Allow unrestricted battery"
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Button(onClick = { OemPower.openAutostart(context) }) {
                Text(
                    if (OemPower.hasOemAutostart()) "Enable autostart (${OemPower.oemLabel()})"
                    else "Open app settings"
                )
            }
        }
        Text(
            "Then add IGRIS to your phone's protected / auto-launch / never-sleeping " +
                "app list, and lock it in Recents if your launcher supports it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
