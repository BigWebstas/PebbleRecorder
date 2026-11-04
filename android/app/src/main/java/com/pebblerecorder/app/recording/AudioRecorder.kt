package com.pebblerecorder.app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log

/**
 * One MediaRecorder session writing into a [RecordingTarget]'s private file. Not thread-safe by
 * itself: PebbleListenerService serializes the calls that change state; the read-only getters
 * are safe to poll from its level-stream loop.
 */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: ParcelFileDescriptor? = null

    // Recording clock (SystemClock.elapsedRealtime, ms). Pauses are subtracted so the watch can
    // re-sync its timer after it was closed or disconnected mid-recording.
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotalMs = 0L

    var target: RecordingTarget? = null
        private set
    var isPaused = false
        private set

    val isActive: Boolean get() = recorder != null

    /** Throws if the recorder can't be set up; the caller then cleans up with [release]. */
    fun start(target: RecordingTarget, format: RecordingFormat) {
        val pfd = ParcelFileDescriptor.open(
            target.file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        outputFile = pfd
        this.target = target

        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            format.configure(this, RecordingSettings.quality(context))
            RecordingSettings.preferredMic(context)?.let { setPreferredDevice(it) }
            setOutputFile(pfd.fileDescriptor)
            prepare()
            start()
        }

        startedAt = SystemClock.elapsedRealtime()
        pausedTotalMs = 0
        pausedAt = 0
    }

    fun pause() {
        recorder?.pause()
        isPaused = true
        pausedAt = SystemClock.elapsedRealtime()
    }

    fun resume() {
        recorder?.resume()
        isPaused = false
        pausedTotalMs += SystemClock.elapsedRealtime() - pausedAt
        pausedAt = 0
    }

    /** Stops the recorder (throws if it recorded nothing usable). Call [release] afterwards. */
    fun stop() {
        recorder?.stop()
    }

    fun release() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MediaRecorder", e)
        }
        recorder = null

        try {
            outputFile?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close output file descriptor", e)
        }
        outputFile = null
        target = null
        isPaused = false
    }

    fun elapsedSeconds(): Int {
        if (recorder == null) return 0
        val now = if (isPaused) pausedAt else SystemClock.elapsedRealtime()
        return ((now - startedAt - pausedTotalMs) / 1000).toInt().coerceAtLeast(0)
    }

    fun sizeBytes(): Long = target?.file?.length() ?: 0L

    /** Peak amplitude (0..32767) since the last call. */
    fun maxAmplitude(): Int? = runCatching { recorder?.maxAmplitude }.getOrNull()

    private companion object {
        const val TAG = "AudioRecorder"
    }
}
