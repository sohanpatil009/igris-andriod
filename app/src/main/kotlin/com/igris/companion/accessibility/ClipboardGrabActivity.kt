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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return

        // Gesture-triggered grabs race the copy commit; give the source app
        // a beat to finish writing the clip before reading.
        handler.postDelayed({ if (!isFinishing) readAndPush() }, 450)
    }

    private fun readAndPush() {
        when (val clip = readClip()) {
            ReadResult.Denied, ReadResult.Sensitive ->
                // Sensitive clips are never synced (hash-skip guard); denied
                // means nothing to do from this vantage either.
                if (clip == ReadResult.Sensitive) logLine("grab: sensitive clip, skipping")
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
                    try {
                        node.pushClipboard(text)
                        logLine("grab: pushed ${text.take(16)}")
                    } catch (e: Exception) {
                        logLine("grab: push failed: $e")
                    }
                    return@Thread
                }
                Thread.sleep(400)
            }
            logLine("grab: node never came up")
        }.start()
    }

    private fun logLine(msg: String) {
        try {
            logFile.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (e: Exception) {
        }
    }
}

