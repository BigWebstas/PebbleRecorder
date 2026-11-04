package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerNamesTest {
    private val transcript = "---\nrecorded: x\n---\n\n**Speaker 1:** Hi.\n\n**Speaker 2:** Hello.\n\n**Speaker 1:** Bye."

    @Test
    fun findsEachSpeakerOnceInOrder() {
        assertEquals(listOf("Speaker 1", "Speaker 2"), speakersIn(transcript))
    }

    @Test
    fun renamesOnlyTheChosenSpeaker() {
        val out = renameSpeakers(transcript, mapOf("Speaker 1" to "Sam"))
        assertEquals(listOf("Sam", "Speaker 2"), speakersIn(out))
        assertEquals(true, out.contains("**Sam:** Bye."))
    }

    @Test
    fun blankNameKeepsTheLabel() {
        assertEquals(transcript, renameSpeakers(transcript, mapOf("Speaker 1" to "  ")))
    }

    @Test
    fun boldTextMidSentenceIsNotASpeaker() {
        assertEquals(emptyList<String>(), speakersIn("He said **Note:** this."))
    }
}
