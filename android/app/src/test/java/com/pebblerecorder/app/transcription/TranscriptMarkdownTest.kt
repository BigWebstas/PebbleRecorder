package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMarkdownTest {
    @Test
    fun clockTextHandlesHours() {
        assertEquals("02:05", clockText(125))
        assertEquals("1:01:01", clockText(3661))
    }

    @Test
    fun plainTranscriptHasJustTheHeader() {
        val md = transcriptMarkdown("Hello.", 0L, null, null, null, null, emptyList())
        assertTrue(md.startsWith("---\nrecorded: "))
        assertTrue(md.endsWith("---\n\nHello."))
        assertFalse(md.contains("location:"))
    }

    @Test
    fun summaryGoesAboveTheTranscript() {
        val md = transcriptMarkdown("Hello.", 0L, null, null, "#tag", "A greeting.", emptyList())
        assertTrue(md.indexOf("#tag") < md.indexOf("## Summary"))
        assertTrue(md.indexOf("## Summary") < md.indexOf("## Transcript"))
        assertTrue(md.endsWith("## Transcript\n\nHello."))
    }

    @Test
    fun bookmarksAreInTheHeaderAndListed() {
        val md = transcriptMarkdown("Hello.", 0L, 40.5, -111.9, null, null, listOf(133, 340))
        assertTrue(md.contains("bookmarks: 02:13, 05:40\n"))
        assertTrue(md.contains("location: 40.5, -111.9\n"))
        assertTrue(md.endsWith("## Bookmarks\n\n- 02:13\n- 05:40\n"))
    }
}
