package com.pebblerecorder.app.transcription

/**
 * Turns whisper's speaker-change markers into the labelled turns the cloud engines produce
 * ("**Speaker 1:** ..."). Tinydiarize only says *that* the speaker changed, not who is talking, so
 * speakers are assumed to alternate between two people - a third voice, or one person speaking
 * twice in a row, will be labelled wrongly.
 */
internal fun labelSpeakers(raw: String): String =
    raw.split(WhisperLib.SPEAKER_TURN_MARKER)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { index, turn -> "**Speaker ${index % 2 + 1}:** $turn" }
        .joinToString("\n\n")
