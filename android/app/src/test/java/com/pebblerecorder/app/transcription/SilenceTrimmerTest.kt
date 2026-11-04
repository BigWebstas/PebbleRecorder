package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTrimmerTest {
    private val rate = 16_000

    private fun tone(seconds: Int) = FloatArray(seconds * rate) { if (it % 2 == 0) 0.3f else -0.3f }
    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt())

    @Test
    fun longSilenceIsCutDownToItsEdges() {
        val input = tone(2) + silence(10.0) + tone(2)
        val out = SilenceTrimmer.trim(input)
        // Both tones survive; 10 s of silence becomes 0.2 s + 0.2 s.
        assertEquals(4 * rate + (0.4 * rate).toInt(), out.size)
    }

    @Test
    fun shortPausesAreKept() {
        val input = tone(2) + silence(1.0) + tone(2)
        assertEquals(input.size, SilenceTrimmer.trim(input).size)
    }

    @Test
    fun speechIsUntouched() {
        val input = tone(3)
        assertTrue(input.contentEquals(SilenceTrimmer.trim(input)))
    }

    @Test
    fun partialFrameAtTheEndIsKept() {
        val input = tone(1) + FloatArray(100) { 0.3f }
        assertEquals(input.size, SilenceTrimmer.trim(input).size)
    }
}
