package com.pebblerecorder.app.recording

import android.content.Context
import java.io.File

/**
 * A copy of every recording and transcript kept in app-private storage after delivery to the
 * recording folder. The folder is usually watched by a sync tool that moves files away, so without
 * this a failed transcription can't be retried and the audio is lost if the sync misbehaves.
 * It lives apart from the in-progress `recordings` directory, so [recoverOrphanedRecordings] never
 * mistakes a backup for an undelivered recording. Removed with the recording itself (manual delete,
 * auto-delete rule).
 */
object RecordingBackup {
    private fun dir(context: Context) = File(context.filesDir, "backups").apply { mkdirs() }

    fun audio(context: Context, name: String) = File(dir(context), name)

    fun transcript(context: Context, name: String) = File(dir(context), transcriptNameFor(name))

    fun hasAudio(context: Context, name: String) = audio(context, name).length() > 0L

    /** Moves [source] into the backup directory under [name] (a copy if it can't be renamed). */
    fun keep(context: Context, source: File, name: String) {
        moveInto(source, File(dir(context), name))
    }

    internal fun moveInto(source: File, dest: File) {
        dest.delete()
        if (source.renameTo(dest)) return
        runCatching { source.copyTo(dest, overwrite = true) }
        source.delete()
    }

    /** Stores [text] as the transcript backup for the recording named [name]. */
    fun keepTranscript(context: Context, name: String, text: String) {
        runCatching { transcript(context, name).writeText(text) }
    }

    fun delete(context: Context, name: String) {
        audio(context, name).delete()
        transcript(context, name).delete()
    }

    fun delete(context: Context, names: Collection<String>) = names.forEach { delete(context, it) }

    /** Bytes the backups take up. */
    fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
