package com.igris.companion.node

import android.content.Context
import android.os.Build
import android.util.Log
import com.igris.companion.identity.IdentityStore
import com.igris.companion.providers.AppEventListener
import com.igris.companion.providers.PlatformProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.igris_protocol.EcosystemNode
import uniffi.igris_protocol.NodeConfig

/**
 * Process-wide holder for the shared Rust node. Built once by the foreground
 * service, observed by the UI. All UniFFI calls are blocking, so they run on
 * the IO dispatcher.
 */
object NodeHolder {
    private const val TAG = "IGRIS"

    @Volatile
    var node: EcosystemNode? = null
        private set

    val isStarted: Boolean
        get() = node != null

    private val lock = Any()
    private var starting = false
    private var scope: CoroutineScope? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (node != null || starting) return
            starting = true
        }
        val app = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            try {
                val identity = IdentityStore.get(app)
                val dataDir = app.filesDir.absolutePath
                val downloadsDir = app.filesDir.resolve("downloads").absolutePath
                val config = NodeConfig(
                    deviceId = identity.deviceId,
                    name = identity.name,
                    model = Build.MODEL,
                    dataDir = dataDir,
                    downloadsDir = downloadsDir,
                    servePlainHttp = false,
                    clipboardEnabled = true,
                    notificationEnabled = true,
                )
                val providers = PlatformProviders(app)
                AppEventListener.appContext = app
                AppEventListener.localDeviceId = identity.deviceId
                val n = EcosystemNode(
                    config = config,
                    clipboardProvider = providers.clipboard,
                    notificationProvider = providers.notification,
                    listener = AppEventListener,
                )
                n.start()
                node = n
                Log.i(TAG, "node started: id=${identity.deviceId} model=${Build.MODEL}")
            } catch (t: Throwable) {
                Log.e(TAG, "node failed to start", t)
            } finally {
                synchronized(lock) {
                    starting = false
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        try {
            node?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "node shutdown error", t)
        }
        node = null
    }
}
