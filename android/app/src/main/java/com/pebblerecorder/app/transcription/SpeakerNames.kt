package com.pebblerecorder.app.transcription

// A speaker turn as the cloud engines and labelSpeakers write it: "**Speaker 1:** ..." (or "**Speaker 1**: ...") at the start of a line.
private val LABEL = Regex("""(?m)^\*\*([^*\n:]{1,40})(?::\*\*|\*\*:)""")

/** The speaker labels used in [transcript], in order of first appearance. */
internal fun speakersIn(transcript: String): List<String> =
    LABEL.findAll(transcript).map { it.groupValues[1].trim() }.distinct().toList()

/** Replaces speaker labels per [names] (old label -> new label); labels not in [names] are left alone. */
internal fun renameSpeakers(transcript: String, names: Map<String, String>): String {
    val validNames = names.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
    if (validNames.isEmpty()) return transcript
    val labeled = LABEL.replace(transcript) { match ->
        val label = match.groupValues[1].trim()
        "**${validNames[label] ?: label}:**"
    }
    return validNames.entries.fold(labeled) { acc, (old, new) ->
        acc.replace(Regex("\\b${Regex.escape(old)}\\b"), new)
    }
}
