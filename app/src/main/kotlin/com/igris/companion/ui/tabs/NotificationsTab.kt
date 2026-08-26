package com.igris.companion.ui.tabs

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.igris.companion.node.NodeHolder
import com.igris.companion.permissions.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uniffi.igris_protocol.IncomingNotification

@Composable
fun NotificationsTab() {
    val context = LocalContext.current
    var notifs by remember { mutableStateOf<List<IncomingNotification>>(emptyList()) }
    var listenerOn by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            listenerOn = PermissionStatus.notificationListenerEnabled(context)
            notifs = withContext(Dispatchers.IO) { NodeHolder.node?.getNotifications() ?: emptyList() }
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notifications", style = MaterialTheme.typography.titleLarge)
        Text(
            "Notifications posted on any linked device appear here. Turn on " +
                "notification access to export this phone's notifications.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (!listenerOn) {
            Row(Modifier.padding(top = 12.dp)) {
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text("Enable notification access")
                }
            }
        } else {
            Text(
                "Notification access: ON",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            // Per-app blocklist management.
            val blocked = remember { mutableStateOf(com.igris.companion.notifications.IgrisNotificationListener.blockedPackages().toList()) }
            if (blocked.value.isNotEmpty()) {
                Text(
                    "Blocked apps",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                blocked.value.forEach { pkg ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            pkg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            com.igris.companion.notifications.IgrisNotificationListener.unblock(pkg)
                            blocked.value = com.igris.companion.notifications.IgrisNotificationListener.blockedPackages().toList()
                        }) {
                            Text("Unblock")
                        }
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (notifs.isEmpty()) {
                item {
                    Text(
                        "No notifications synced yet.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(notifs, key = { "n-${it.id}" }) { n ->
                val isLocal = n.deviceId.isNotBlank() && n.deviceId == com.igris.companion.providers.AppEventListener.localDeviceId
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(
                                n.appName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "from ${n.deviceName} · ${timeFmt.format(Date(n.timestamp))}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (isLocal) {
                                Spacer(Modifier.weight(1f))
                                Button(onClick = {
                                    com.igris.companion.notifications.IgrisNotificationListener.block(n.appPackage.ifBlank { n.appName })
                                }) {
                                    Text("Block")
                                }
                            }
                        }
                        Text(
                            n.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (n.body.isNotBlank()) {
                            Text(
                                n.body,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}