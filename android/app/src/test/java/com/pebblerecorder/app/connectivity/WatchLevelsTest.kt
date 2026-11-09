package com.pebblerecorder.app.connectivity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchLevelsTest {
    @Test
    fun mapsMinus60To0DbfsOnto0To100() {
        assertEquals(0, WatchLevels.toLevel(0))
        assertEquals(0, WatchLevels.toLevel(32)) // about -60 dBFS
        assertEquals(99, WatchLevels.toLevel(32767)) // full scale rounds down
        // The silence auto-stop threshold (1000, about -30.3 dBFS) sits at 49 - watch.c's
        // SILENCE_THRESHOLD_LEVEL must match.
        assertEquals(49, WatchLevels.toLevel(1000))
    }

    @Test
    fun batchIsOneBytePerSampleOldestFirst() {
        assertArrayEquals(byteArrayOf(0, 49, 99, 0), WatchLevels.toBytes(intArrayOf(0, 1000, 32767, 0)))
    }
}
