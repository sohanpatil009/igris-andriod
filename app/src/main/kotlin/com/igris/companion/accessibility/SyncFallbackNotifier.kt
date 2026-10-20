package com.igris.companion.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.igris.companion.R

/**
 * One-tap fallback for the background clipboard grab.
 *
 * Strict OEM skins (MIUI/HyperOS notably) silently drop background activity
 * launches: [ClipboardGrabActivity] never appears, no exception is thrown, and
 * a copy made while IGRIS is backgrounded never reaches the linked PC. Tapping
 * a notification is exempt from those launch restrictions, so when a grab
 * request goes unanswered IGRIS posts a single "tap to sync" prompt and the
 * user finishes the sync with one tap.
 *
 * This object also owns the grab-request bookkeeping (in SharedPreferences, so
 * it survives process restarts) shared between the accessibility service
 * (which requests the grab) and the grab activity (which reports back).
 */
object SyncFallbackNotifier {

    const val CHANNEL_ID = "igris_sync"
    const val NOTIF_ID = 4201
    const val EXTRA_FROM_FALLBACK = "igris.from_fallback"

    private const val PREFS = "igris_grab"
    private const val KEY_REQ_AT = "req_at"
    private const val KEY_LAUNCHED_AT = "launched_at"

    /** A request older than this is stale (previous process life) — never prompt for it. */
    private const val STALE_REQ_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records a grab request right before the (possibly silently-dropped) launch. */
    fun markGrabRequested(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_REQ_AT, atMs).commit()
    }

    /** The grab activity confirms it actually launched. */
    fun markGrabLaunched(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_LAUNCHED_AT, atMs).commit()
    }

    /** The request has been answered (or is no longer worth watching). */
    fun clearGrabRequest(context: Context) {
        prefs(context).edit().remove(KEY_REQ_AT).commit()
    }

    /**
     * Watches the pending grab request:
     *  - request answered (activity launched)  → returns false, clears if stale;
     *  - request younger than [graceMs]        → returns false, still waiting;
     *  - request unanswered past [graceMs]     → posts the tap-to-sync prompt
     *    and returns true (the launch was dropped by the OS).
     */
    fun checkBlockedGrab(context: Context, nowMs: Long, graceMs: Long): Boolean {
        val p = prefs(context)
        val reqAt = p.getLong(KEY_REQ_AT, 0L)
        if (reqAt <= 0L) return false

        val launchedAt = p.getLong(KEY_LAUNCHED_AT, 0L)
        if (launchedAt >= reqAt) {
            // Launched; the activity clears the request when it finishes.
            if (nowMs - reqAt > STALE_REQ_MS) clearGrabRequest(context)
            return false
        }
        if (nowMs - reqAt < graceMs) return false

        clearGrabRequest(context)
        if (nowMs - reqAt > STALE_REQ_MS) return false // stale from an earlier life — don't nag
        notifyTapToSync(context)
        return true
    }

    // ---- notification plumbing ----

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Clipboard sync prompts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Asks for one tap to sync a copy the phone " +
                        "blocked from running in the background"
                }
            )
        }
    }

    private fun openPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context.applicationContext,
            NOTIF_ID,
            Intent(context.applicationContext, ClipboardGrabActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_FROM_FALLBACK, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The grab launch was dropped: ask the user for one tap. */
    fun notifyTapToSync(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Clipboard waiting to sync")
            .setContentText("Tap to send your last copied text to your PC.")
            .setContentIntent(openPendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    /** Fallback tap succeeded — morph the prompt into a silent confirmation. */
    fun notifyResult(context: Context, delivered: Boolean) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (delivered) {
            builder.setContentTitle("Clipboard sent ✓")
                .setContentText("Your copied text is on your linked device.")
        } else {
            builder.setContentTitle("Waiting for a linked device")
                .setContentText("Clipboard captured — it will sync once a device is online.")
        }
        nm.notify(NOTIF_ID, builder.build())
    }

    /** The read itself failed (rare): tell the user instead of failing silently. */
    fun notifyUnavailable(context: Context, reason: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nothing synced")
            .setContentText(reason)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    /** Any successful sync direction clears a stale prompt. */
    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }
}
