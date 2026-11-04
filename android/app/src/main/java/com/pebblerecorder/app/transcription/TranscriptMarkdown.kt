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
