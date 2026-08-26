package com.igris.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C8BFF),
    secondary = Color(0xFF9AA7B8),
    background = Color(0xFF0F1115),
    surface = Color(0xFF17191E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5BB8),
)

@Composable
fun IgrisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
