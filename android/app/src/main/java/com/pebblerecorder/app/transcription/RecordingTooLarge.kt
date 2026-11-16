package com.pebblerecorder.app.transcription

import android.content.Context
import android.net.Uri

/**
 * A recording is over what a cloud engine will take. Permanent, not retried: the file won't get
 * smaller. The message is what the user sees, so it says what to do about it.
 */
internal class RecordingTooLargeException(val sizeBytes: Long, val limitBytes: Long, val engine: String) :
    IllegalStateException(tooLargeMessage(sizeBytes, limitBytes, engine))

internal fun tooLargeMessage(sizeBytes: Long, limitBytes: Long, engine: String) =
    "This recording is ${megabytes(sizeBytes)}, over the ${megabytes(limitBytes)} $engine accepts. " +
        "Use the on-device Whisper engine instead (menu > Transcribe again with Whisper)."

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / (1024.0 * 1024.0))

/** The exception if [sizeBytes] is over [limitBytes], else null. */
internal fun checkSize(sizeBytes: Long, limitBytes: Long, engine: String): RecordingTooLargeException? =
    if (sizeBytes > limitBytes) RecordingTooLargeException(sizeBytes, limitBytes, engine) else null

/** The recording's size without reading it, or -1 if it can't be told. */
internal fun sizeOf(context: Context, uri: Uri): Long =
    runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
