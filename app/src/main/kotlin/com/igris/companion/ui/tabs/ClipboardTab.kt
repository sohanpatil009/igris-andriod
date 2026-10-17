package com.igris.companion.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igris.companion.ui.SectionHeader

@Composable
fun ClipboardTab() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionHeader("CLIPBOARD")
        Text(
            "Text syncs live across linked devices (SHA-256 dedup). Images " +
                "and files sync as media when the peer supports it. " +
                "Note: Android 10+ only delivers clipboard reads while the " +
                "app is foreground — the accessibility service and Quick " +
                "Settings tile cover background copies.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
