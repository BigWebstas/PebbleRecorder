package com.pebblerecorder.app.connectivity

import kotlin.math.log10

/**
 * The mic level stream to the watch. The mic is sampled every [SAMPLE_MS] and the samples go out
 * in batches of [SAMPLES_PER_SEND], one message per batch, so the watch can play them back
 * smoothly without the phone sending more messages.
 */
object WatchLevels {
    const val SAMPLE_MS = 100L
    const val SAMPLES_PER_SEND = 4

    /** Maps a peak sample (0..32767) onto 0..100 over -60..0 dBFS: quiet rooms read near zero, speech mid-to-high. */
    fun toLevel(amplitude: Int): Int {
        val db = 20 * log10(amplitude.coerceAtLeast(1) / 32768.0)
        return ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)
    }

    /** One byte per sample, oldest first, for the watch's LEVELS key. */
    fun toBytes(amplitudes: IntArray): ByteArray = ByteArray(amplitudes.size) { toLevel(amplitudes[it]).toByte() }
}
