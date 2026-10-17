package com.igris.companion.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.igris.companion.node.NodeHolder
import java.util.concurrent.ArrayBlockingQueue

/**
 * Phone-side duplex audio engine for the IGRIS LAN call link.
 *
 * - Uplink: mic capture (48 kHz mono s16, 10 ms frames) pushed into the
 *   Rust node (`sendAudioFrame`) which forwards them over the audio TCP
 *   link to the peer.
 * - Downlink: peer frames arrive through `onAudioFrame` and are played on
 *   a media-routed `AudioTrack`.
 *
 * Both loops only run while a session is open (opened/closed by the node
 * listener through [onSessionStateChanged]).
 */
object AudioEngine {
    private const val TAG = "IGRIS"

    const val SAMPLE_RATE = 48000
    const val FRAME_SAMPLES = 480 // 10 ms mono s16
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    @Volatile
    private var open: Boolean = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    /** Bounded jitter buffer for the downlink; full queue drops frames. */
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(250)

    /** Session state from the Rust audio server (link opened/closed). */
    fun onSessionStateChanged(active: String) {
        if (active == "true") openSession() else closeSession()
    }

    /** Feed one peer frame into the playback queue (caller: Rust thread). */
    fun onAudioFrame(frame: ByteArray) {
        if (!open) return
        // Drop rather than block — realtime audio never queues unbounded.
        if (playbackQueue.offer(frame) && track == null) {
            Log.d(TAG, "audio: queued frame before track ready")
        }
    }

    @Synchronized
    private fun openSession() {
        if (open) return
        Log.i(TAG, "audio session opening")
        playbackQueue.clear()
        open = true // loops must see this before their first poll
        try {
            startPlayback()
            startCapture()
            Log.i(TAG, "audio session open (duplex)")
        } catch (t: Throwable) {
            Log.e(TAG, "audio session failed to open", t)
            closeSession()
        }
    }

    @Synchronized
    private fun closeSession() {
        if (!open && record == null && track == null) return
        open = false
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
        try { track?.stop() } catch (_: Throwable) {}
        track?.release()
        track = null
        playbackQueue.clear()
        Log.i(TAG, "audio session closed")
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO manifest+runtime-granted
    private fun startCapture() {
        val node = NodeHolder.node
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("audio capture min buffer $minBuf")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed (RECORD_AUDIO grant?)")
        }
        record = rec
        rec.startRecording()
        Thread({
            val buf = ByteArray(FRAME_BYTES)
            while (open) {
                try {
                    val n = rec.read(buf, 0, FRAME_BYTES)
                    if (n == FRAME_BYTES) {
                        // Realtime funnel: one 10 ms frame per read; the
                        // node drops quietly whenever no link is active.
                        try {
                            NodeHolder.node?.sendAudioFrame(buf)
                        } catch (t: Throwable) {
                            Log.w(TAG, "audio send failed: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "audio capture loop: ${t.message}")
                    break
                }
            }
            Log.d(TAG, "audio capture thread exited")
        }, "igris-audio-capture").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("audio playback min buffer $minBuf")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 6)
            .build()
        this.track = track
        track.play()
        Thread({
            while (open) {
                try {
                    val frame = playbackQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        track.write(frame, 0, frame.size)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "audio playback loop: ${t.message}")
                    break
                }
            }
            Log.d(TAG, "audio playback thread exited")
        }, "igris-audio-play").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }.start()
    }
}
