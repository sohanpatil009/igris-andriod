package com.igris.companion.calls

import android.content.Context
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.igris.companion.node.NodeHolder
import java.util.UUID

/**
 * Enterprise call + SMS bridge (Igris native).
 * Observes phone state and forwards enterprise call events into the Rust core,
 * which fans them out to trusted peers over the call sync endpoint.
 * SMS sending uses the Rust sms sync endpoint; incoming SMS
 * from peers surface via `onSmsReceived`.
 */
object CallSmsBridge {
    private const val TAG = "IGRIS"

    @Volatile
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE

    fun start(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    val event = when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> "ringing"
                        TelephonyManager.CALL_STATE_OFFHOOK -> "talking"
                        else -> if (lastState != TelephonyManager.CALL_STATE_IDLE) "ended" else return
                    }
                    lastState = state
                    pushCall(event, phoneNumber.orEmpty())
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "call bridge: READ_PHONE_STATE not granted: $e")
        } catch (t: Throwable) {
            Log.w(TAG, "call bridge start failed", t)
        }
    }

    fun pushCall(event: String, phoneNumber: String, contactName: String = "") {
        try {
            val node = NodeHolder.node ?: return
            node.pushCall(
                callId = UUID.randomUUID().toString(),
                event = event,
                phoneNumber = phoneNumber,
                contactName = contactName,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "pushCall failed", t)
        }
    }

    /**
     * Push a snapshot of the phone's call log (newest 200 rows) to trusted
     * peers so the desktop mirrors the call history. Needs READ_CALL_LOG.
     * Call once after the node starts (or after reconnect), not per row.
     */
    fun syncCallLog(context: Context) {
        try {
            val node = NodeHolder.node ?: return
            val entries = readCallLog(context)
            if (entries.isEmpty()) {
                Log.d(TAG, "call log sync: no entries (missing grant?)")
                return
            }
            node.pushCallLog(entries)
            Log.d(TAG, "call log sync: pushed ${entries.size} rows")
        } catch (e: SecurityException) {
            Log.w(TAG, "call log sync: READ_CALL_LOG not granted: $e")
        } catch (t: Throwable) {
            Log.w(TAG, "call log sync failed", t)
        }
    }

    private fun readCallLog(context: Context): List<uniffi.igris_protocol.CallLogEntryInfo> {
        val out = mutableListOf<uniffi.igris_protocol.CallLogEntryInfo>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT 200",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            while (c.moveToNext()) {
                val rowId = c.getLong(iId)
                val number = c.getString(iNum).orEmpty()
                val date = c.getLong(iDate)
                val duration = c.getLong(iDur)
                val type = c.getInt(iType)
                val name = if (iName >= 0) c.getString(iName).orEmpty() else ""
                out.add(
                    uniffi.igris_protocol.CallLogEntryInfo(
                        callId = rowId.toString(),
                        event = eventFor(type, duration),
                        phoneNumber = number,
                        contactName = name,
                        timestamp = date,
                        durationSeconds = duration,
                        callType = typeFor(type),
                    ),
                )
            }
        }
        return out
    }

    private fun eventFor(type: Int, duration: Long): String = when (type) {
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.INCOMING_TYPE -> if (duration > 0) "answered" else "ringing"
        CallLog.Calls.OUTGOING_TYPE -> "talking"
        CallLog.Calls.REJECTED_TYPE -> "missed"
        else -> "ended"
    }

    private fun typeFor(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
        else -> "unknown"
    }

    fun pushSms(threadId: String, address: String, body: String) {
        try {
            val node = NodeHolder.node ?: return
            node.pushSms(
                smsId = UUID.randomUUID().toString(),
                threadId = threadId,
                address = address,
                body = body,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "pushSms failed", t)
        }
    }

    /**
     * Move the ongoing call's audio to the paired Bluetooth handsfree
     * endpoint (the linked PC as a phone-audio accessory): the PC's
     * microphone becomes the call's uplink, its speakers the downlink.
     * Reads only as "this line is offhook" — the eco core chooses when.
     */
    fun startCallAudioRoute(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val scoDevice = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDevice == null) {
                Log.w(TAG, "audio route: no BT SCO device connected (pair the PC as phone audio)")
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
                am.setCommunicationDevice(scoDevice)
            } else {
                @Suppress("DEPRECATION")
                {
                    am.mode = android.media.AudioManager.MODE_IN_CALL
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
            }
            Log.d(TAG, "call audio routed to BT SCO (linked PC handsfree)")
        } catch (t: Throwable) {
            Log.w(TAG, "audio route failed: $t")
        }
    }

    /** Drop the Bluetooth call-audio routing; restores the handset's own
     *  audio path. Called by the eco core when the call ends. */
    fun stopCallAudioRoute(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
                if (am.mode != android.media.AudioManager.MODE_NORMAL) {
                    am.mode = android.media.AudioManager.MODE_NORMAL
                }
            } else {
                @Suppress("DEPRECATION")
                {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                    am.mode = android.media.AudioManager.MODE_NORMAL
                }
            }
            Log.d(TAG, "call audio route cleared")
        } catch (t: Throwable) {
            Log.w(TAG, "audio route clear failed: $t")
        }
    }

    fun muteCall(callId: String) {
        try {
            NodeHolder.node?.callAction(callId, "mute", "")
        } catch (t: Throwable) {
            Log.w(TAG, "muteCall failed", t)
        }
    }

    /**
     * Execute a call control action from a linked device (desktop dial pad,
     * incoming-call popup). Audio stays on the phone — this is remote
     * control, watch-style. Returns false when it could not run.
     */
    fun executeAction(context: Context, callId: String, action: String, phoneNumber: String): Boolean {
        return try {
            val tm = context.applicationContext
                .getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                ?: return false
            when (action.lowercase()) {
                "dial" -> {
                    if (phoneNumber.isBlank()) return false
                    val uri = android.net.Uri.fromParts("tel", phoneNumber, null)
                    val extras = android.os.Bundle()
                    tm.placeCall(uri, extras)
                    pushCall("dialing", phoneNumber, "")
                }
                "accept" -> tm.acceptRingingCall()
                "decline", "end" -> @Suppress("DEPRECATION") tm.endCall()
                "mute" -> muteCall(callId)
                // The eco core decides when; these arms only flip the OS
                // switch. "audio" is invoked once the line is actually
                // offhook (a desktop-initiated answer that wants the call
                // carried by the linked PC, paired as a Bluetooth handsfree
                // accessory — the PC's mic is the uplink, its speakers the
                // downlink).
                "audio" -> startCallAudioRoute(context)
                "audio_off" -> stopCallAudioRoute(context)
                else -> return false
            }
            Log.d(TAG, "call action executed: $action")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "call action needs phone permission: $action ($e)")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "call action failed: $action", t)
            false
        }
    }
}
