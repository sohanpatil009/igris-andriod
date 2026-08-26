package com.igris.companion.ui.tabs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.igris.companion.node.NodeHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.igris_protocol.DeviceInfo
import uniffi.igris_protocol.IncomingTransfer
import uniffi.igris_protocol.OutgoingFile
import uniffi.igris_protocol.TransferProgressInfo
import java.io.File

@Composable
fun TransfersTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pending by remember { mutableStateOf<List<IncomingTransfer>>(emptyList()) }
    var progress by remember { mutableStateOf<List<TransferProgressInfo>>(emptyList()) }
    var sendDialog by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var targetIpForSend by remember { mutableStateOf<String?>(null) }

    val published = remember { PublishTracker(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val node = NodeHolder.node
            if (node != null) {
                withContext(Dispatchers.IO) {
                    val pend = node.pendingTransfers()
                    val prog = node.getTransferProgress()
                    for (p in prog) {
                        if (!p.isOutbound && p.isComplete && !published.isPublished(p.sessionId)) {
                            published.markPublished(p.sessionId)
                            val files = node.getReceivedFiles(p.sessionId)
                            files.forEach { path ->
                                val src = File(path)
                                if (src.exists()) publishToDownloads(context, src)
                            }
                        }
                    }
                    pending = pend
                    progress = prog
                }
            }
            delay(1000)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            scope.launch {
                sending = true
                try {
                    val targetIp = targetIpForSend
                    if (targetIp == null) return@launch
                    val staged = withContext(Dispatchers.IO) { stageFiles(context, uris) }
                    if (staged.isEmpty()) {
                        sendError = "Could not stage any selected files"
                    } else {
                        val node = NodeHolder.node
                        if (node != null) {
                            withContext(Dispatchers.IO) {
                                node.sendFiles(targetIp, staged)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    sendError = t.message ?: "send failed"
                } finally {
                    sending = false
                }
            }
        }
    }

    if (sendDialog) {
        AlertDialog(
            onDismissRequest = { sendDialog = false },
            title = { Text("Send files to…") },
            text = {
                Column {
                    devices.forEach { d ->
                        val label = if (d.isOnline) "${d.name} (${d.ip})" else "${d.name} (${d.ip}) — offline"
                        TextButton(onClick = {
                            targetIpForSend = d.ip
                            sendDialog = false
                            filePicker.launch(arrayOf("*/*"))
                        }) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sendDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Transfers", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Button(
                enabled = !sending,
                onClick = {
                    devices = NodeHolder.node?.getDevices()?.filter { it.isOnline } ?: emptyList()
                    if (devices.isEmpty()) {
                        sendError = "No online devices found — run a scan in Devices"
                    } else {
                        sendDialog = true
                    }
                },
            ) { Text(if (sending) "Sending…" else "Send files") }
            Spacer(Modifier.width(8.dp))
            if (sending) Text("Transfer in progress", style = MaterialTheme.typography.bodySmall)
        }
        sendError?.let { err ->
            Text(
                err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (pending.isEmpty() && progress.isEmpty()) {
                item {
                    Text(
                        "No transfers. Files sent to this phone land in Downloads/IGRIS after " +
                            "you accept them.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            items(pending, key = { "p-${it.sessionId}" }) { t ->
                PendingCard(t, onAccept = {
                    NodeHolder.node?.approveTransfer(t.sessionId)
                }, onDeny = {
                    NodeHolder.node?.denyTransfer(t.sessionId)
                })
            }

            items(progress, key = { "a-${it.sessionId}" }) { p ->
                ActiveCard(p)
            }
        }
    }
}

@Composable
private fun PendingCard(t: IncomingTransfer, onAccept: () -> Unit, onDeny: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Incoming from ${t.senderName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${t.fileCount} file(s), ${formatSize(t.totalSize)} — ${t.files.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = onAccept) { Text("Accept") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun ActiveCard(p: TransferProgressInfo) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            val dir = if (p.isOutbound) "Sending" else "Receiving"
            Text(
                "$dir — ${p.fileNames.joinToString(", ")}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (p.overallPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${formatSize(p.transferredBytes)} / ${formatSize(p.totalBytes)} " +
                    "(${p.overallPercent.toInt()}%)" + if (p.isComplete) " — done" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private suspend fun stageFiles(context: Context, uris: List<Uri>): List<OutgoingFile> =
    withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "outbox").apply { mkdirs() }
        uris.mapNotNull { uri ->
            val name = queryDisplayName(context, uri) ?: return@mapNotNull null
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dest = File(outDir, safe)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: return@mapNotNull null
            } catch (e: Exception) {
                return@mapNotNull null
            }
            OutgoingFile(path = dest.absolutePath, relativePath = safe)
        }
    }

private fun queryDisplayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (e: Exception) {
        null
    }

private fun publishToDownloads(context: Context, src: File) {
    try {
        val sub = downloadsSubdir(context, src)
        val relBase = Environment.DIRECTORY_DOWNLOADS + "/IGRIS" +
            if (sub.isEmpty()) "" else "/$sub"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(context, src))
                put(MediaStore.Downloads.RELATIVE_PATH, relBase)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                src.delete()
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
            }
        } else {
            val dir = File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "IGRIS",
                ),
                sub.replace('/', File.separatorChar),
            ).apply { mkdirs() }
            val dest = File(dir, src.name)
            src.copyTo(dest, overwrite = false)
            src.delete()
        }
    } catch (e: Exception) {
        // Leave the file in app storage; logcat shows the reason.
        android.util.Log.w("IGRIS", "publish failed: ${src.name}", e)
    }
}

/** Subdirectory of the received file relative to the app download root,
 *  e.g. "photos/vacation" for files/downloads/photos/vacation/image.png. */
private fun downloadsSubdir(context: Context, src: File): String {
    val root = context.filesDir.resolve("downloads").canonicalPath
    val parent = src.parentFile?.canonicalPath ?: return ""
    return if (parent.startsWith(root)) parent.removePrefix(root).trimStart('/', '\\') else ""
}

private fun mimeOf(context: Context, f: File): String =
    try {
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
    } catch (e: Exception) {
        "application/octet-stream"
    }

private fun formatSize(bytes: ULong): String {
    val b = bytes.toLong().coerceAtLeast(0)
    return when {
        b >= 1024L * 1024 * 1024 -> String.format("%.2f GB", b / 1073741824.0)
        b >= 1024 * 1024 -> String.format("%.2f MB", b / 1048576.0)
        b >= 1024 -> String.format("%.1f KB", b / 1024.0)
        else -> "$b B"
    }
}

/** Remembers which inbound sessions were already published to MediaStore. */
private class PublishTracker(context: Context) {
    private val prefs = context.getSharedPreferences("igris_published", Context.MODE_PRIVATE)

    fun isPublished(sessionId: String): Boolean = prefs.contains(sessionId)

    fun markPublished(sessionId: String) {
        prefs.edit().putBoolean(sessionId, true).apply()
    }
}