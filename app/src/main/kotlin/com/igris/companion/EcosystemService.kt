package com.igris.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.igris.companion.keepalive.KeepAliveManager
import com.igris.companion.node.NodeHolder
import com.igris.companion.watchdog.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that boots the shared Rust node (eco + FastSwap servers,
 * discovery, sync loops) and keeps it alive while devices are linked. The node
 * binds 53327/53328 (eco) and 53317/53318 (FastSwap).
 *
 * Runs as a `connectedDevice` FGS (no Android-14 6h/day cap; restartable from
 * boot/background on Android 15). A ~15s loop drives two keep-alive tiers:
 *  - Tier 1 (always while linked): the watchdog keeps the service resurrectable.
 *  - Tier 2 (only while ≥1 mobile peer online): hold CPU + WiFi wake locks so
 *    OEM power management can't freeze the sync.
 */
class EcosystemService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "igris_service"
    private val notifId = 1

    private lateinit var keepAlive: KeepAliveManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
        keepAlive = KeepAliveManager(this)
        com.igris.companion.calls.CallSmsBridge.start(this)
        scope.launch { keepAliveLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        NodeHolder.start(this)
        // Mirror the phone's call history to linked desktops once the node
        // finished booting (NodeHolder.start is async on the IO dispatcher).
        scope.launch {
            delay(12_000)
            com.igris.companion.calls.CallSmsBridge.syncCallLog(applicationContext)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away: schedule a near-term restart (belt-and-suspenders with STICKY).
        Watchdog.scheduleSoon(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        keepAlive.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Drives the keep-alive tiers every ~15s: hold/release the mobile-gated wake
     * locks, and keep the watchdog's linked flag + alarm in sync with the durable
     * trusted count. Online state flips on the 30s discovery cadence, so 15s
     * polling is comfortably fine-grained.
     */
    private suspend fun keepAliveLoop() {
        while (scope.isActive) {
            val node = NodeHolder.node
            if (node != null) {
                keepAlive.evaluate(node)
                try {
                    Watchdog.syncTrusted(applicationContext, node.trustedCount())
                } catch (_: Throwable) {
                }
            }
            // Backstop for the grab watchdog (the a11y service runs its own
            // at 1s): catches dropped tile-initiated launches and requests
            // that outlive an a11y-service restart.
            try {
                if (com.igris.companion.accessibility.SyncFallbackNotifier
                        .checkBlockedGrab(applicationContext, System.currentTimeMillis(), 5_000L)
                ) {
                    android.util.Log.w("IGRIS", "grab launch dropped; tap-to-sync prompt posted")
                }
            } catch (_: Throwable) {
            }
            delay(15_000)
        }
    }

    private fun enterForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(notifId, notif)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            channelId,
            "IGRIS background service",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("IGRIS")
            .setContentText("LAN device ecosystem is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, EcosystemService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
