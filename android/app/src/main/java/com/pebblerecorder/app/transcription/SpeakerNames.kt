package com.pebblerecorder.app.transcription

// A speaker turn as the cloud engines and labelSpeakers write it: "**Speaker 1:** ..." at the start of a line.
private val LABEL = Regex("""(?m)^\*\*([^*\n]{1,40}):\*\*""")

/** The speaker labels used in [transcript], in order of first appearance. */
internal fun speakersIn(transcript: String): List<String> =
    LABEL.findAll(transcript).map { it.groupValues[1] }.distinct().toList()

/** Replaces speaker labels per [names] (old label -> new label); labels not in [names] are left alone. */
internal fun renameSpeakers(transcript: String, names: Map<String, String>): String =
    LABEL.replace(transcript) { match ->
        val label = match.groupValues[1]
        "**${names[label]?.trim()?.takeIf { it.isNotEmpty() } ?: label}:**"
    }
