package com.igris.companion.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igris.companion.node.NodeHolder
import com.igris.companion.ui.FadeSlideInCard
import com.igris.companion.ui.SectionHeader
import com.igris.companion.ui.theme.IgrisTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.igris_protocol.CallInfo
import uniffi.igris_protocol.SmsInfo

/**
 * Enterprise calls + SMS log. Same Obsidian Signal language as desktop.
 * The search box queries the local phonebook (name or number, like the
 * native dialer); tapping a hit dials it through the linked mesh.
 */
@Composable
fun CallsTab() {
    var calls by remember { mutableStateOf<List<CallInfo>>(emptyList()) }
    var sms by remember { mutableStateOf<List<SmsInfo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<uniffi.igris_protocol.ContactHitInfo>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            val node = NodeHolder.node
            if (node != null) {
                calls = withContext(Dispatchers.IO) { node.getCalls() }
                sms = withContext(Dispatchers.IO) { node.getSms() }
            }
            delay(3000)
        }
    }

    // Debounced local phonebook search (2+ chars).
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        val q = query.trim()
        hits = withContext(Dispatchers.IO) {
            try {
                com.igris.companion.providers.ContactProviderImpl(context).searchContacts(q, 8u)
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader("CALLS")
            Text(
                "Ringing, missed and ongoing calls from linked devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
        item {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search name or number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(hits, key = { "h-${it.number}" }) { h ->
            FadeSlideInCard {
                Card(
                    onClick = {
                        com.igris.companion.calls.CallSmsBridge.pushCall(
                            "dialing",
                            h.number,
                            h.name,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            h.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            h.number,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (calls.isEmpty()) {
            item {
                Text(
                    "No calls synced yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        items(calls, key = { "c-${it.callId}-${it.event}" }) { c ->
            FadeSlideInCard {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            c.event.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (c.event) {
                                "missed" -> IgrisTokens.Danger
                                "ringing" -> IgrisTokens.Pending
                                else -> IgrisTokens.Trust
                            },
                        )
                        Text(
                            c.contactName.ifBlank { c.phoneNumber.ifBlank { "Unknown" } },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            "from ${c.deviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SectionHeader("SMS")
            Text(
                "Messages synced from linked devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }
        if (sms.isEmpty()) {
            item {
                Text(
                    "No messages synced yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        items(sms, key = { "s-${it.smsId}" }) { m ->
            FadeSlideInCard {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            m.address,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            m.body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "from ${m.deviceName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
