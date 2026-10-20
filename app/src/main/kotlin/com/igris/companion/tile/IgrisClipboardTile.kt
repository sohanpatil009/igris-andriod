package com.igris.companion.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.igris.companion.accessibility.ClipboardGrabActivity
import com.igris.companion.node.NodeHolder

/**
 * Quick Settings tile: "Sync clipboard now".
 *
 * HyperOS withholds background clipboard-change callbacks, so the automatic
 * grab path has no trigger while the app is backgrounded. A tile tap is a
 * user-initiated foreground launch, which is always legal — it raises the
 * transparent grab activity, which reads the clipboard while focused and
 * pushes to linked peers. One swipe + tap, no app switch.
 */
class IgrisClipboardTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // The node may not be up (fresh boot, service killed); the grab
        // activity retries its push while the node boots.
        NodeHolder.start(this)
        // Bookkeeping: if even this user-initiated launch is dropped by the
        // OS, the watchdog posts the tap-to-sync notification instead.
        com.igris.companion.accessibility.SyncFallbackNotifier
            .markGrabRequested(this, System.currentTimeMillis())
        val intent = Intent(this, ClipboardGrabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
