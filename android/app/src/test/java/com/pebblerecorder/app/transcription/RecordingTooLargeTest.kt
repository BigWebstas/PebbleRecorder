package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTooLargeTest {
    private val mb = 1024L * 1024

    @Test
    fun aRecordingUnderTheLimitIsFine() {
        assertNull(checkSize(19 * mb, 19 * mb, "Gemini"))
    }

    @Test
    fun theRecordingFromTheBugReportIsTooLargeForGemini() {
        // 59147313 bytes against Gemini's 19 MB inline limit.
        val e = checkSize(59_147_313L, 19 * mb, "Gemini")
        assertNotNull(e)
        assertTrue(e is IllegalStateException)
        assertEquals(59_147_313L, e!!.sizeBytes)
    }

    @Test
    fun theMessageSaysWhatToDoAboutIt() {
        val message = checkSize(59_147_313L, 19 * mb, "Gemini")!!.message!!
        assertTrue(message, "56.4 MB" in message)
        assertTrue(message, "19.0 MB" in message)
        assertTrue(message, "Gemini" in message)
        assertTrue(message, "Whisper" in message)
    }

    @Test
    fun itIsNotRetried() {
        // Retrying only happens for RetryableTranscriptionException.
        val e: Throwable? = checkSize(30 * mb, 25 * mb, "OpenAI")
        assertTrue(e !is RetryableTranscriptionException)
    }
}
