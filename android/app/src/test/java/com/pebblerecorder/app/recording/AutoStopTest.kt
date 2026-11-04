package com.pebblerecorder.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStopTest {
    @Test
    fun offByDefault() {
        val stop = AutoStop(0, 0)
        assertNull(stop.onTick(100_000, 0))
    }

    @Test
    fun stopsAtMaxDuration() {
        val stop = AutoStop(maxSeconds = 60, silenceSeconds = 0)
        assertNull(stop.onTick(59, 5000))
        assertEquals(AutoStopReason.MAX_DURATION, stop.onTick(60, 5000))
    }

    @Test
    fun stopsAfterEnoughSilence() {
        val stop = AutoStop(0, silenceSeconds = 120)
        assertNull(stop.onTick(10, 200))
        assertNull(stop.onTick(129, 200))
        assertEquals(AutoStopReason.SILENCE, stop.onTick(130, 200))
    }

    @Test
    fun soundRestartsTheSilenceClock() {
        val stop = AutoStop(0, silenceSeconds = 60)
        stop.onTick(0, 100)
        assertNull(stop.onTick(50, 100))
        assertNull(stop.onTick(55, 4000)) // someone spoke
        assertNull(stop.onTick(100, 100)) // quiet again - the clock starts over here
        assertNull(stop.onTick(159, 100))
        assertEquals(AutoStopReason.SILENCE, stop.onTick(160, 100))
    }

    @Test
    fun resetForgetsAQuietStreak() {
        val stop = AutoStop(0, silenceSeconds = 60)
        stop.onTick(0, 100)
        stop.reset() // e.g. the recording was paused
        assertNull(stop.onTick(61, 100))
    }
}
