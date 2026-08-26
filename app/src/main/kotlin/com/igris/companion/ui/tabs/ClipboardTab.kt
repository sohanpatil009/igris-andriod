package com.igris.companion.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ClipboardTab() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Clipboard", style = MaterialTheme.typography.titleLarge)
        Text(
            "Clipboard sync ships in Phase 4 (SHA-256 dedup in the core). " +
                "Note: Android 10+ restricts background clipboard reads, so the " +
                "sync loop only catches changes while the app is foreground.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
