package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTrimmerTest {
    private val rate = 16_000

    private fun tone(seconds: Int) = ShortArray(seconds * rate) { if (it % 2 == 0) 9830 else -9830 }
    private fun silence(seconds: Double) = ShortArray((seconds * rate).toInt())

    private fun trimmed(input: ShortArray): ShortArray {
        val copy = input.copyOf()
        return copy.copyOf(SilenceTrimmer.trim(copy))
    }

    @Test
    fun longSilenceIsCutDownToItsEdges() {
        val input = tone(2) + silence(10.0) + tone(2)
        // Both tones survive; 10 s of silence becomes 0.2 s + 0.2 s.
        assertEquals(4 * rate + (0.4 * rate).toInt(), trimmed(input).size)
    }

    @Test
    fun shortPausesAreKept() {
        val input = tone(2) + silence(1.0) + tone(2)
        assertEquals(input.size, trimmed(input).size)
    }

    @Test
    fun speechIsUntouched() {
        val input = tone(3)
        assertTrue(input.contentEquals(trimmed(input)))
    }

    @Test
    fun partialFrameAtTheEndIsKept() {
        val input = tone(1) + ShortArray(100) { 9830 }
        assertEquals(input.size, trimmed(input).size)
    }

    @Test
    fun trimmingInPlaceKeepsTheSpeechIntact() {
        // Distinct values per tone so a clobbered copy would show.
        val a = ShortArray(2 * rate) { 5000 }
        val b = ShortArray(2 * rate) { -7000 }
        val out = trimmed(a + silence(10.0) + b)
        assertTrue(out.sliceArray(0 until 2 * rate).all { it == 5000.toShort() })
        assertTrue(out.sliceArray(out.size - 2 * rate until out.size).all { it == (-7000).toShort() })
    }

    @Test
    fun onlyTheGivenLengthIsTrimmed() {
        // The decoder's array is longer than the audio in it.
        val audio = tone(2) + silence(10.0)
        val padded = audio + ShortArray(5 * rate) { 9830 }
        assertEquals(2 * rate + (0.4 * rate).toInt(), SilenceTrimmer.trim(padded, audio.size))
    }
}
