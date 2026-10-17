package com.igris.companion.notifications

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.igris.companion.node.NodeHolder

/**
 * System-shade direct reply for mirrored notifications. Fires without
 * opening the app: takes the typed text and routes it to the originating
 * device through the Rust core.
 */
class MirrorReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "shade reply received")
        val results: Bundle = RemoteInput.getResultsFromIntent(intent) ?: run {
            Log.w(TAG, "shade reply: no input results")
            return
        }
        val text = results.getCharSequence(REPLY_KEY)?.toString().orEmpty()
        if (text.isBlank()) {
            Log.w(TAG, "shade reply: blank text")
            return
        }
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, 0)
        if (key.isEmpty() || deviceId.isEmpty()) {
            Log.w(TAG, "shade reply: missing key/device")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                // The app process may be dead (receiver-only wake): boot the
                // node first, otherwise the reply is silently dropped.
                var node = NodeHolder.node
                if (node == null) {
                    Log.d(TAG, "shade reply: node down, booting")
                    com.igris.companion.EcosystemService.start(context)
                    for (i in 1..40) {
                        Thread.sleep(500)
                        node = NodeHolder.node
                        if (node != null) break
                    }
                }
                if (node == null) {
                    Log.w(TAG, "shade reply: node never came up")
                    return@Thread
                }
                node.replyNotification(deviceId, key, text)
                Log.d(TAG, "shade reply sent to $deviceId")
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                nm.cancel(notifyId)
            } catch (t: Throwable) {
                Log.w(TAG, "shade reply failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val REPLY_KEY = "igris_reply"
        const val EXTRA_KEY = "sbn_key"
        const val EXTRA_DEVICE = "device_id"
        const val EXTRA_NOTIFY_ID = "notify_id"
        private const val TAG = "IGRIS"
    }
}
