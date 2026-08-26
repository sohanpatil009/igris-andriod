package com.igris.companion.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.igris.companion.EcosystemService
import com.igris.companion.node.NodeHolder

/**
 * Restarts the sync service after reboot (BOOT_COMPLETED) and after the OS
 * kills/unfreezes it or the task is swiped away (Watchdog alarm chain). Only
 * acts while the user is linked to ≥1 device (persisted flag), so an untrusted
 * fresh install never spins up a foreground service on boot.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        val action = intent?.action

        if (!Watchdog.isLinked(ctx)) {
            Log.i(TAG, "watchdog $action: not linked, skipping restart")
            return
        }

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                startService(ctx)
                Watchdog.schedule(ctx)
            }
            Watchdog.ACTION_TICK -> {
                if (!NodeHolder.isStarted) startService(ctx)
                Watchdog.schedule(ctx) // re-arm the self-healing chain
            }
            else -> Unit
        }
    }

    private fun startService(context: Context) {
        try {
            EcosystemService.start(context)
            Log.i(TAG, "watchdog restarted EcosystemService")
        } catch (t: Throwable) {
            // API 31+ can reject a background FGS start unless battery-unrestricted
            // is granted; log and rely on START_STICKY / a11y rebind as backstops.
            Log.w(TAG, "watchdog service start failed (bg-start restricted?)", t)
        }
    }

    companion object {
        private const val TAG = "IGRIS"
    }
}
