package com.igris.companion.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.igris.companion.power.OemPower

/**
 * Single source of truth for the live state of each background-sync permission,
 * shared by the first-run onboarding wall and the Settings tab so both agree.
 */
object PermissionStatus {

    /** POST_NOTIFICATIONS (API 33+). Granted at install below 33. */
    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Battery-optimization exemption — the universal keep-alive lever. */
    fun batteryUnrestricted(context: Context): Boolean =
        OemPower.isBatteryUnrestricted(context)

    /** IGRIS accessibility service enabled — required for clipboard capture. */
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.contains("igris", ignoreCase = true) }
    }

    /** IGRIS notification listener enabled — required for notification export. */
    fun notificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(':').any { it.contains("igris", ignoreCase = true) }
    }
}
