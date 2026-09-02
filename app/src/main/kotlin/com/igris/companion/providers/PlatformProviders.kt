package com.igris.companion.providers

import android.content.Context
import android.os.Build
import android.util.Log
import uniffi.igris_protocol.ClipboardDataInfo
import uniffi.igris_protocol.ClipboardProvider
import uniffi.igris_protocol.DeviceInfo
import uniffi.igris_protocol.IncomingNotification
import uniffi.igris_protocol.IncomingTransfer
import uniffi.igris_protocol.NodeEventListener
import uniffi.igris_protocol.NotificationProvider
import uniffi.igris_protocol.RawNotificationInfo
import uniffi.igris_protocol.TransferProgressInfo

/**
 * Phase 1 stub platform seams. Clipboard + notification access get real
 * implementations in Phases 4/5; for now the node boots with no-ops so the
 * servers, discovery and transfer plumbing can be verified on-device.
 */
class PlatformProviders(context: Context) {
    private val ctx = context.applicationContext

    val clipboard: ClipboardProvider = object : ClipboardProvider {
        override fun getText(): String {
            // Android 10+: background clipboard reads are denied by the OS
            // (even for a11y services on MIUI). Touching the ClipboardManager
            // while backgrounded only produces system_server denial log spam,
            // so skip it entirely — the a11y service is the background path.
            if (!com.igris.companion.ForegroundState.isForeground) return ""
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return ""
            val primary = cm.primaryClip ?: return ""
            return if (primary.itemCount > 0) primary.getItemAt(0).coerceToText(ctx)?.toString() ?: "" else ""
        }

        override fun setText(text: String) {
            Log.d("IGRIS", "setText called: ${text.take(32)}...")

            // Foreground: a direct write is always legal on Android 10+ and
            // skips the activity round-trip entirely.
            if (com.igris.companion.ForegroundState.isForeground) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                try {
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("IGRIS", text))
                    Log.d("IGRIS", "setText: direct write OK (foreground)")
                    return
                } catch (e: Exception) {
                    Log.e("IGRIS", "setText: direct write failed while foreground: $e")
                    // Fall through to the activity path below.
                }
            }

            // Backgrounded: setPrimaryClip() throws SecurityException on
            // Android 10+, so raise ClipboardSetActivity to claim foreground
            // first. That's a background *activity launch*, which the OS
            // silently drops (no exception at all) unless SYSTEM_ALERT_WINDOW
            // is granted. Surface that case loudly — otherwise this looks
            // like a silent no-op on the receiving side, which is exactly
            // what made PC-to-phone clipboard sync look broken while
            // phone-to-PC (routed through the accessibility service, which
            // gets an automatic background-launch exemption) kept working.
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                Log.e(
                    "IGRIS",
                    "setText: backgrounded and SYSTEM_ALERT_WINDOW not granted \u2014 " +
                        "ClipboardSetActivity launch will likely be dropped by the OS. " +
                        "Grant \"display over other apps\" in Settings for incoming " +
                        "clipboard sync to work.",
                )
            }
            try {
                com.igris.companion.accessibility.ClipboardSetActivity.setClipboard(ctx, text)
            } catch (e: Exception) {
                Log.e("IGRIS", "setText via activity failed: $e")
                // Last-ditch fallback: works only if the app happens to be foreground.
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                try {
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("IGRIS", text))
                } catch (e2: Exception) {
                    Log.e("IGRIS", "setText fallback also failed: $e2")
                }
            }
        }
    }

    val notification: NotificationProvider = object : NotificationProvider {
        override fun readNotifications(): List<RawNotificationInfo> = emptyList()

        override fun replyToNotification(notificationId: String, reply: String) = Unit

        override fun hasPermission(): Boolean = false

        override fun requestPermission() = Unit
    }
}

/** Forwards core events to the UI / log. Phase 2+ tabs consume these. */
object AppEventListener : NodeEventListener {
    @Volatile
    var appContext: android.content.Context? = null

    /** Canonical local device id (set at node start) — used to detect remotes. */
    @Volatile
    var localDeviceId: String? = null

    private val alertedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onDeviceUpdated(device: DeviceInfo) = log("device updated: ${device.name} ${device.ip}")
    override fun onDeviceRemoved(deviceId: String) = log("device removed: $deviceId")
    override fun onClipboardReceived(data: ClipboardDataInfo) = log("clipboard: ${data.contentHash.take(8)}")
    override fun onClipboardReceivedFrom(data: ClipboardDataInfo) {
        log("received from peer: ${data.contentHash.take(8)}")
        val ctx = appContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(
                    ctx,
                    "IGRIS: clipboard received",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
            }
        }
    }

    override fun onNotificationReceived(notification: IncomingNotification) {
        log("notif: ${notification.title}")
        val ctx = appContext ?: return
        // Only surface remote notifications — local ones already exist here.
        if (notification.deviceId == localDeviceId) return
        if (notification.deviceId.isBlank()) return
        postSystemNotification(
            ctx,
            channelId = "igris_incoming",
            title = "${notification.appName} — ${notification.deviceName}",
            text = listOf(notification.title, notification.body)
                .filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    /** Peer wants us to fire the quick-reply RemoteInput of `key`. */
    override fun onNotificationReplyRequested(key: String, text: String) {
        log("reply requested: $key")
        com.igris.companion.notifications.IgrisNotificationListener.reply(key, text)
    }

    /** Peer pressed action button `actionIndex` of `key`. */
    override fun onNotificationActionRequested(key: String, actionIndex: UInt) {
        log("action requested: $key #$actionIndex")
        com.igris.companion.notifications.IgrisNotificationListener.fireAction(key, actionIndex.toInt())
    }

    /** Peer dismissed our notification `key` — cancel it locally. */
    override fun onNotificationDismissRequested(key: String) {
        log("dismiss requested: $key")
        com.igris.companion.notifications.IgrisNotificationListener.dismiss(key)
    }

    override fun onTransferRequest(transfer: IncomingTransfer) {
        log("incoming: ${transfer.fileCount} file(s) from ${transfer.senderName}")
        // A transfer only waits 60s for approval — alert even if the app is
        // not foreground. Deduped per session.
        val ctx = appContext ?: return
        if (!alertedSessions.add(transfer.sessionId)) return
        postSystemNotification(
            ctx,
            channelId = "igris_transfers",
            title = "IGRIS: incoming files",
            text = "${transfer.fileCount} file(s) from ${transfer.senderName} " +
                "(${transfer.files.joinToString(", ")}) — open the app to accept",
        )
    }

    override fun onTransferProgress(progress: TransferProgressInfo) = log("progress ${progress.overallPercent.toInt()}%")
    override fun onLog(message: String) = log(message)

    private fun postSystemNotification(ctx: android.content.Context, channelId: String, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                channelId,
                if (channelId == "igris_incoming") "Synced notifications" else "Transfers",
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)
            val openIntent = android.app.PendingIntent.getActivity(
                ctx, 0,
                android.content.Intent(ctx, com.igris.companion.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(com.igris.companion.R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
            nm.notify(title.hashCode(), notif)
        } catch (e: Exception) {
            log("post notification failed: $e")
        }
    }

    private fun log(message: String) {
        Log.d("IGRIS", message)
        appContext?.let { ctx ->
            try {
                java.io.File(ctx.filesDir, "events.log")
                    .appendText("${System.currentTimeMillis()} $message\n")
            } catch (e: Exception) {
            }
        }
    }
}
