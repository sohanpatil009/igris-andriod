package com.igris.companion.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.igris.companion.permissions.PermissionStatus
import com.igris.companion.power.OemPower
import com.igris.companion.ui.FadeSlideInCard
import com.igris.companion.ui.SectionHeader
import com.igris.companion.ui.theme.IgrisTokens
import kotlinx.coroutines.delay

/** One-time flag so the onboarding wall only shows on first run. */
object OnboardingPrefs {
    private const val PREFS = "igris_onboarding"
    private const val KEY_DONE = "complete"

    fun isComplete(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun setComplete(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

/**
 * First-run permission wall. Grants everything needed for background clipboard
 * sync from one screen so the user never has to hunt through system settings:
 *  - Notifications + unrestricted-battery fire real system dialogs (one tap).
 *  - Accessibility + OEM autostart can't be dialog-granted (Android/OEM security),
 *    so each button deep-links straight to the exact toggle.
 * Status is re-polled every ~1.2s, so checkmarks light up the moment the user
 * returns from a dialog or settings screen.
 */
@Composable
fun OnboardingScreen(
    onRequestNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var a11y by remember { mutableStateOf(PermissionStatus.accessibilityEnabled(context)) }
    var overlay by remember { mutableStateOf(PermissionStatus.overlayGranted(context)) }
    var battery by remember { mutableStateOf(PermissionStatus.batteryUnrestricted(context)) }
    var notifs by remember { mutableStateOf(PermissionStatus.notificationsGranted(context)) }
    var listener by remember { mutableStateOf(PermissionStatus.notificationListenerEnabled(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            a11y = PermissionStatus.accessibilityEnabled(context)
            overlay = PermissionStatus.overlayGranted(context)
            battery = PermissionStatus.batteryUnrestricted(context)
            notifs = PermissionStatus.notificationsGranted(context)
            listener = PermissionStatus.notificationListenerEnabled(context)
            delay(1200)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        SectionHeader("WELCOME TO IGRIS")
        Text(
            "A few grants keep sync alive in the background — even with the " +
                "screen off. Each card says exactly what Android will warn " +
                "you about and what IGRIS does with it. Nothing leaves your LAN.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(20.dp))

        PermissionCard(
            index = 1,
            title = "Clipboard access",
            required = true,
            description = "Lets IGRIS read what you copy in any app and sync it to your " +
                "linked devices. Without this, clipboard sync can't work.",
            disclosure = "Android will warn that IGRIS can observe screen content. " +
                "That is how copy detection works — IGRIS watches for copy " +
                "gestures only, never records your screen, and never uploads " +
                "anything anywhere: sync stays on your Wi-Fi.",
            granted = a11y,
            actionLabel = "Enable accessibility",
            note = "Find IGRIS in the list, turn it on, then tap Back.",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        PermissionCard(
            index = 2,
            title = "Display over other apps",
            required = true,
            description = "Lets IGRIS apply clipboard content sent *from* your other " +
                "devices while running in the background. Without this, sync only " +
                "works one-way (this phone to your PC) — pushes from the PC arrive " +
                "over the network but silently fail to land on this phone's clipboard.",
            granted = overlay,
            actionLabel = "Allow",
            note = "Find IGRIS and enable \"Allow display over other apps\", then tap Back.",
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        PermissionCard(
            index = 3,
            title = "Share notifications",
            required = true,
            description = "Mirrors this phone's notifications to your linked devices " +
                "so you can read and reply from anywhere. Without this, " +
                "notification sync can't work.",
            disclosure = "Android will warn that IGRIS can read all notifications, " +
                "including personal ones like messages and one-time codes. " +
                "Mirroring goes only to devices you linked, over your Wi-Fi — " +
                "never to any server. Sensitive codes stay on your LAN.",
            granted = listener,
            actionLabel = "Enable notification access",
            note = "Find IGRIS in the list, turn it on, then tap Back.",
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        PermissionCard(
            index = 4,
            title = "Unrestricted battery",
            required = false,
            description = "Stops your phone from freezing IGRIS after the screen turns " +
                "off — the single biggest factor for staying connected.",
            granted = battery,
            actionLabel = "Allow",
            onAction = { OemPower.requestBatteryUnrestricted(context) },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                index = 5,
                title = "Notifications",
                required = false,
                description = "Shows the ongoing \"sync is running\" status so the OS keeps " +
                    "the service alive and visible.",
                granted = notifs,
                actionLabel = "Allow",
                onAction = onRequestNotifications,
            )
        }

        if (OemPower.hasOemAutostart()) {
            PermissionCard(
                index = 6,
                title = "Autostart (${OemPower.oemLabel()})",
                required = false,
                description = "Lets IGRIS relaunch after a reboot and start in the " +
                    "background when a device connects.",
                granted = null, // No API exposes this state; it's action-only.
                actionLabel = "Open autostart settings",
                note = "Turn IGRIS on in the list your phone opens.",
                onAction = { OemPower.openAutostart(context) },
            )
        }

        if (OemPower.isMiuiFamily()) {
            PermissionCard(
                index = 7,
                title = "Background pop-up (${OemPower.oemLabel()})",
                required = false,
                description = "Unlocks fully automatic clipboard sync while IGRIS " +
                    "runs in the background. Without it, your phone silently " +
                    "blocks IGRIS from checking what you copied — you'd have to " +
                    "tap a small notification to sync each copy instead.",
                granted = null, // MIUI exposes no readable state; it's action-only.
                actionLabel = "Open permission page",
                note = "Enable \"Display pop-up windows while running in " +
                    "background\", then tap Back.",
                onAction = { OemPower.openBackgroundPopupSettings(context) },
            )
        }

        Spacer(Modifier.height(8.dp))

        if (!a11y || !overlay || !listener) {
            Text(
                "Sync stays partial until the required grants are on — accessibility " +
                    "for sending clips, \"display over other apps\" for receiving " +
                    "them, notification access for mirroring. You can still " +
                    "continue and grant them later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(if (a11y && overlay && listener) "Start using IGRIS" else "Skip for now")
        }
    }
}

@Composable
private fun PermissionCard(
    index: Int,
    title: String,
    required: Boolean,
    description: String,
    granted: Boolean?,
    actionLabel: String,
    onAction: () -> Unit,
    note: String? = null,
    disclosure: String? = null,
) {
    FadeSlideInCard {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(index = index, granted = granted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (required) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "required",
                                style = MaterialTheme.typography.labelSmall,
                                color = IgrisTokens.Pending,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (disclosure != null) {
                Text(
                    "Heads-up: $disclosure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (granted == true) {
                Text(
                    "Granted ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = IgrisTokens.Trust,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(actionLabel) }
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun StatusBadge(index: Int, granted: Boolean?) {
    if (granted == true) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "granted",
            tint = IgrisTokens.Trust,
            modifier = Modifier.size(28.dp),
        )
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    index.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
