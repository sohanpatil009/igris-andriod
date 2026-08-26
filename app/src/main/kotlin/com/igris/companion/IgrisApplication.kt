package com.igris.companion

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Process-wide foreground flag (set by lifecycle callbacks). */
object ForegroundState {
    @Volatile
    var isForeground: Boolean = false
}

/**
 * Registers activity lifecycle callbacks so platform seams can gate
 * foreground-only operations (Android 10+ background clipboard reads are
 * denied by the OS; touching the ClipboardManager while backgrounded only
 * produces system_server denial log spam).
 */
class IgrisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ForegroundState.isForeground = true
            }

            override fun onActivityPaused(activity: Activity) {
                ForegroundState.isForeground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }
}