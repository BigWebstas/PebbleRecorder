package com.pebblerecorder.app.recording

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRetentionTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, minute, second) }.timeInMillis

    private fun entry(name: String, ms: Long) = RecordingEntry(name, ms, 10, audioUri = "content://x/$name")

    @Test
    fun sevenDaysDeletesDayOneJustAfterMidnightOnDayEight() {
        val entries = listOf(entry("day1-late", at(1, 23, 59)), entry("day2", at(2, 0, 0)))
        val justAfterMidnight = at(8, 0, 0, 30)
        assertEquals(listOf("day1-late"), RecordingRetention.expired(entries, 7, justAfterMidnight, utc).map { it.name })
    }

    @Test
    fun nothingIsDeletedOnDaySeven() {
        val entries = listOf(entry("day1", at(1, 8)))
        assertEquals(emptyList<RecordingEntry>(), RecordingRetention.expired(entries, 7, at(7, 23, 59), utc))
    }

    @Test
    fun neverMeansNothingExpires() {
        assertEquals(emptyList<RecordingEntry>(), RecordingRetention.expired(listOf(entry("old", 0L)), 0, at(20), utc))
    }

    @Test
    fun entriesWithoutFilesAreLeftAlone() {
        val noFiles = RecordingEntry("failed", at(1), 5)
        assertEquals(emptyList<RecordingEntry>(), RecordingRetention.expired(listOf(noFiles), 7, at(20), utc))
    }

    @Test
    fun midnightIsCountedFromNow() {
        assertEquals(6 * 3600_000L, RecordingRetention.millisUntilNextMidnight(at(3, 18), utc))
    }
}
