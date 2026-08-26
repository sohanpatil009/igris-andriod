package com.igris.companion.keepalive

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import uniffi.igris_protocol.EcosystemNode

/**
 * Holds a CPU partial [PowerManager.WakeLock] + a [WifiManager.WifiLock] while
 * at least one OTHER online **mobile** peer is present — i.e. the user's
 * "2+ mobile devices connected" rule (this phone + ≥1). The locks keep the CPU
 * and WiFi radio awake through screen-off so the 1s clipboard poll and LAN sync
 * keep running when aggressive OEM power management would otherwise freeze the
 * process. Desktop peers do NOT trigger the locks (battery-friendly).
 *
 * Acquire/release are idempotent and reference-counting is disabled, so the
 * evaluate() calls from the service loop can be made unconditionally.
 */
class KeepAliveManager(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "igris:sync").apply {
            setReferenceCounted(false)
        }

    private val wifiLock: WifiManager.WifiLock = run {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiManager.createWifiLock(mode, "igris:sync").apply { setReferenceCounted(false) }
    }

    @Volatile
    private var held = false

    /** Recompute lock state from the current peer list. */
    fun evaluate(node: EcosystemNode) {
        val mobileOnline = try {
            node.getDevices().count { it.isTrusted && it.isOnline && it.isMobile }
        } catch (t: Throwable) {
            Log.w(TAG, "keep-alive evaluate: getDevices failed", t)
            return
        }
        if (mobileOnline >= 1) acquire() else release()
    }

    @Synchronized
    private fun acquire() {
        if (held) return
        try {
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!wifiLock.isHeld) wifiLock.acquire()
            held = true
            Log.i(TAG, "keep-alive locks ACQUIRED (>=1 mobile peer online)")
        } catch (t: Throwable) {
            Log.w(TAG, "keep-alive acquire failed", t)
        }
    }

    /** Release both locks. Safe to call when nothing is held. */
    @Synchronized
    fun release() {
        if (!held) return
        try {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        } catch (t: Throwable) {
            Log.w(TAG, "keep-alive release failed", t)
        } finally {
            held = false
            Log.i(TAG, "keep-alive locks RELEASED (no mobile peer online)")
        }
    }

    companion object {
        private const val TAG = "IGRIS"
    }
}
