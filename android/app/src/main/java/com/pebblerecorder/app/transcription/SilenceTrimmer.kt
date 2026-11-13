package com.pebblerecorder.app.transcription

import kotlin.math.sqrt

private const val SAMPLE_RATE = 16_000
private const val FRAME = SAMPLE_RATE / 50 // 20 ms
private const val SILENCE_RMS = 0.01f * 32768f // about -40 dBFS: quieter than a room's background hum
private const val MIN_GAP_FRAMES = 2 * 50 // only gaps of 2 s or more are worth cutting
private const val KEEP_FRAMES = 10 // 0.2 s of the silence is kept on each side of a cut

/**
 * Cuts long silent stretches out of 16 kHz mono audio before whisper sees it. Whisper spends the
 * same time on silence as on speech, so a recording with long pauses transcribes noticeably faster
 * without them. Short pauses stay as they are - they matter for phrasing.
 */
internal object SilenceTrimmer {
    /**
     * Trims the first [length] samples of [samples] in place and returns the new length. In place
     * because a long recording is most of the app's memory; the write position never passes the
     * read position, so nothing is overwritten before it has been copied.
     */
    fun trim(samples: ShortArray, length: Int = samples.size): Int {
        val frames = length / FRAME
        val silent = BooleanArray(frames) { rms(samples, it * FRAME) < SILENCE_RMS }

        var size = 0
        var frame = 0
        while (frame < frames) {
            var end = frame
            if (silent[frame]) {
                while (end < frames && silent[end]) end++
                val gap = end - frame
                if (gap >= MIN_GAP_FRAMES) {
                    // Keep the edges of the gap, drop the middle.
                    size = copy(samples, frame, KEEP_FRAMES, size)
                    size = copy(samples, end - KEEP_FRAMES, KEEP_FRAMES, size)
                    frame = end
                    continue
                }
            }
            end = maxOf(end, frame + 1)
            size = copy(samples, frame, end - frame, size)
            frame = end
        }
        // The tail that isn't a whole frame.
        val tail = length - frames * FRAME
        System.arraycopy(samples, frames * FRAME, samples, size, tail)
        return size + tail
    }

    private fun copy(samples: ShortArray, startFrame: Int, frameCount: Int, size: Int): Int {
        System.arraycopy(samples, startFrame * FRAME, samples, size, frameCount * FRAME)
        return size + frameCount * FRAME
    }

    private fun rms(samples: ShortArray, start: Int): Float {
        var sum = 0f
        for (i in start until start + FRAME) sum += samples[i].toFloat() * samples[i]
        return sqrt(sum / FRAME)
    }
}
