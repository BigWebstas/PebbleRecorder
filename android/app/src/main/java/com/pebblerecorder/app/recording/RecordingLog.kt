package com.pebblerecorder.app.recording

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

// Enough to cover a week or more of recording, so the auto-delete rule can see everything it should.
private const val MAX_ENTRIES = 200
const val MAX_TEXT = 4000

/** Where a recording's transcript stands. */
enum class TranscriptState { NONE, PENDING, DONE, FAILED }

/**
 * One finished (or finishing) recording as the main screen's list shows it. [audioUri] and
 * [transcriptUri] are where the files were delivered - they point into the user's recording
 * folder, which a sync tool may well have emptied since, so they can go stale.
 */
data class RecordingEntry(
    val name: String,
    val recordedAtMs: Long,
    val durationSec: Int,
    val audioUri: String? = null,
    val transcriptUri: String? = null,
    val transcript: TranscriptState = TranscriptState.NONE,
    val preview: String? = null,
    /** The transcript itself (cut to [MAX_TEXT] characters), for searching the list. */
    val text: String? = null,
    val error: String? = null,
)

/**
 * The most recent recordings, newest first, kept in a small JSON file so the list survives
 * restarts. Reading the recording folder instead would mean scanning a SAF tree or MediaStore, and
 * couldn't know about transcripts that are still running or that failed.
 */
object RecordingLog {
    private val _entries = MutableStateFlow<List<RecordingEntry>>(emptyList())
    val entries: StateFlow<List<RecordingEntry>> = _entries

    private var loaded = false

    @Synchronized
    fun add(context: Context, entry: RecordingEntry) {
        load(context)
        _entries.value = (listOf(entry) + _entries.value.filter { it.name != entry.name }).take(MAX_ENTRIES)
        save(context)
    }

    @Synchronized
    fun update(context: Context, name: String, change: (RecordingEntry) -> RecordingEntry) {
        load(context)
        _entries.value = _entries.value.map { if (it.name == name) change(it) else it }
        save(context)
    }

    @Synchronized
    fun remove(context: Context, names: Set<String>) {
        load(context)
        _entries.value = _entries.value.filter { it.name !in names }
        save(context)
    }

    /** Loads the saved list (once) so the UI has something to show before anything new is recorded. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = file(context)
        if (!file.exists()) return
        _entries.value = runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList()).map {
            // The process died mid-transcription, so it will never finish.
            if (it.transcript == TranscriptState.PENDING) it.copy(transcript = TranscriptState.FAILED, error = "Interrupted") else it
        }
    }

    private fun save(context: Context) {
        runCatching { file(context).writeText(JSONArray(_entries.value.map(::toJson)).toString()) }
    }

    private fun file(context: Context) = File(context.filesDir, "recording_log.json")

    internal fun toJson(e: RecordingEntry) = JSONObject()
        .put("name", e.name)
        .put("recordedAtMs", e.recordedAtMs)
        .put("durationSec", e.durationSec)
        .put("audioUri", e.audioUri)
        .put("transcriptUri", e.transcriptUri)
        .put("transcript", e.transcript.name)
        .put("preview", e.preview)
        .put("text", e.text)
        .put("error", e.error)

    internal fun fromJson(o: JSONObject) = RecordingEntry(
        name = o.getString("name"),
        recordedAtMs = o.getLong("recordedAtMs"),
        durationSec = o.getInt("durationSec"),
        audioUri = o.optString("audioUri").takeIf { it.isNotEmpty() && it != "null" },
        transcriptUri = o.optString("transcriptUri").takeIf { it.isNotEmpty() && it != "null" },
        transcript = TranscriptState.entries.firstOrNull { it.name == o.optString("transcript") } ?: TranscriptState.NONE,
        preview = o.optString("preview").takeIf { it.isNotEmpty() && it != "null" },
        text = o.optString("text").takeIf { it.isNotEmpty() && it != "null" },
        error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
    )
}
