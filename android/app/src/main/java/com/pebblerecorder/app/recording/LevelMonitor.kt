package com.pebblerecorder.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

private const val TAG = "LevelMonitor"
private const val SAMPLE_RATE = 16_000

/**
 * A small [AudioRecord] held open just to read the mic level while no recording is running, so the
 * watch's meter works before the user presses start. Not thread-safe: PebbleListenerService calls
 * it under its own lock, together with the foreground-service type changes the mic needs.
 */
class LevelMonitor(private val context: Context) {
    private var monitor: AudioRecord? = null
    private var micKey: String? = null

    /**
     * Peak sample (0..32767) heard since the last call, opening the monitor first if needed.
     * [onOpen] runs right before the mic is opened - Android only hands the mic to a foreground
     * service of the microphone type.
     */
    @SuppressLint("MissingPermission")
    fun peak(onOpen: () -> Unit): Int? {
        // The user picked a different mic in settings: reopen on it.
        if (monitor != null && micKey != RecordingSettings.micKey(context)) stop()
        if (monitor == null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            try {
                onOpen()
                val buffer = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, SAMPLE_RATE, // 1 s of 16-bit samples
                )
                RecordingSettings.preferredMic(context)?.let { buffer.preferredDevice = it }
                if (buffer.state != AudioRecord.STATE_INITIALIZED) {
                    buffer.release()
                    return null
                }
                buffer.startRecording()
                monitor = buffer
                micKey = RecordingSettings.micKey(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open level monitor", e)
                return null
            }
        }
        val samples = ShortArray(SAMPLE_RATE / 2)
        val read = monitor?.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING) ?: return null
        if (read <= 0) return 0
        var peak = 0
        for (i in 0 until read) peak = maxOf(peak, abs(samples[i].toInt()))
        return peak
    }

    /** Releases the mic; returns whether it was open. */
    fun stop(): Boolean {
        val open = monitor ?: return false
        monitor = null
        runCatching { open.stop() }
        open.release()
        return true
    }
}
