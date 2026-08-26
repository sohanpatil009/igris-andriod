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
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igris.companion.node.NodeHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.igris_protocol.DeviceInfo

/**
 * Phase 2: drives the list from the node's discovery snapshot (polled here;
 * onDeviceUpdated events also refresh it) and exposes LINK/UNLINK, which
 * POST /pair/request and /pair/untrust over TLS to the peer.
 */
@Composable
fun DevicesTab() {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
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
            delay(3000)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
    Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
        Text("Devices", style = MaterialTheme.typography.titleLarge)
        Text(
            "Discovered on your LAN via the 30s eco subnet scan.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (devices.isEmpty()) {
            Text(
                "No devices discovered yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(devices, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(d.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${d.ip}:${d.port}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (d.isTrusted) "Linked" else "Not linked",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (d.isTrusted) {
                                OutlinedButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        NodeHolder.node?.let { report("unpair", runCatching { it.unpair(d.id) }) }
                                    }
                                }) {
                                    Text("UNLINK")
                                }
                            } else {
                                Button(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        NodeHolder.node?.let { report("pair", runCatching { it.pair(d.ip) }) }
                                    }
                                }) {
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
