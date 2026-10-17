package com.igris.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.igris.companion.ui.theme.IgrisTokens

/**
 * Shared Obsidian Signal primitives — same visual language as the desktop
 * shell (`igris-ecosystem/src/ui/theme.rs`): mono section headers, pulsing
 * status dots, fade/slide-in cards.
 */
@Composable
fun SectionHeader(title: String) {
    Text(
        text = "// $title",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    Box(
        modifier
            .size(10.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun FadeSlideInCard(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(300)) +
            slideInVertically(
                animationSpec = tween(300),
                initialOffsetY = { it / 4 },
            ),
    ) {
        content()
    }
}
