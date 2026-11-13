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
    fun theTooLargeFlagSurvivesTheRoundTripAndDefaultsOff() {
        val failed = RecordingEntry(
            "a.m4a", 1L, 60, transcript = TranscriptState.FAILED, error = "too big", tooLarge = true,
        )
        assertEquals(failed, RecordingLog.fromJson(JSONObject(RecordingLog.toJson(failed).toString())))
        // An entry saved before the flag existed.
        val old = JSONObject(RecordingLog.toJson(failed).toString()).apply { remove("tooLarge") }
        assertEquals(false, RecordingLog.fromJson(old).tooLarge)
    }

    @Test
    fun aSummaryErrorSurvivesTheRoundTripAndDefaultsNull() {
        val entry = RecordingEntry("a.m4a", 1L, 60, transcript = TranscriptState.DONE, summaryError = "HTTP 401")
        val back = RecordingLog.fromJson(JSONObject(RecordingLog.toJson(entry).toString()))
        assertEquals("HTTP 401", back.summaryError)
        val old = JSONObject(RecordingLog.toJson(entry).toString()).apply { remove("summaryError") }
        assertNull(RecordingLog.fromJson(old).summaryError)
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
