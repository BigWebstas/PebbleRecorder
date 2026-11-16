package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for transcription failing with OutOfMemoryError on recordings over ~30 minutes:
 * the decoder kept 16 kHz samples as floats in an array it doubled (copying it) whenever it filled,
 * which for a 32-minute recording asked for a 246 MB array on a 256 MB heap.
 */
class AudioDecoderTest {
    private val rate = 16_000

    @Test
    fun theBufferIsSizedFromTheDurationSoItNeverHasToGrow() {
        val hour = 3600L * 1_000_000
        val buffer = AudioDecoder.SampleBuffer(AudioDecoder.estimatedSamples(hour))
        val initial = buffer.data
        repeat(3600 * rate) { buffer.add(1) }
        assertEquals(3600 * rate, buffer.size)
        // Same array: nothing was copied along the way.
        assertTrue(buffer.data === initial)
    }

    @Test
    fun aWrongEstimateGrowsByAFixedStepNotByDoubling() {
        val buffer = AudioDecoder.SampleBuffer(rate)
        val samples = 90 * 60 * rate // 90 minutes from a one-second estimate
        var largestCapacity = 0
        var growths = 0
        var capacity = buffer.data.size
        repeat(samples) {
            buffer.add(1)
            if (buffer.data.size != capacity) {
                growths++
                capacity = buffer.data.size
                largestCapacity = maxOf(largestCapacity, capacity)
            }
        }
        // Capacity stays within one step of what's needed, so a copy never needs much more than
        // the audio itself (doubling would have reached 2x).
        assertTrue(buffer.data.size - samples <= AudioDecoder.SampleBuffer.GROW_STEP)
        assertTrue(largestCapacity < samples + AudioDecoder.SampleBuffer.GROW_STEP + 1)
        assertTrue(growths > 0)
    }

    @Test
    fun aMissingDurationStillGetsAUsableBuffer() {
        assertTrue(AudioDecoder.estimatedSamples(0) > 0)
    }

    @Test
    fun twoHoursOfAudioFitsInHalfTheMemoryOfFloats() {
        // 2 h at 16 kHz: shorts are 230 MB where the old float array was 460 MB, and the decode
        // + trim path no longer makes a second copy of it.
        val samples = 2 * 3600 * rate
        val buffer = AudioDecoder.SampleBuffer(AudioDecoder.estimatedSamples(2 * 3600L * 1_000_000))
        val resampler = AudioDecoder.Resampler(48_000)
        // Feed 48 kHz input for 10 minutes; check the resampled count is 1/3 as long.
        repeat(10 * 60 * 48_000) { resampler.add(1000f, buffer) }
        assertTrue(kotlin.math.abs(buffer.size - 10 * 60 * rate) <= 2)
        assertTrue(buffer.data.size >= samples)
    }

    @Test
    fun resamplingKeepsTheSignalsLevel() {
        val buffer = AudioDecoder.SampleBuffer(rate * 2)
        val resampler = AudioDecoder.Resampler(16_000)
        repeat(rate) { resampler.add(12_345f, buffer) }
        assertEquals(12_345, buffer.data[rate / 2].toInt())
    }
}
