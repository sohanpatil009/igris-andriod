package com.igris.companion.identity

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.first

private val Context.identityDataStore by preferencesDataStore(name = "identity")

data class Identity(val deviceId: String, val name: String)

/**
 * Persisted device identity. The lowercase UUID is the canonical device_id the
 * whole ecosystem keys trust on, so it must survive restarts (DataStore).
 */
object IdentityStore {
    private val DEVICE_ID = stringPreferencesKey("device_id")
    private val DEVICE_NAME = stringPreferencesKey("device_name")

    suspend fun get(context: Context): Identity {
        val prefs = context.identityDataStore.data.first()
        var deviceId = prefs[DEVICE_ID]
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().lowercase()
            context.identityDataStore.edit { prefs ->
                prefs[DEVICE_ID] = deviceId
                prefs[DEVICE_NAME] = "IGRIS-${android.os.Build.MODEL}"
            }
        }
        val name = prefs[DEVICE_NAME] ?: "IGRIS-${android.os.Build.MODEL}"
        return Identity(deviceId, name)
    }
}
