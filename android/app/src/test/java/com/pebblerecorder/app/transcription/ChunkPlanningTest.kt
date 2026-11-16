package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanningTest {
    private val rate = 16_000

    @Test
    fun theCutLandsInTheQuietestStretchOfTheLastThirtySeconds() {
        val length = 15 * 60 * rate
        val samples = ShortArray(length) { 8000 }
        // A pause 12 s before the end, 1 s long.
        val pauseStart = length - 12 * rate
        for (i in pauseStart until pauseStart + rate) samples[i] = 0
        val cut = ChunkPlanning.quietestCut(samples, length)
        assertTrue("cut $cut not in the pause", cut in pauseStart..(pauseStart + rate))
    }

    @Test
    fun noPauseStillCutsWithinTheSearchWindow() {
        val length = 15 * 60 * rate
        val cut = ChunkPlanning.quietestCut(ShortArray(length) { 8000 }, length)
        assertTrue(cut in (length - 30 * rate)..length)
    }

    @Test
    fun aVeryShortBufferIsNotCutEarly() {
        assertEquals(100, ChunkPlanning.quietestCut(ShortArray(100), 100))
    }

    @Test
    fun bookmarksAreMappedOntoTheirPiece() {
        val bookmarks = listOf(100, 905, 1799, 1800, 4000)
        assertEquals(listOf(100), ChunkPlanning.bookmarksIn(bookmarks, 0, 900))
        // The second piece runs 900..1800: 905 -> 5, 1799 -> 899; 1800 belongs to the third.
        assertEquals(listOf(5, 899), ChunkPlanning.bookmarksIn(bookmarks, 900, 1800))
        assertEquals(listOf(0), ChunkPlanning.bookmarksIn(bookmarks, 1800, 2700))
    }

    @Test
    fun bookmarkMarkersGetThePiecesOffset() {
        val text = "Hello **[Bookmark 01:30]** there and **[Bookmark 14:59]** end"
        assertEquals(
            "Hello **[Bookmark 16:30]** there and **[Bookmark 29:59]** end",
            ChunkPlanning.offsetBookmarkMarkers(text, 900),
        )
    }

    @Test
    fun bookmarkMarkersCrossingAnHourUseTheHourFormat() {
        assertEquals(
            "**[Bookmark 1:00:30]**",
            ChunkPlanning.offsetBookmarkMarkers("**[Bookmark 10:30]**", 3000),
        )
        // And an hour-format marker coming back in is read as hours.
        assertEquals("[Bookmark 1:15:05]", ChunkPlanning.offsetBookmarkMarkers("[Bookmark 1:00:05]", 900))
    }

    @Test
    fun textWithoutMarkersIsUntouched() {
        assertEquals("Speaker 1: hi", ChunkPlanning.offsetBookmarkMarkers("Speaker 1: hi", 900))
    }

    @Test
    fun theTailStartsAtALineBoundary() {
        val previous = (1..100).joinToString("\n") { "**Speaker ${it % 2 + 1}:** line number $it of the talk" }
        val tail = ChunkPlanning.tail(previous, 200)
        assertTrue(tail.length <= 200)
        assertTrue(tail.startsWith("**Speaker"))
        assertTrue(tail.endsWith("line number 100 of the talk"))
    }

    @Test
    fun shortTextIsItsOwnTail() {
        assertEquals("short", ChunkPlanning.tail("short"))
    }

    @Test
    fun piecesAreJoinedWithABlankLineAndEmptyOnesDropped() {
        assertEquals("a\n\nb\n\nc", ChunkPlanning.join(listOf(" a ", "", "b\n", "c")))
    }
}
