package com.igris.companion.ui.tabs

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igris.companion.node.NodeHolder
import com.igris.companion.ui.FadeSlideInCard
import com.igris.companion.ui.PulseDot
import com.igris.companion.ui.SectionHeader
import com.igris.companion.ui.theme.IgrisTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.igris_protocol.DeviceInfo

/**
 * Devices on the LAN mesh. Same Obsidian Signal language as the desktop
 * Devices panel: mono headers, pulsing presence dots, LINKED chips.
 */
@Composable
fun DevicesTab() {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun report(tag: String, result: Result<Unit>) {
        if (result.isSuccess) {
            Log.d("IGRIS", "$tag ok")
        } else {
            val msg = "$tag failed: ${result.exceptionOrNull()}"
            Log.e("IGRIS", msg)
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            devices = withContext(Dispatchers.IO) { NodeHolder.node?.getDevices() ?: emptyList() }
            scanning = false
            delay(3000)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
    Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
        SectionHeader("ECOSYSTEM NODES")
        Text(
            if (scanning) "Scanning your LAN…"
            else "Peers (${devices.size}) — same subnet, IGRIS running.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (devices.isEmpty() && !scanning) {
            Column(
                Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No peers found",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Devices with IGRIS running appear here automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(devices, key = { it.id }) { d ->
                FadeSlideInCard {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (d.isTrusted) IgrisTokens.Trust.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    PulseDot(
                                        if (d.isOnline) IgrisTokens.Trust
                                        else IgrisTokens.TextMuted,
                                    )
                                    Column(Modifier.padding(start = 12.dp)) {
                                        Text(
                                            d.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${d.ip}:${d.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (d.isTrusted) "LINKED" else if (d.isOnline) "ONLINE" else "OFFLINE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (d.isTrusted) IgrisTokens.Trust
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (d.isTrusted) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                NodeHolder.node?.let { report("unpair", runCatching { it.unpair(d.id) }) }
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = IgrisTokens.Danger,
                                        ),
                                    ) {
                                        Text("UNLINK")
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                NodeHolder.node?.let { report("pair", runCatching { it.pair(d.ip) }) }
                                            }
                                        },
                                    ) {
                                        Text("LINK")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
