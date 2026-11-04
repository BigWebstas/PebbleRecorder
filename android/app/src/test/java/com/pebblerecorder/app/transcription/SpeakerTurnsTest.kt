package com.pebblerecorder.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerTurnsTest {
    @Test
    fun alternatesBetweenTwoSpeakers() {
        val raw = " Hi there. [SPEAKER_TURN] Hello! How are you? [SPEAKER_TURN] Good. [SPEAKER_TURN] Great."
        assertEquals(
            "**Speaker 1:** Hi there.\n\n**Speaker 2:** Hello! How are you?\n\n" +
                "**Speaker 1:** Good.\n\n**Speaker 2:** Great.",
            labelSpeakers(raw),
        )
    }

    @Test
    fun noTurnsIsOneSpeaker() {
        assertEquals("**Speaker 1:** Just me talking.", labelSpeakers(" Just me talking. "))
    }

    @Test
    fun trailingMarkerLeavesNoEmptyTurn() {
        assertEquals("**Speaker 1:** Done.", labelSpeakers("Done. [SPEAKER_TURN]"))
    }
}
