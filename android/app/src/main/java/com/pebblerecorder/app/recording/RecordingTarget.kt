package com.pebblerecorder.app.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.pebblerecorder.app.settings.RecordingFolderPrefs
import java.io.File
import java.io.IOException

private const val TAG = "RecordingTarget"
private const val DOWNLOADS_SUBFOLDER = "PebbleRecorder"

/**
 * Where a recording and its transcript (if any) end up: the user's chosen SAF folder
 * (RecordingFolderPrefs) if one is set, otherwise the public Downloads/PebbleRecorder folder via
 * MediaStore - which needs no user interaction or storage permission on API 29+, so recording
 * works out of the box before anyone's opened the app's settings.
 *
 * Both files are written into app-private storage ([file], [transcriptFile]), which no other app
 * can see, and only copied to the destination together by [finalize] once everything is done
 * (the private copies are then kept as a backup, see [RecordingBackup]).
 * Folder watchers (Syncthing, ...) therefore never see a partial file or an audio file without its
 * transcript - there's no cross-app file lock over SAF, and hidden dotfiles are still synced by
 * most tools.
 */
sealed interface RecordingTarget {
    /** The in-progress recording in app-private storage. */
    val file: File
    val displayName: String

    val uri: Uri get() = Uri.fromFile(file)

    /** Where the transcript is written before [finalize] moves it alongside the audio. */
    val transcriptFile: File
        get() = File(file.parentFile, transcriptNameFor(displayName))

    /** Copies [source] to the destination under [name]; the new file's Uri, or null on failure. */
    fun copyToDestination(context: Context, source: File, name: String, mimeType: String): Uri?

    /** Where [finalize] put the files. */
    data class Delivery(val audioUri: Uri, val transcriptUri: Uri?)

    /**
     * Copies the transcript (if one was written) and the recording to their destination, then moves
     * the private files into [RecordingBackup]. On failure the private copies are kept, so [recoverOrphanedRecordings]
     * can retry on the next start.
     */
    fun finalize(context: Context): Delivery? {
        val transcript = transcriptFile
        var transcriptUri: Uri? = null
        if (transcript.exists()) {
            transcriptUri = copyToDestination(context, transcript, transcript.name, "text/markdown") ?: return null
            RecordingBackup.keep(context, transcript, transcript.name)
        }
        val audioUri = copyToDestination(context, file, displayName, RecordingFormat.mimeTypeOf(displayName)) ?: return null
        RecordingBackup.keep(context, file, displayName)
        return Delivery(audioUri, transcriptUri)
    }

    /** Cleans up an abandoned in-progress recording, e.g. if MediaRecorder failed to start. */
    fun delete(context: Context) {
        file.delete()
        transcriptFile.delete()
    }

    class Saf(override val file: File, private val folder: DocumentFile) : RecordingTarget {
        override val displayName: String get() = file.name

        override fun copyToDestination(context: Context, source: File, name: String, mimeType: String): Uri? {
            // Copy under a dotfile name and rename when done, so a watcher can't pick up the
            // destination file half-copied.
            val partial = folder.createFile(mimeType, ".$name")
            if (partial == null) {
                Log.w(TAG, "Failed to create .$name in recording folder")
                return null
            }
            if (copyFile(context, source, partial.uri) && partial.renameTo(name)) {
                // renaming can change the document's Uri, so report the renamed file's.
                return folder.findFile(name)?.uri ?: partial.uri
            }
            Log.w(TAG, "Failed to move $name into recording folder")
            partial.delete()
            return null
        }
    }

    class Downloads(override val file: File) : RecordingTarget {
        override val displayName: String get() = file.name

        override fun copyToDestination(context: Context, source: File, name: String, mimeType: String): Uri? {
            // MediaStore hides IS_PENDING entries from other apps until the copy is done.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBFOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (dest == null) {
                Log.w(TAG, "Failed to create $name in Downloads")
                return null
            }
            if (!copyFile(context, source, dest)) {
                Log.w(TAG, "Failed to copy $name into Downloads")
                context.contentResolver.delete(dest, null, null)
                return null
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(dest, done, null, null)
            return dest
        }
    }
}

private fun copyFile(context: Context, source: File, dest: Uri): Boolean = try {
    val out = context.contentResolver.openOutputStream(dest, "wt")
    out != null && out.use { o -> source.inputStream().use { it.copyTo(o) } } >= 0
} catch (e: IOException) {
    Log.w(TAG, "Copy to $dest failed", e)
    false
}

/** The transcript's file name for the recording named [audioName]. */
fun transcriptNameFor(audioName: String) = audioName.substringBeforeLast('.').removeSuffix("-rec") + "-txt.md"

private fun recordingsDir(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

/**
 * Builds the target for a recording named [finalName]: its private file plus wherever it'll be
 * copied on finalize. Prefers the user-chosen SAF folder; falls back to Downloads/PebbleRecorder
 * (API 29+) if none is set or it's no longer writable. Null if there's nowhere to put it.
 */
fun createRecordingTarget(context: Context, finalName: String): RecordingTarget? {
    val file = File(recordingsDir(context), finalName)
    val folderUri = RecordingFolderPrefs.get(context)
    if (folderUri != null) {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
        if (folder != null && folder.canWrite()) {
            return RecordingTarget.Saf(file, folder)
        }
        Log.w(TAG, "Configured recording folder is not writable, falling back to Downloads")
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return RecordingTarget.Downloads(file)
}

/**
 * Finds recordings left in private storage by a process death mid-recording, mid-transcription or
 * mid-copy and hands each to [handle] - which should transcribe (if enabled) and deliver it, so an
 * interrupted recording ends up as complete as any other. Empty files are discarded.
 */
fun recoverOrphanedRecordings(context: Context, handle: (RecordingTarget) -> Unit) {
    recordingsDir(context).listFiles { f -> RecordingFormat.isRecordingFile(f.name) }?.forEach { file ->
        if (file.length() == 0L) {
            file.delete()
            return@forEach
        }
        createRecordingTarget(context, file.name)?.let(handle)
    }
}
