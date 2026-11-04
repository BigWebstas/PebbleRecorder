package com.pebblerecorder.app.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pebblerecorder.app.R
import com.pebblerecorder.app.connectivity.PebbleListenerService
import com.pebblerecorder.app.recording.RecordingEntry
import com.pebblerecorder.app.recording.RecordingLog
import com.pebblerecorder.app.recording.TranscriptState
import com.pebblerecorder.app.settings.TranscriptionEngine
import com.pebblerecorder.app.transcription.TranscriptFiles
import com.pebblerecorder.app.transcription.renameSpeakers
import com.pebblerecorder.app.transcription.speakersIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHOWN = 10
private const val MENU_RENAME = 100
private const val MENU_DELETE = 101

/**
 * The "Recent recordings" list on the main screen: when, how long, a transcript preview and its
 * status, with play, share and retry. Rows are rebuilt whenever [RecordingLog] changes - there are
 * only a handful.
 */
class RecordingsList(
    private val activity: AppCompatActivity,
    private val container: LinearLayout,
    private val empty: View,
    private val searchLayout: View,
    searchInput: EditText,
) {
    private var entries: List<RecordingEntry> = emptyList()
    private var query = ""
    private var player: MediaPlayer? = null
    private var playing: String? = null

    init {
        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim()
                    render()
                }
            },
        )
    }

    fun show(newEntries: List<RecordingEntry>) {
        entries = newEntries
        render()
    }

    /** Stops playback; call when the screen goes away. */
    fun release() {
        stopPlayback()
    }

    private fun render() {
        searchLayout.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        // Searching looks through everything kept; otherwise just the newest few.
        val shown = if (query.isEmpty()) entries.take(SHOWN) else entries.filter(::matches)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        container.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        shown.forEach { entry -> container.addView(row(inflater, entry)) }
    }

    private fun matches(entry: RecordingEntry) =
        listOfNotNull(entry.name, entry.text ?: entry.preview).any { it.contains(query, ignoreCase = true) }

    private fun row(inflater: LayoutInflater, entry: RecordingEntry): View {
        val view = inflater.inflate(R.layout.item_recording, container, false)
        view.findViewById<TextView>(R.id.recording_title).text =
            "${whenText(entry.recordedAtMs)} · ${clock(entry.durationSec)}"

        val preview = view.findViewById<TextView>(R.id.recording_preview)
        preview.visibility = if (entry.preview == null) View.GONE else View.VISIBLE
        preview.text = entry.preview
        // Tap the preview to read all of it (it's cut to two lines otherwise).
        preview.setOnClickListener { preview.maxLines = if (preview.maxLines == 2) Int.MAX_VALUE else 2 }

        val play = view.findViewById<ImageButton>(R.id.play_button)
        play.visibility = if (entry.audioUri == null) View.INVISIBLE else View.VISIBLE
        play.setImageResource(if (playing == entry.name) R.drawable.ic_pause else R.drawable.ic_play)
        play.setOnClickListener { togglePlayback(entry) }

        val share = view.findViewById<ImageButton>(R.id.share_button)
        share.visibility = if (entry.transcript == TranscriptState.DONE) View.VISIBLE else View.GONE
        share.setOnClickListener { shareTranscript(entry) }

        view.findViewById<ImageButton>(R.id.more_button).setOnClickListener { showMenu(it, entry) }

        bindStatus(view.findViewById(R.id.recording_status_chip), entry)
        return view
    }

    private fun bindStatus(chip: Chip, entry: RecordingEntry) {
        chip.isClickable = false
        chip.setOnClickListener(null)
        when (entry.transcript) {
            TranscriptState.NONE -> chip.visibility = View.GONE
            TranscriptState.PENDING -> {
                chip.visibility = View.VISIBLE
                chip.setText(R.string.chip_transcribing)
                chip.setChipIconResource(R.drawable.ic_expand_more)
                chip.isChipIconVisible = false
            }
            TranscriptState.DONE -> {
                chip.visibility = View.VISIBLE
                chip.setText(R.string.chip_transcribed)
                chip.setChipIconResource(R.drawable.ic_check)
                chip.isChipIconVisible = true
            }
            TranscriptState.FAILED -> {
                chip.visibility = View.VISIBLE
                chip.setText(R.string.chip_failed)
                chip.setChipIconResource(R.drawable.ic_warning)
                chip.isChipIconVisible = true
                chip.setOnClickListener { PebbleListenerService.retryTranscription(activity, entry.name) }
                // The reason is a long-press away.
                chip.setOnLongClickListener {
                    Toast.makeText(activity, entry.error ?: "", Toast.LENGTH_LONG).show()
                    true
                }
            }
        }
    }

    // ---- Row menu: transcribe again, rename speakers, delete ---------------------------------------

    private fun showMenu(anchor: View, entry: RecordingEntry) {
        val menu = PopupMenu(activity, anchor)
        val engines = TranscriptionEngine.entries
        val canRetranscribe = entry.audioUri != null && entry.transcript != TranscriptState.PENDING
        if (canRetranscribe) {
            engines.forEachIndexed { i, engine ->
                menu.menu.add(0, i, i, activity.getString(R.string.menu_retranscribe, engineName(engine)))
            }
        }
        if (entry.transcript == TranscriptState.DONE && entry.transcriptUri != null) {
            menu.menu.add(0, MENU_RENAME, MENU_RENAME, R.string.menu_rename_speakers)
        }
        menu.menu.add(0, MENU_DELETE, MENU_DELETE, R.string.menu_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> renameSpeakers(entry)
                MENU_DELETE -> confirmDelete(entry)
                else -> PebbleListenerService.retryTranscription(activity, entry.name, engines[item.itemId])
            }
            true
        }
        menu.show()
    }

    private fun engineName(engine: TranscriptionEngine) = activity.getString(
        when (engine) {
            TranscriptionEngine.GEMINI -> R.string.engine_gemini_short
            TranscriptionEngine.OPENAI -> R.string.engine_openai_short
            TranscriptionEngine.WHISPER -> R.string.engine_whisper_short
        },
    )

    private fun renameSpeakers(entry: RecordingEntry) {
        val uri = Uri.parse(entry.transcriptUri ?: return)
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { TranscriptFiles.read(activity, uri) }
            val speakers = text?.let(::speakersIn).orEmpty()
            if (text == null || speakers.isEmpty()) {
                Toast.makeText(activity, R.string.no_speakers, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val padding = (20 * activity.resources.displayMetrics.density).toInt()
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
            }
            val fields = speakers.map { speaker ->
                EditText(activity).apply { setText(speaker); setSelectAllOnFocus(true); box.addView(this) }
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.rename_speakers_title)
                .setView(box)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val names = speakers.zip(fields.map { it.text.toString().trim() })
                        .filter { (old, new) -> new.isNotEmpty() && new != old }.toMap()
                    if (names.isNotEmpty()) applyRename(entry, uri, text, names)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun applyRename(entry: RecordingEntry, uri: Uri, text: String, names: Map<String, String>) {
        activity.lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) { TranscriptFiles.write(activity, uri, renameSpeakers(text, names)) }
            if (!written) {
                Toast.makeText(activity, R.string.transcript_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            RecordingLog.update(activity, entry.name) {
                it.copy(
                    text = it.text?.let { t -> renameSpeakers(t, names) },
                    preview = names.entries.fold(it.preview) { preview, (old, new) -> preview?.replace("$old:", "$new:") },
                )
            }
        }
    }

    private fun confirmDelete(entry: RecordingEntry) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> delete(listOf(entry)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(toDelete: List<RecordingEntry>) {
        if (playing in toDelete.map { it.name }) stopPlayback()
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                toDelete.forEach { entry ->
                    listOfNotNull(entry.audioUri, entry.transcriptUri).forEach { TranscriptFiles.delete(activity, Uri.parse(it)) }
                }
            }
            RecordingLog.remove(activity, toDelete.map { it.name }.toSet())
            Toast.makeText(activity, R.string.deleted_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayback(entry: RecordingEntry) {
        val wasPlaying = playing == entry.name
        stopPlayback()
        if (wasPlaying) return render()
        val uri = entry.audioUri?.let(Uri::parse) ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(activity, uri)
                setOnCompletionListener { stopPlayback(); render() }
                prepare()
                start()
            }
            playing = entry.name
        } catch (e: Exception) {
            // Most likely a sync tool has already taken the file out of the folder.
            stopPlayback()
            Toast.makeText(activity, R.string.recording_gone, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playing = null
    }

    private fun shareTranscript(entry: RecordingEntry) {
        val uri = entry.transcriptUri?.let(Uri::parse)
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                uri?.let { TranscriptFiles.read(activity, it) }
            } ?: entry.text ?: entry.preview
            if (text == null) {
                Toast.makeText(activity, R.string.transcript_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(200_000)),
                    null,
                ),
            )
        }
    }

    private fun whenText(ms: Long): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        val day = if (DateUtils.isToday(ms)) {
            activity.getString(R.string.today)
        } else {
            DateUtils.formatDateTime(activity, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
        return "$day $time"
    }

    private fun clock(seconds: Int): String =
        if (seconds >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        }
}

/** Convenience for observing the log from an activity. */
fun AppCompatActivity.bindRecordingLog(list: RecordingsList) {
    RecordingLog.load(this)
    lifecycleScope.launch { RecordingLog.entries.collect { list.show(it) } }
}
