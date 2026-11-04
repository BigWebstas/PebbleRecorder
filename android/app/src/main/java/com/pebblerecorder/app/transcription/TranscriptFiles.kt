package com.pebblerecorder.app.transcription

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Reading, rewriting and deleting files that were already delivered to the recording folder. */
internal object TranscriptFiles {
    fun read(context: Context, uri: Uri): String? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull()

    /** Replaces the file's contents; false if it couldn't be written (e.g. it's gone). */
    fun write(context: Context, uri: Uri, text: String): Boolean =
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)

    /** Deletes the file, whether it lives in a chosen folder (SAF) or in Downloads (MediaStore). */
    fun delete(context: Context, uri: Uri): Boolean =
        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
}
