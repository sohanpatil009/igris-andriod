package com.igris.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.igris.companion.node.NodeHolder
import com.igris.companion.ui.AppRoot
import com.igris.companion.ui.theme.IgrisTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EcosystemService.start(this)
        handleShareIntent(intent)
        setContent {
            IgrisTheme {
                AppRoot(onRequestNotifications = ::requestNotifications)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Share-target entry ("Share -> IGRIS", ACTION_SEND text/plain): push the
     * shared text into the sync fan-out. The text arrives in the intent
     * extras, so no clipboard read is involved — works regardless of the
     * Android 10+ background-read restrictions.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrEmpty()) return

        NodeHolder.start(this)
        Toast.makeText(this, R.string.shared_to_igris, Toast.LENGTH_SHORT).show()
        Thread {
            val logFile = File(filesDir, "a11y.log")
            // The node boots async; retry briefly so the push is not lost.
            for (attempt in 1..10) {
                val node = NodeHolder.node
                if (node != null) {
                    try {
                        node.pushClipboard(text)
                        logFile.appendText("${System.currentTimeMillis()} share: pushed ${text.take(24)}\n")
                    } catch (e: Exception) {
                        logFile.appendText("${System.currentTimeMillis()} share: push failed: $e\n")
                    }
                    return@Thread
                }
                Thread.sleep(300)
            }
            logFile.appendText("${System.currentTimeMillis()} share: node never came up\n")
        }.start()
    }

    /** Fire the POST_NOTIFICATIONS system dialog (no-op below 33 / if already granted). */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
