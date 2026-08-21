package com.pebblerecorder.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile

private const val TAG = "RecordingTarget"
private const val DOWNLOADS_SUBFOLDER = "PebbleRecorder"

/**
 * Where a recording (and its sibling transcript, if any) actually gets written: the user's chosen
 * SAF folder (RecordingFolderPrefs) if one is set, otherwise the public Downloads/PebbleRecorder
 * folder via MediaStore - which needs no user interaction or storage permission on API 29+, so
 * recording works out of the box before anyone's opened the app's settings.
 */
sealed interface RecordingTarget {
    val uri: Uri
    val displayName: String

    /** Marks the file as no longer in-progress, now that MediaRecorder has finalized it. */
    fun finalize(context: Context)

    /** Cleans up an abandoned in-progress recording, e.g. if MediaRecorder failed to start. */
    fun delete(context: Context)

    /** Creates a new file alongside this one (same folder), e.g. for a transcript. */
    fun createSibling(context: Context, name: String, mimeType: String): Uri?

    class Saf(private val file: DocumentFile, private val finalName: String) : RecordingTarget {
        override val uri: Uri get() = file.uri
        override val displayName: String get() = finalName

        override fun finalize(context: Context) {
            if (!file.renameTo(finalName)) {
                Log.w(TAG, "Failed to rename ${file.name} to $finalName")
            }
        }

        override fun delete(context: Context) {
            file.delete()
        }

        override fun createSibling(context: Context, name: String, mimeType: String): Uri? =
            file.parentFile?.createFile(mimeType, name)?.uri
    }

    class Downloads(override val uri: Uri, override val displayName: String) : RecordingTarget {
        // MediaStore hides IS_PENDING entries from other apps automatically - no dotfile/rename
        // trick needed here the way the SAF backend needs one.
        override fun finalize(context: Context) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }

        override fun delete(context: Context) {
            context.contentResolver.delete(uri, null, null)
        }

        override fun createSibling(context: Context, name: String, mimeType: String): Uri? {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBFOLDER")
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }
    }
}

/** Only call on API 29+ (checked by the caller) - MediaStore.Downloads doesn't exist before that. */
fun createDownloadsRecordingTarget(context: Context, finalName: String): RecordingTarget.Downloads? {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBFOLDER")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    return RecordingTarget.Downloads(uri, finalName)
}
