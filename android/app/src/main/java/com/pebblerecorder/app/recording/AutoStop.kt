package com.pebblerecorder.app.recording

// Peak amplitude (of 32768) below which a moment counts as silence: about -30 dBFS, above a quiet
// room's hum but below any speech.
private const val SILENCE_PEAK = 1000

/** Why a recording was stopped without anyone asking. */
enum class AutoStopReason { MAX_DURATION, SILENCE }

/**
 * Decides when a running recording should stop by itself: after [maxSeconds] of recording, or
 * after [silenceSeconds] without anything audible. A limit of 0 is off. Feed it every tick while
 * recording; it needs no clock of its own, only the recording's elapsed time.
 */
class AutoStop(private val maxSeconds: Int, private val silenceSeconds: Int) {
    private var quietSinceSecond: Int? = null

    /** Forget the quiet streak, e.g. across a pause. */
    fun reset() {
        quietSinceSecond = null
    }

    fun onTick(elapsedSeconds: Int, peakAmplitude: Int): AutoStopReason? {
        if (maxSeconds > 0 && elapsedSeconds >= maxSeconds) return AutoStopReason.MAX_DURATION
        if (silenceSeconds <= 0) return null
        if (peakAmplitude >= SILENCE_PEAK) {
            quietSinceSecond = null
            return null
        }
        val since = quietSinceSecond ?: elapsedSeconds.also { quietSinceSecond = it }
        return if (elapsedSeconds - since >= silenceSeconds) AutoStopReason.SILENCE else null
    }
}
