package com.igris.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.igris.companion.node.NodeHolder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background clipboard reader.
 *
 * MIUI/HyperOS reality check (empirically verified on-device):
 *  - ClipboardManager reads are denied for background apps — even for
 *    accessibility services (Xiaomi patched the a11y exemption out).
 *  - Accessibility EVENT delivery to background services is withheld.
 *  - logcat writes from frozen background app processes are suppressed.
 * So we poll the accessibility UI tree (rootInActiveWindow is a direct
 * service call, the same API TalkBack uses) and log to a FILE (readable via
 * `run-as com.igris.companion cat files/a11y.log`) instead of logcat.
 */
class IgrisClipboardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastPushed: String? = null

    /** Poll-thread gate: after an undelivered push, wait this long before
     *  re-attempting the same text — stops the push/deny/re-push loop at 1 Hz
     *  while peers are offline. */
    @Volatile
    private var retryNotBeforeMs: Long = 0

    /** Copy-gesture detector (see [ClipboardDetection]). */
    private var detector: ClipboardDetection? = null
    private var lastGestureMs: Long = 0
    private val eventCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val logFile: File by lazy { File(filesDir, "a11y.log") }

    private fun logLine(msg: String) {
        Log.w("IGRIS", msg)
        try {
            logFile.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (e: Exception) {
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val locale = resources.configuration.locales[0]
        detector = ClipboardDetection(
            ClipboardDetection.wordsForLocale(locale.language, locale.country),
            ClipboardDetection.toastKeywordsForLocale(locale.language, locale.country),
        )
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener { onClipboardChanged() }
        logLine("a11y connected; watching clipboard")
        NodeHolder.start(this)
        logLine("node requested")
        try {
            com.igris.companion.EcosystemService.start(this)
            logLine("foreground service started (anti-freeze)")
        } catch (e: Exception) {
            logLine("fgs start failed: $e")
        }
        scope.launch { pollLoop(cm) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Copy-gesture detection (see [ClipboardDetection]). HyperOS withholds
     * the clip-changed callback for
     * background apps, so "the user copied something" is inferred from the
     * tap on Copy/Cut, the collapsing selection, or the copied-toast, and the
     * grab activity is raised to do the actual read.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return
        eventCounts.merge(event.eventType, 1, Int::plus)
        logInterestingEvent(event)
        val detector = detector ?: return
        val reason = detector.copyGestureReason(event) ?: return
        if (reason == "label") {
            // A tap on a view explicitly labelled Copy/Cut: high confidence,
            // never throttled (the generic gesture trigger may have fired a
            // beat earlier and consumed the window).
            lastGestureMs = 0
        } else {
            val now = System.currentTimeMillis()
            if (now - lastGestureMs < GESTURE_THROTTLE_MS) return
            lastGestureMs = now
        }
        logLine("a11y det: copy gesture ($reason) pkg=${event.packageName}")
        if (com.igris.companion.ForegroundState.isForeground) {
            // Focused: direct read is allowed, no grab needed.
            onClipboardChanged()
        } else {
            launchGrabActivity()
        }
    }

    /**
     * Diagnostic trace of the event types the copy heuristics key on. Keeps
     * labels (contentDescription / short text) so OEM/app-specific copy
     * buttons can be identified from a11y.log, but never logs full message
     * text.
     */
    private fun logInterestingEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> logLine(
                "a11y ev: t=${event.eventType} pkg=${event.packageName} " +
                    "cls=${event.className} cd=${event.contentDescription?.toString()?.take(24)} " +
                    "txt=${event.text?.joinToString("/")?.take(32)}"
            )
        }
    }

    override fun onInterrupt() = Unit

    private suspend fun pollLoop(cm: ClipboardManager) {
        var denyCount = 0
        var tickCount = 0
        while (true) {
            // Attempt the read on every tick regardless of focus. With
            // READ_LOGS granted (adb `pm grant`) some builds let the a11y
            // service read in background — then a copy reaches the fan-out
            // within 1s with no user gesture. When denied, the read is a
            // cheap null and the grab activity / tile remain the fallbacks.
            val text = readClip(cm)
            if (text != null) {
                if (text != lastPushed && System.currentTimeMillis() >= retryNotBeforeMs) {
                    lastPushed = text
                    logLine("a11y poll: ${text.take(24)}")
                    push(text)
                }
            } else if (++denyCount % 15 == 1) {
                logLine("a11y read denied/empty (x$denyCount)")
            }
            captureSelection()
            if (++tickCount % 30 == 0 && eventCounts.isNotEmpty()) {
                // Event-delivery histogram every ~30s: proves whether HyperOS
                // is actually delivering a11y events to this background
                // service (type=count, decimal event types).
                logLine("a11y hist: $eventCounts")
            }
            delay(1000)
        }
    }

    private fun onClipboardChanged() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = readClip(cm)
        if (text.isNullOrEmpty()) {
            // Android 10+ (and MIUI): background reads are denied. Igris
            // trick: raise a tiny transparent activity to claim foreground,
            // read there, then it finishes itself.
            if (!com.igris.companion.ForegroundState.isForeground) {
                logLine("a11y: clip changed in background — launching grab activity")
                launchGrabActivity()
            }
            return
        }
        if (text == lastPushed) return
        logLine("a11y: clip changed, pushing ${text.take(24)}")
        push(text)
    }

    private var lastGrabLaunchMs: Long = 0

    private fun launchGrabActivity() {
        val now = System.currentTimeMillis()
        if (now - lastGrabLaunchMs < 900) return
        lastGrabLaunchMs = now
        try {
            val intent = android.content.Intent(this, ClipboardGrabActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
            startActivity(intent)
        } catch (e: Exception) {
            logLine("grab launch failed: $e")
        }
    }

    private fun readClip(cm: ClipboardManager): String? = try {
        val clip = cm.primaryClip ?: return null
        // hash-skip password guard: skip clips the source app marked
        // sensitive (password managers set EXTRA_IS_SENSITIVE).
        if (clip.description?.extras
                ?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            null
        } else {
            clip.getItemAt(0)?.coerceToText(this)?.toString()
        }
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun push(text: String) {
        try {
            val node = NodeHolder.node ?: return
            node.pushClipboard(text)
            lastPushed = text
            // Dedup-on-failed-send fix: if the fan-out reached no peer (peer
            // offline), arm a retry so the next 1s poll re-pushes the same
            // text once a peer is reachable — no user re-selection needed.
            scope.launch {
                delay(400)
                val delivered = try {
                    node.clipboardDelivered()
                } catch (e: Exception) {
                    true
                }
                if (!delivered && lastPushed == text) {
                    logLine("a11y push undelivered — retry armed")
                    lastPushed = null
                    retryNotBeforeMs = System.currentTimeMillis() + 10_000
                }
            }
        } catch (e: Exception) {
            logLine("a11y push failed: $e")
        }
    }

    /** Poll-driven selection capture; runs every tick even when events are withheld. */
    private fun captureSelection() {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            logLine("a11y capture: root err ${e.message}")
            null
        }
        if (root == null) return
        try {
            val focused = findFocusedNode(root) ?: return
            val text = focused.text?.toString()
            if (text.isNullOrEmpty()) return
            val start = focused.textSelectionStart
            val end = focused.textSelectionEnd
            if (start !in 0..end || end <= start || end > text.length) return
            val selected = text.substring(start, end)
            if (selected.isNotEmpty() && selected != lastPushed) {
                lastPushed = selected
                logLine("a11y sel: ${selected.take(24)}")
                push(selected)
            }
        } catch (e: Exception) {
            logLine("a11y capture err: $e")
        } finally {
            root.recycle()
        }
    }

    private fun findFocusedNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNode(child)
            if (found != null) return found
        }
        return null
    }

    private companion object {
        /** Minimum spacing between gesture-triggered grabs. */
        const val GESTURE_THROTTLE_MS = 1000L
    }
}
