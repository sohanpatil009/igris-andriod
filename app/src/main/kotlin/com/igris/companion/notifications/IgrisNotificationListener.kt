package com.igris.companion.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.igris.companion.node.NodeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.igris_protocol.NotificationMessageInfo
import uniffi.igris_protocol.RichNotificationInfo
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Exports local notifications onto the ecosystem (Igris native):
 *
 * - Noise filter: ongoing/foreground/local-only/group-summary/media/system
 *   notifications are skipped.
 * - ~150ms coalescing per `StatusBarNotification.key`: rapid re-posts
 *   collapse into one update instead of spamming peers (50ms coalesce).
 * - Transient suppression: a removal arriving before the coalesce delay
 *   cancels the pending send entirely (Igris behaviour).
 * - Rich extraction: title/text/conversation, app label + package, app and
 *   large icon downscaled to <=96px PNG base64 with MD5 hash, action labels,
 *   reply `RemoteInput.resultKey`.
 * - Dismiss mirroring: `onNotificationRemoved` routes to
 *   `EcosystemNode.dismiss_notification` so peers remove their copy.
 * - Remote execution: reply / action / dismiss requests from peers are
 *   handled through [Executor] (PendingIntent firing + cancelNotification).
 */
class IgrisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "IGRIS"
        private const val POST_DELAY_MS = 150L
        private const val MAX_ICON_PX = 96

        /** PendingIntent + RemoteInputs for quick-reply, keyed by sbn.key. */
        val replyActions = ConcurrentHashMap<String, Pair<PendingIntent, Array<android.app.RemoteInput>>>()
        /** Action PendingIntents (label -> intent) keyed by sbn.key. */
        val actionIntents = ConcurrentHashMap<String, Array<Notification.Action>>()
        /** Per-app blocklist (package names), persisted in SharedPreferences. */
        private val blocklist = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        @Volatile
        var instance: IgrisNotificationListener? = null

        fun isBlocked(pkg: String) = blocklist.contains(pkg)

        fun block(pkg: String) {
            blocklist.add(pkg)
            persistBlocklist()
        }

        fun unblock(pkg: String) {
            blocklist.remove(pkg)
            persistBlocklist()
        }

        fun blockedPackages(): Set<String> = blocklist

        fun loadBlocklist(ctx: Context) {
            blocklist.clear()
            ctx.getSharedPreferences("igris_notif_blocklist", Context.MODE_PRIVATE)
                .all.forEach { (k, _) -> blocklist.add(k) }
        }

        private fun persistBlocklist() {
            val ctx = instance?.applicationContext ?: return
            ctx.getSharedPreferences("igris_notif_blocklist", Context.MODE_PRIVATE)
                .edit().apply {
                    clear()
                    blocklist.forEach { putBoolean(it, true) }
                    apply()
                }
        }

        /** Peer requested a quick reply — fire the stored RemoteInput PendingIntent. */
        fun reply(key: String, text: String) {
            val stored = replyActions[key]
            if (stored != null) {
                fireRemoteInput(stored.first, stored.second, text, key)
                return
            }
            // The map is in-memory: a restart wipes it while the shade
            // notification survives. Fall back to the live shade entry.
            Log.d(TAG, "reply: no stored action for key=$key, re-reading shade")
            val svc = instance ?: run {
                Log.w(TAG, "reply: listener not bound")
                return
            }
            try {
                val match = svc.activeNotifications.firstOrNull { it.key == key }
                if (match == null) {
                    Log.w(TAG, "reply: key gone from shade: $key")
                    return
                }
                val actions = match.notification.actions ?: emptyArray()
                for (action in actions) {
                    val ris = action?.remoteInputs
                    if (ris != null && ris.isNotEmpty() && action.actionIntent != null) {
                        replyActions[key] = action.actionIntent to ris
                        fireRemoteInput(action.actionIntent, ris, text, key)
                        return
                    }
                }
                Log.w(TAG, "reply: live entry has no RemoteInput: $key")
            } catch (t: Throwable) {
                Log.w(TAG, "reply: shade re-read failed", t)
            }
        }

        private fun fireRemoteInput(
            intent: PendingIntent,
            remoteInputs: Array<android.app.RemoteInput>,
            text: String,
            key: String,
        ) {
            val localIntent = android.content.Intent()
            val results = android.os.Bundle()
            for (ri in remoteInputs) {
                // Results must go in BEFORE sealing the intent — otherwise
                // the target app receives an empty message ("can't send").
                results.putString(ri.resultKey, text)
                android.app.RemoteInput.addResultsToIntent(arrayOf(ri), localIntent, results)
                break
            }
            try {
                intent.send(instance?.applicationContext, 0, localIntent)
                Log.d(TAG, "reply fired for key=$key")
            } catch (t: Throwable) {
                Log.w(TAG, "reply intent failed", t)
            }
        }

        /** Peer requested an action button press — fire the matching PendingIntent. */
        fun fireAction(key: String, actionIndex: Int) {
            val actions = actionIntents[key] ?: return
            if (actionIndex < 0 || actionIndex >= actions.size) return
            val action = actions[actionIndex]
            if (action.actionIntent == null) return
            try {
                action.actionIntent.send()
            } catch (t: Throwable) {
                Log.w(TAG, "action intent failed", t)
            }
        }

        /** Peer dismissed our notification — cancel it locally. */
        fun dismiss(key: String) {
            val svc = instance ?: return
            try {
                svc.cancelNotification(key)
            } catch (t: Throwable) {
                Log.w(TAG, "cancelNotification failed", t)
            }
        }

        /** Drop executor state for a key (notification removed). */
        fun cleanup(key: String) {
            replyActions.remove(key)
            actionIntents.remove(key)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val pendingPosts = HashMap<String, Runnable>()

    override fun onListenerConnected() {
        instance = this
        loadBlocklist(applicationContext)
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val ctx = applicationContext
        if (sbn.packageName == ctx.packageName) return
        if (isBlocked(sbn.packageName)) return
        if (!shouldSync(sbn)) return

        // Key-based coalescing: rapid re-posts collapse into one send.
        val key = sbn.key
        pendingPosts.remove(key)?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { flushPost(sbn) }
        pendingPosts[key] = runnable
        handler.postDelayed(runnable, POST_DELAY_MS)
    }

    private fun flushPost(sbn: StatusBarNotification) {
        val key = sbn.key
        pendingPosts.remove(key)
        val node = NodeHolder.node ?: return
        val rich = extract(sbn) ?: return
        scope.launch {
            try {
                node.pushNotificationRich(rich)
            } catch (t: Throwable) {
                Log.w(TAG, "push_notification_rich failed", t)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val key = sbn.key
        val pending = pendingPosts.remove(key)
        if (pending != null) {
            // Transient: gone before the coalesce delay — never sync it.
            handler.removeCallbacks(pending)
            return
        }
        cleanup(key)
        val node = NodeHolder.node ?: return
        scope.launch {
            try {
                node.dismissNotification(key)
            } catch (t: Throwable) {
                Log.w(TAG, "dismiss_notification failed", t)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pendingPosts.clear()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------

    private fun shouldSync(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (flags and Notification.FLAG_LOCAL_ONLY != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.isMediaStyle()) return false

        // System noise hacks (Igris native parity).
        val pkg = sbn.packageName
        if (pkg == "com.android.systemui") {
            val tag = sbn.tag ?: ""
            if (tag.contains("low_battery")) return false
        }
        if (pkg == "com.samsung.android.oneui" || pkg.contains("samsung")) {
            val channel = n.channelId ?: ""
            if (channel.contains("MediaOngoingActivity")) return false
        }

        // Filter notifications likely containing OTPs or verification codes
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val combined = "$title $text"
        val otpPattern = Regex("\\b\\d{4,8}\\b")
        val sensitiveWords = listOf("verification code", "otp", "one-time", "passcode", "security code", "auth code")
        if (sensitiveWords.any { combined.contains(it, ignoreCase = true) }) return false
        // Don't block short numeric sequences that appear in normal messages
        if (combined.contains(otpPattern) && combined.length < 80) return false

        return true
    }

    private fun Notification.isMediaStyle(): Boolean {
        return try {
            val template = extras.getCharSequence(Notification.EXTRA_TEMPLATE)?.toString()
                ?: extras.getString(Notification.EXTRA_TEMPLATE)
            template?.contains("MediaStyle") == true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Rich extraction
    // ------------------------------------------------------------------

    private fun extract(sbn: StatusBarNotification): RichNotificationInfo? {
        val ctx = applicationContext
        val n = sbn.notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = buildString {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it) }
            extras.getCharSequence(Notification.EXTRA_TEXT)?.let {
                if (isNotEmpty()) append(" ")
                append(it)
            }
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let {
                if (isNotEmpty()) append(" ")
                append(it)
            }
        }.toString()
        if (title.isNullOrBlank() && body.isBlank()) return null

        val messages = extractMessages(extras)
        val (icon, iconHash) = extractIcons(ctx, sbn)

        // Action labels + reply capability + stored PendingIntents.
        val actions = n.actions ?: emptyArray()
        val actionLabels = ArrayList<String>()
        var canReply = false
        var replyResult: Pair<PendingIntent, Array<android.app.RemoteInput>>? = null
        val actionIntentsList = ArrayList<Notification.Action>()
        for (action in actions) {
            if (action == null) continue
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty() && action.actionIntent != null) {
                canReply = true
                replyResult = action.actionIntent to remoteInputs
            } else {
                actionLabels.add(action.title?.toString() ?: "")
                if (action.actionIntent != null) {
                    actionIntentsList.add(action)
                }
            }
        }
        if (canReply) {
            replyActions[sbn.key] = replyResult!!
        }
        if (actionIntentsList.isNotEmpty()) {
            actionIntents[sbn.key] = actionIntentsList.toTypedArray()
        }

        return RichNotificationInfo(
            key = sbn.key,
            appPackage = sbn.packageName,
            appName = appLabel(ctx, sbn.packageName),
            title = title ?: "",
            body = body,
            icon = icon,
            iconHash = iconHash,
            actions = actionLabels,
            canReply = canReply,
            messages = messages,
        )
    }

    private fun extractMessages(extras: android.os.Bundle): List<NotificationMessageInfo> {
        return try {
            val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
            msgs.mapNotNull { m ->
                val bundle = m as? android.os.Bundle ?: return@mapNotNull null
                val sender = bundle.getCharSequence("sender")?.toString() ?: ""
                val text = bundle.getCharSequence("text")?.toString() ?: ""
                if (sender.isBlank() && text.isBlank()) null
                else NotificationMessageInfo(sender = sender, text = text)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractIcons(ctx: Context, sbn: StatusBarNotification): Pair<String?, String> {
        val n = sbn.notification
        var bitmap: Bitmap? = null
        try {
            val largeIcon = n.largeIcon
            if (largeIcon != null && largeIcon.width > 0) {
                bitmap = largeIcon
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                n.getLargeIcon()?.let { icon ->
                    bitmap = icon.loadDrawable(ctx)?.let { drawable ->
                        if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap
                        else bitmapFromDrawable(drawable)
                    }
                }
            }
            if (bitmap == null) {
                // App launcher icon fallback.
                val appIcon = ctx.packageManager.getApplicationIcon(sbn.packageName)
                bitmap = if (appIcon is android.graphics.drawable.BitmapDrawable) {
                    appIcon.bitmap
                } else {
                    bitmapFromDrawable(appIcon)
                }
            }
        } catch (e: Exception) {
            return Pair(null, "")
        }
        if (bitmap == null) return Pair(null, "")

        val scaled = downscale(bitmap, MAX_ICON_PX)
        val bytes = compressPng(scaled)
        if (bytes == null || bytes.isEmpty()) return Pair(null, "")
        return Pair(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), md5Hex(bytes))
    }

    private fun bitmapFromDrawable(drawable: android.graphics.drawable.Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(
            maxOf(drawable.intrinsicWidth, 1),
            maxOf(drawable.intrinsicHeight, 1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    private fun downscale(src: Bitmap, maxPx: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxPx && h <= maxPx) return src
        val scale = maxPx.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    }

    private fun compressPng(bitmap: Bitmap): ByteArray? {
        return try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (e: Exception) {
        pkg
    }
}
