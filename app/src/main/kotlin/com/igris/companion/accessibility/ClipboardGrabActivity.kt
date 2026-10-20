package com.igris.companion.accessibility

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import com.igris.companion.node.NodeHolder
import java.io.File

/**
 * Igris native clipboard grab (Igris: ClipboardGrabActivity).
 *
 * Android 10+ denies clipboard reads to background apps, and MIUI removes the
 * accessibility-service exemption too. When a copy gesture is detected (or
 * the tile is tapped), this transparent 1x1 activity is raised to claim
 * foreground; once it actually has focus the clipboard is readable, so we
 * read, push into the sync fan-out and finish.
 *
 * The activity stays focused briefly and re-reads once: some apps commit the
 * copy after the grab already launched (gesture triggers fire on the button
 * tap, before the write lands), so a late-differing read is pushed too.
 *
 * Requires SYSTEM_ALERT_WINDOW (adb: `appops set <pkg> SYSTEM_ALERT_WINDOW
 * allow`) to make background activity launches legal, using Igris native.
 */
class ClipboardGrabActivity : Activity() {

    private val logFile: File by lazy { File(filesDir, "a11y.log") }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPushedText: String? = null

    /** True when the user opened this from the tap-to-sync notification. */
    private var fromFallback = false

    /** Window focus arrived at least once (a launched-but-never-shown
     *  activity means the OEM half-blocked us — notify instead of silence). */
    private var hadFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromFallback = intent?.getBooleanExtra(
            SyncFallbackNotifier.EXTRA_FROM_FALLBACK, false,
        ) ?: false
        // Report back to the watchdog: this launch made it through.
        SyncFallbackNotifier.markGrabLaunched(this, System.currentTimeMillis())
        // Cold-boot path (tile tapped right after install/reboot): kick the
        // node ourselves instead of relying on the a11y service rebinding.
        NodeHolder.start(applicationContext)
        window.setLayout(1, 1)
        val wlp = window.attributes
        wlp.dimAmount = 0f
        wlp.flags = wlp.flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        window.attributes = wlp
        // Launched but the OS never brought us to front (partial MIUI block):
        // the focus callback never fires, so the clipboard would never be
        // read. Prompt the one-tap path instead of failing silently.
        handler.postDelayed({
            if (!hadFocus && !isFinishing && !fromFallback) {
                logLine("grab: launched but never focused — posting tap-to-sync")
                SyncFallbackNotifier.notifyTapToSync(this)
                finish()
            }
        }, 2500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        hadFocus = true

        // Gesture-triggered grabs race the copy commit; give the source app
        // a beat to finish writing the clip before reading.
        handler.postDelayed({ if (!isFinishing) readAndPush() }, 450)
    }

    private fun readAndPush() {
        when (val clip = readClip()) {
            ReadResult.Denied ->
                if (fromFallback) {
                    logLine("grab: read denied even focused")
                    SyncFallbackNotifier.notifyUnavailable(
                        this,
                        "Your phone blocked the read this time — copy again and retry.",
                    )
                }
            ReadResult.Sensitive ->
                // Sensitive clips are never synced (privacy guard).
                if (fromFallback) {
                    logLine("grab: sensitive clip, stayed on phone")
                    SyncFallbackNotifier.notifyUnavailable(
                        this,
                        "That copy looked sensitive (like a password), so it stayed on this phone.",
                    )
                }
            is ReadResult.Text ->
                if (clip.value.isNotEmpty() && clip.value != lastPushedText) {
                    lastPushedText = clip.value
                    logLine("grab: read=${clip.value.take(16)}")
                    pushAsync(clip.value)
                }
        }
        // Stay focused for a late re-read before closing.
        handler.postDelayed({ if (!isFinishing) reReadOrFinish(1) }, 1100)
    }

    private fun reReadOrFinish(attempt: Int) {
        val clip = readClip()
        if (clip is ReadResult.Text &&
            clip.value.isNotEmpty() &&
            clip.value != lastPushedText
        ) {
            lastPushedText = clip.value
            logLine("grab: late read, pushing ${clip.value.take(16)}")
            pushAsync(clip.value)
            handler.postDelayed({ if (!isFinishing) reReadOrFinish(attempt + 1) }, 1100)
            return
        }
        // Nothing new. Close unless the node is still booting with a pending
        // push (cold start) — keep the retry alive for a few seconds.
        if (NodeHolder.node == null && lastPushedText != null && attempt < 10) {
            handler.postDelayed({ if (!isFinishing) reReadOrFinish(attempt + 1) }, 1000)
            return
        }
        SyncFallbackNotifier.clearGrabRequest(this)
        finish()
    }

    private sealed interface ReadResult {
        data object Denied : ReadResult
        data object Sensitive : ReadResult
        data class Text(val value: String) : ReadResult
    }

    private fun readClip(): ReadResult = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ReadResult.Denied
        if (clip.description?.extras
                ?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            ReadResult.Sensitive
        } else {
            ReadResult.Text(clip.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
        }
    } catch (e: Exception) {
        ReadResult.Denied
    }

    /** Push on a worker thread; retries briefly while the node boots. */
    private fun pushAsync(text: String) {
        Thread {
            for (i in 1..30) {
                val node = NodeHolder.node
                if (node != null) {
                    var pushed = false
                    try {
                        node.pushClipboard(text)
                        pushed = true
                        logLine("grab: pushed ${text.take(16)}")
                    } catch (e: Exception) {
                        logLine("grab: push failed: $e")
                    }
                    if (pushed) {
                        SyncFallbackNotifier.clearGrabRequest(this)
                        if (fromFallback) {
                            // Confirm the one-tap sync; report honestly when
                            // no peer was online to receive it.
                            Thread.sleep(500)
                            val delivered = try {
                                node.clipboardDelivered()
                            } catch (e: Exception) {
                                true
                            }
                            SyncFallbackNotifier.notifyResult(this, delivered)
                        } else {
                            // Auto-path sync succeeded; drop any stale prompt.
                            SyncFallbackNotifier.cancel(this)
                        }
                    }
                    return@Thread
                }
                Thread.sleep(400)
            }
            logLine("grab: node never came up")
            if (fromFallback) {
                SyncFallbackNotifier.notifyUnavailable(
                    this,
                    "IGRIS is still starting up — try again in a moment.",
                )
            }
        }.start()
    }

    private fun logLine(msg: String) {
        try {
            logFile.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (e: Exception) {
        }
    }
}

