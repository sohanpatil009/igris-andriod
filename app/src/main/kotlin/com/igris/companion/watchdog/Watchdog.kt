package com.igris.companion.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Keeps the sync service resurrectable across OEM freeze/kill and reboot.
 *
 * Uses [AlarmManager.setAndAllowWhileIdle] (fires even in Doze; needs no
 * SCHEDULE_EXACT_ALARM) to periodically poke [RestartReceiver], which restarts
 * [com.igris.companion.EcosystemService] if it isn't running. A one-shot alarm
 * re-arms itself each fire, forming a self-healing chain.
 *
 * Gated on being "linked" (≥1 trusted device). The linked flag is persisted in
 * SharedPreferences so the BOOT_COMPLETED path can check it before the Rust
 * node (and its trusted-count) is available. The service loop keeps the flag
 * current via [syncTrusted].
 *
 * Background FGS-start (API 31+) is legal here because the app is on the
 * battery-optimization allowlist (see OemPower onboarding) — that exemption
 * also exempts background foreground-service starts.
 */
object Watchdog {
    private const val TAG = "IGRIS"
    private const val PREFS = "igris_keepalive"
    private const val KEY_LINKED = "linked"
    private const val REQ_CODE = 4201

    const val ACTION_TICK = "com.igris.companion.watchdog.TICK"

    private const val INTERVAL_MS = 15L * 60L * 1000L // ~15 min
    private const val SOON_MS = 2_000L

    fun isLinked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LINKED, false)

    private fun setLinked(context: Context, linked: Boolean) {
        prefs(context).edit().putBoolean(KEY_LINKED, linked).apply()
    }

    /**
     * Called from the service loop with the durable trusted count. Persists the
     * linked flag and arms/cancels the watchdog accordingly.
     */
    fun syncTrusted(context: Context, trustedCount: UInt) {
        val linked = trustedCount > 0u
        setLinked(context, linked)
        if (linked) schedule(context) else cancel(context)
    }

    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        try {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(context),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog schedule failed", t)
        }
    }

    /** Near-term restart (used on swipe-away). */
    fun scheduleSoon(context: Context) = schedule(context, SOON_MS)

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, RestartReceiver::class.java)
            .setAction(ACTION_TICK)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context.applicationContext, REQ_CODE, intent, flags)
    }
}
