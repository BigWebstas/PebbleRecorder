package com.pebblerecorder.app

import android.content.Context
import android.net.Uri

/** Persists the SAF tree URI the user picked as the recording output folder. */
object RecordingFolderPrefs {
    private const val PREFS_NAME = "recording_folder"
    private const val KEY_FOLDER_URI = "folder_uri"

    fun set(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
    }

    fun get(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
            ?.let(Uri::parse)
}
