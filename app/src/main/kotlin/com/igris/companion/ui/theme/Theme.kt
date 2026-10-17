package com.igris.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Igris Obsidian Signal — same tokens as the desktop shell
 * (`igris-ecosystem/src/ui/theme.rs`). Enterprise dark only.
 */
object IgrisTokens {
    val BgBase = Color(0xFF050A14)
    val BgRaised = Color(0xFF0A1128)
    val Signal = Color(0xFF22D3EE)
    val Awake = Color(0xFFA855F7)
    val Trust = Color(0xFFA3E635)
    val Pending = Color(0xFFFBBF24)
    val Danger = Color(0xFFFB7185)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF9AA7B8)
    val TextMuted = Color(0xFF475569)
    val Mono = FontFamily.Monospace
}

@Immutable
data class IgrisStatusColors(
    val trust: Color = IgrisTokens.Trust,
    val pending: Color = IgrisTokens.Pending,
    val danger: Color = IgrisTokens.Danger,
)

val LocalIgrisStatus = staticCompositionLocalOf { IgrisStatusColors() }

private val ObsidianSignal = darkColorScheme(
    primary = IgrisTokens.Signal,
    onPrimary = Color(0xFF050A14),
    secondary = IgrisTokens.Awake,
    onSecondary = Color.White,
    tertiary = IgrisTokens.Trust,
    background = IgrisTokens.BgBase,
    onBackground = IgrisTokens.TextPrimary,
    surface = IgrisTokens.BgRaised,
    onSurface = IgrisTokens.TextPrimary,
    surfaceVariant = Color(0xFF0C1430),
    onSurfaceVariant = IgrisTokens.TextSecondary,
    outline = Color(0x1FFFFFFF),
    error = IgrisTokens.Danger,
)

@Composable
fun IgrisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObsidianSignal,
        content = content,
    )
}
