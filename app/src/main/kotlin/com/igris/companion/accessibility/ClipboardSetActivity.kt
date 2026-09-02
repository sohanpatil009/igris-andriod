package com.igris.companion.accessibility

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Transparent 1x1 activity that sets the clipboard from a foreground context.
 *
 * Android 10+ denies setPrimaryClip() to background services (SecurityException).
 * When a remote peer's clipboard arrives, this activity is launched to claim
 * foreground, write the clip, and finish immediately.
 *
 * Requires SYSTEM_ALERT_WINDOW for background activity launch.
 */
class ClipboardSetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT)
        if (text != null) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("IGRIS", text))
                Log.d("IGRIS", "ClipboardSetActivity: set clipboard OK (${text.take(16)}...)")
            } catch (e: Exception) {
                Log.e("IGRIS", "ClipboardSetActivity: set clipboard failed: $e")
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_CLIPBOARD_TEXT = "clipboard_text"

        fun setClipboard(context: Context, text: String) {
            val intent = Intent(context, ClipboardSetActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_CLIPBOARD_TEXT, text)
            }
            context.startActivity(intent)
        }
    }
}
