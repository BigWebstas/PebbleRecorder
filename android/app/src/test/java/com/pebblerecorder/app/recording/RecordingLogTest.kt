package com.pebblerecorder.app.recording

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingLogTest {
    @Test
    fun entrySurvivesTheJsonRoundTrip() {
        val entry = RecordingEntry(
            name = "recording-1-rec.m4a",
            recordedAtMs = 1_700_000_000_000,
            durationSec = 125,
            audioUri = "content://x/audio",
            transcriptUri = "content://x/transcript",
            transcript = TranscriptState.DONE,
            preview = "Hello there",
            text = "Hello there, this is the whole transcript.",
        )
        assertEquals(entry, RecordingLog.fromJson(JSONObject(RecordingLog.toJson(entry).toString())))
    }

    @Test
    fun missingOptionalFieldsComeBackNull() {
        val bare = RecordingEntry("a.m4a", 1L, 0)
        val back = RecordingLog.fromJson(JSONObject(RecordingLog.toJson(bare).toString()))
        assertNull(back.audioUri)
        assertNull(back.text)
        assertEquals(TranscriptState.NONE, back.transcript)
    }
}
