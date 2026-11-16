package com.pebblerecorder.app.transcription

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "mm:ss" (or "h:mm:ss") for [seconds]. */
internal fun clockText(seconds: Int): String =
    if (seconds >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

/**
 * The saved transcript file: a front-matter header (when it was recorded, where, bookmarks), then
 * an optional summary above the transcript itself, then the bookmark list.
 */
internal fun transcriptMarkdown(
    transcript: String,
    recordedAtMs: Long,
    latitude: Double?,
    longitude: Double?,
    locationTag: String?,
    summary: String?,
    bookmarkSeconds: List<Int>,
): String = buildString {
    append("---\n")
    append("recorded: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(recordedAtMs))}\n")
    if (latitude != null && longitude != null) {
        append("location: $latitude, $longitude\n")
        append("maps: https://www.google.com/maps?q=$latitude,$longitude\n")
    }
    if (bookmarkSeconds.isNotEmpty()) append("bookmarks: ${bookmarkSeconds.joinToString(", ") { clockText(it) }}\n")
    append("---\n\n")
    if (locationTag != null) append("$locationTag\n\n")
    if (summary != null) {
        append("## Summary\n\n$summary\n\n## Transcript\n\n")
    }
    append(transcript)
    if (bookmarkSeconds.isNotEmpty()) {
        append("\n\n## Bookmarks\n\n")
        bookmarkSeconds.forEach { append("- ${clockText(it)}\n") }
    }
}

/** A saved transcript file taken apart, so one section can be replaced without touching the rest. */
internal class TranscriptParts(
    /** Front matter plus the location tag, if any. */
    val head: String,
    val summary: String?,
    val transcript: String,
    /** The "## Bookmarks" list, including its leading blank line, or empty. */
    val bookmarks: String,
) {
    fun markdown(): String = buildString {
        append(head)
        if (summary != null) append("## Summary\n\n$summary\n\n## Transcript\n\n")
        append(transcript)
        append(bookmarks)
    }
}

/** Inverse of [transcriptMarkdown]. */
internal fun splitTranscriptMarkdown(markdown: String): TranscriptParts {
    var head = ""
    var rest = markdown
    if (markdown.startsWith("---\n")) {
        val end = markdown.indexOf("\n---\n\n", 3)
        if (end >= 0) {
            head = markdown.substring(0, end + 6)
            rest = markdown.substring(end + 6)
        }
    }
    // The location tag is a single "#word" line.
    val tagEnd = rest.indexOf("\n\n")
    if (rest.startsWith("#") && !rest.startsWith("##") && tagEnd > 0 && rest.substring(0, tagEnd).none { it.isWhitespace() }) {
        head += rest.substring(0, tagEnd + 2)
        rest = rest.substring(tagEnd + 2)
    }
    var summary: String? = null
    if (rest.startsWith("## Summary\n\n")) {
        val marker = "\n\n## Transcript\n\n"
        val end = rest.indexOf(marker)
        if (end >= 0) {
            summary = rest.substring("## Summary\n\n".length, end)
            rest = rest.substring(end + marker.length)
        }
    }
    var bookmarks = ""
    val bookmarkStart = rest.lastIndexOf("\n\n## Bookmarks\n\n")
    if (bookmarkStart >= 0) {
        bookmarks = rest.substring(bookmarkStart)
        rest = rest.substring(0, bookmarkStart)
    }
    return TranscriptParts(head, summary, rest, bookmarks)
}
