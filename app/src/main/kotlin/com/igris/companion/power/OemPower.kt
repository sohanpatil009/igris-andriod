package com.igris.companion.power

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Cross-OEM helpers to keep IGRIS off the OS freeze/kill list.
 *
 * Two levers:
 *  1. Battery-optimization exemption — pure AOSP, works on **every** OEM, and is
 *     the single biggest factor for background survival (it also legalizes the
 *     watchdog's background foreground-service starts on API 31+).
 *  2. OEM autostart / background-launch allowlist — the only manufacturer-specific
 *     surface. We try a chain of known ComponentNames for the detected OEM and
 *     fall back to the app-details screen on any unrecognized ROM, so a wrong or
 *     outdated entry is never fatal.
 *
 * ComponentNames follow the well-known dontkillmyapp.com set; validate per ROM.
 */
object OemPower {
    private const val TAG = "IGRIS"

    // ---- Battery optimization (universal) ----

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Prompt to exempt IGRIS from battery optimization, with graceful fallbacks. */
    fun requestBatteryUnrestricted(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(context, direct)) return

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch(context, list)) return

        openAppDetails(context)
    }

    // ---- Autostart / background allowlist (per-OEM, with fallback) ----

    /** Friendly OEM skin name for button labels. */
    fun oemLabel(): String = when {
        isAny("xiaomi", "redmi", "poco") -> "MIUI/HyperOS"
        isAny("oppo") -> "ColorOS"
        isAny("realme") -> "Realme UI"
        isAny("oneplus") -> "OxygenOS"
        isAny("iqoo") -> "iQOO"
        isAny("vivo") -> "Funtouch/OriginOS"
        isAny("samsung") -> "One UI"
        isAny("huawei", "honor") -> "EMUI/MagicOS"
        isAny("asus") -> "ZenUI"
        else -> "your phone"
    }

    /** True when we ship OEM-specific autostart deep-links for this device. */
    fun hasOemAutostart(): Boolean = autostartCandidates().isNotEmpty()

    /**
     * Open the OEM autostart / background-launch allowlist. Tries each known
     * ComponentName for this OEM and launches the first that starts; falls back
     * to the app-details screen otherwise. We attempt startActivity directly
     * (inside try/catch) rather than resolveActivity, because package-visibility
     * rules on Android 11+ make resolveActivity a false-negative for these
     * system security apps.
     */
    fun openAutostart(context: Context) {
        for (cn in autostartCandidates()) {
            val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(context, intent)) {
                Log.i(TAG, "opened autostart: ${cn.flattenToShortString()}")
                return
            }
        }
        Log.i(TAG, "no OEM autostart resolved; opening app details")
        openAppDetails(context)
    }

    private fun autostartCandidates(): List<ComponentName> = when {
        isAny("xiaomi", "redmi", "poco") -> listOf(
            cn("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        isAny("oppo", "realme") -> listOf(
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            cn("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            cn("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            cn("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        isAny("oneplus") -> listOf(
            cn("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            // Newer OxygenOS is ColorOS underneath.
            cn("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        )
        isAny("vivo", "iqoo") -> listOf(
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            cn("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            cn("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
        )
        isAny("huawei", "honor") -> listOf(
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            cn("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        isAny("asus") -> listOf(
            cn("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
            cn("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        )
        isAny("samsung") -> listOf(
            // One UI has no autostart list; Device Care battery is the closest —
            // the user adds IGRIS to "Never sleeping apps" there.
            cn("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            cn("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        else -> emptyList()
    }

    // ---- helpers ----

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(context, intent)
    }

    private fun cn(pkg: String, cls: String) = ComponentName(pkg, cls)

    private fun isAny(vararg keys: String): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return keys.any { m.contains(it) || b.contains(it) }
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launch failed: ${intent.component ?: intent.action}", t)
        false
    }
}
