package com.igris.companion

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Process-wide foreground flag using lifecycle-aware tracking. */
object ForegroundState {
    @Volatile
    var isForeground: Boolean = false
}

class IgrisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                ForegroundState.isForeground = true
            }
            override fun onStop(owner: LifecycleOwner) {
                ForegroundState.isForeground = false
            }
        })
    }
}
