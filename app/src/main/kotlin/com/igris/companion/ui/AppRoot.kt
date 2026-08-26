package com.igris.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.igris.companion.ui.onboarding.OnboardingPrefs
import com.igris.companion.ui.onboarding.OnboardingScreen

/**
 * Chooses between the first-run permission wall and the main app. Once the user
 * finishes (or skips) onboarding, the flag is persisted and the app goes
 * straight to [MainScreen] on every later launch.
 */
@Composable
fun AppRoot(onRequestNotifications: () -> Unit) {
    val context = LocalContext.current
    var onboarded by remember { mutableStateOf(OnboardingPrefs.isComplete(context)) }

    if (onboarded) {
        MainScreen()
    } else {
        OnboardingScreen(
            onRequestNotifications = onRequestNotifications,
            onDone = {
                OnboardingPrefs.setComplete(context)
                onboarded = true
            },
        )
    }
}
