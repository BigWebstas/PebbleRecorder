package com.pebblerecorder.app.transcription

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pebblerecorder.app.R
import com.pebblerecorder.app.recording.MAX_TEXT
import com.pebblerecorder.app.recording.RecordingEntry
import com.pebblerecorder.app.recording.RecordingLog
import com.pebblerecorder.app.recording.RecordingTarget
import com.pebblerecorder.app.recording.TranscriptState
import com.pebblerecorder.app.recording.createRecordingTarget
import com.pebblerecorder.app.recording.transcriptNameFor
import com.pebblerecorder.app.settings.GeminiPrefs
import com.pebblerecorder.app.settings.TranscriptionEngine
import com.pebblerecorder.app.settings.TranscriptionPrefs
import com.pebblerecorder.app.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "TranscriptionPipeline"
private const val TRANSCRIPTION_CHANNEL_ID = "transcription"
// Failure notifications are keyed off the recording name (offset from this base) so a repeated
// failure for the same file updates in place while different files each get their own.
private const val TRANSCRIPTION_FAILURE_NOTIFICATION_BASE_ID = 100
private const val TRANSCRIPT_READY_NOTIFICATION_BASE_ID = 100_000
private const val PREVIEW_LENGTH = 160
private const val MAX_SHARE_CHARS = 200_000

/**
 * What happens to a recording once it has stopped: optionally transcribed (Gemini, OpenAI or on-device Whisper), then
 * delivered to the recording folder. Recordings stay in private storage until then, so a
 * folder-watcher sees the audio and its transcript appear together.
 */
class TranscriptionPipeline(private val context: Context, private val scope: CoroutineScope) {

    /**
     * Finished recordings not yet copied to the recording folder (still transcribing, or copying).
     * Tracked so [deliverPending] can still deliver them if the service is torn down mid-transcription.
     */
    private val pending = CopyOnWriteArraySet<RecordingTarget>()

    /** Told (true/false) whenever a transcription finishes or fails, e.g. so the watch can buzz. */
    var onFinished: ((Boolean) -> Unit)? = null

    init {
        // IMPORTANCE_LOW: shows in the shade but never makes a sound or a heads-up - the failure
        // notice is informational, not urgent.
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                TRANSCRIPTION_CHANNEL_ID,
                context.getString(R.string.transcription_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /**
     * If transcription is on, transcribes [target] with the chosen engine (cloud engines retry
     * transient failures - see [withRetries]) and writes the transcript sibling; then finalizes the
     * recording. The recording is finalized even when transcription ultimately fails - a missing
     * transcript shouldn't cost the user their audio. Progress is tracked in [RecordingLog] for the
     * main screen's list.
     */
    fun finish(target: RecordingTarget, location: Location?, bookmarks: List<Int> = emptyList()) {
        pending.add(target)
        val name = target.displayName
        RecordingLog.load(context)
        // A recording picked up again after an interruption keeps the time it was made.
        val recordedAt = RecordingLog.entries.value.firstOrNull { it.name == name }?.recordedAtMs
            ?: System.currentTimeMillis()
        val enabled = GeminiPrefs.isEnabled(context)
        RecordingLog.add(
            context,
            RecordingEntry(
                name = name,
                recordedAtMs = recordedAt,
                durationSec = durationSeconds(target.file),
                sizeBytes = target.file.length(),
                transcript = if (enabled) TranscriptState.PENDING else TranscriptState.NONE,
            ),
        )
        scope.launch(Dispatchers.IO) {
            try {
                if (enabled) {
                    transcribe(target.uri, target.file, bookmarks = bookmarks)
                        .onSuccess { transcript ->
                            val locationTag = location?.let {
                                runCatching { reverseGeocodeTag(it) }.getOrNull()
                            }
                            val markdown = transcriptMarkdown(
                                transcript, recordedAt, location?.latitude, location?.longitude,
                                locationTag, summaryFor(transcript), bookmarks,
                            )
                            target.transcriptFile.writeText(markdown)
                            Log.d(TAG, "Transcribed $name")
                            markTranscribed(name, transcript, markdown)
                        }
                        .onFailure { markFailed(name, it) }
                }
            } finally {
                val totalBytes = target.file.length() +
                    (if (target.transcriptFile.exists()) target.transcriptFile.length() else 0L)
                val delivery = target.finalize(context)
                RecordingLog.update(context, name) {
                    it.copy(
                        sizeBytes = totalBytes.takeIf { b -> b > 0L } ?: it.sizeBytes,
                        audioUri = delivery?.audioUri?.toString(),
                        transcriptUri = delivery?.transcriptUri?.toString(),
                    )
                }
                pending.remove(target)
            }
        }
    }

    /**
     * Transcribes the already-delivered recording [name] again - after a failure, or with a
     * different [engine] - copying its audio back out of the recording folder. An existing
     * transcript is overwritten in place. Fails if a sync tool has since taken the audio away.
     */
    fun retry(name: String, engine: TranscriptionEngine? = null) {
        val entry = RecordingLog.entries.value.firstOrNull { it.name == name } ?: return
        val audioUri = entry.audioUri?.let(Uri::parse)
        if (audioUri == null) {
            markFailed(name, IllegalStateException("The audio file isn't in the recording folder"))
            return
        }
        RecordingLog.update(context, name) { it.copy(transcript = TranscriptState.PENDING, error = null) }
        scope.launch(Dispatchers.IO) {
            val audio = File(context.cacheDir, name)
            val transcriptFile = File(context.cacheDir, transcriptNameFor(name))
            try {
                val copied = runCatching {
                    context.contentResolver.openInputStream(audioUri)!!.use { input ->
                        audio.outputStream().use { input.copyTo(it) }
                    }
                }
                if (copied.isFailure) {
                    markFailed(name, IllegalStateException("The audio file isn't in the recording folder any more"))
                    return@launch
                }
                transcribe(Uri.fromFile(audio), audio, engine ?: TranscriptionPrefs.engine(context))
                    .onSuccess { transcript ->
                        val markdown = transcriptMarkdown(
                            transcript, entry.recordedAtMs, null, null, null, summaryFor(transcript), emptyList(),
                        )
                        val existing = entry.transcriptUri?.let(Uri::parse)
                        val written = if (existing != null && TranscriptFiles.write(context, existing, markdown)) {
                            existing
                        } else {
                            transcriptFile.writeText(markdown)
                            createRecordingTarget(context, name)
                                ?.copyToDestination(context, transcriptFile, transcriptFile.name, "text/markdown")
                        }
                        RecordingLog.update(context, name) { it.copy(transcriptUri = written?.toString()) }
                        markTranscribed(name, transcript, markdown)
                    }
                    .onFailure { markFailed(name, it) }
            } finally {
                audio.delete()
                transcriptFile.delete()
            }
        }
    }

    /** The summary to put above the transcript, or null if summaries are off or the provider failed. */
    private suspend fun summaryFor(transcript: String): String? =
        if (!TranscriptionPrefs.summaryEnabled(context)) null
        else Summarizer.summarize(context, transcript).onFailure { Log.w(TAG, "Summary failed", it) }.getOrNull()

    private suspend fun transcribe(
        uri: Uri,
        file: File,
        engine: TranscriptionEngine = TranscriptionPrefs.engine(context),
        bookmarks: List<Int> = emptyList(),
    ): Result<String> =
        when (engine) {
            TranscriptionEngine.GEMINI -> GeminiTranscriber.transcribe(context, uri, bookmarks)
            TranscriptionEngine.OPENAI -> OpenAiTranscriber.transcribe(context, uri)
            TranscriptionEngine.WHISPER -> WhisperTranscriber.transcribe(context, file)
        }

    private fun markTranscribed(name: String, transcript: String, markdown: String = transcript) {
        RecordingLog.update(context, name) {
            it.copy(
                transcript = TranscriptState.DONE,
                preview = previewOf(transcript),
                text = markdown.take(MAX_TEXT),
                error = null,
            )
        }
        notifyReady(name, transcript)
        onFinished?.invoke(true)
    }

    private fun markFailed(name: String, error: Throwable) {
        Log.e(TAG, "Gave up transcribing $name", error)
        RecordingLog.update(context, name) {
            it.copy(transcript = TranscriptState.FAILED, error = error.message ?: error.javaClass.simpleName)
        }
        notifyFailed(name, error)
        onFinished?.invoke(false)
    }

    private fun durationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).div(1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    /** The first line or so of the transcript, minus speaker markup, for the list. */
    private fun previewOf(transcript: String): String =
        transcript.replace("**", "").replace(Regex("\\s+"), " ").trim().take(PREVIEW_LENGTH)

    /** Delivers recordings still waiting on a transcript - they won't get one now, but the audio shouldn't stay stuck in private storage. */
    fun deliverPending() {
        pending.forEach { runCatching { it.finalize(context) } }
        pending.clear()
    }

    /** Reverse-geocodes into a single hashtag, e.g. "#1600AmphitheatrePkwyMountainView". */
    private suspend fun reverseGeocodeTag(location: Location): String? {
        if (!Geocoder.isPresent()) {
            return null
        }
        val addressLine = getFirstAddress(Geocoder(context, Locale.getDefault()), location)
            ?.getAddressLine(0)
            ?: return null
        val tag = addressLine.filter { it.isLetterOrDigit() }
        return tag.takeIf { it.isNotEmpty() }?.let { "#$it" }
    }

    private suspend fun getFirstAddress(geocoder: Geocoder, location: Location): Address? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    continuation.resume(addresses.firstOrNull())
                }
            }
        }
        @Suppress("DEPRECATION")
        return withContext(Dispatchers.IO) {
            runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }
                .getOrNull()
        }
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    /** Posts a silent "transcript ready" notification whose Share action sends the text on. */
    private fun notifyReady(recordingName: String, transcript: String) {
        if (!canNotify()) return
        val notificationId = TRANSCRIPT_READY_NOTIFICATION_BASE_ID + (recordingName.hashCode() and 0xFFFF)
        val share = PendingIntent.getActivity(
            context,
            notificationId,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    // Intent extras travel through binder, which has a ~1 MB cap.
                    .putExtra(Intent.EXTRA_TEXT, transcript.take(MAX_SHARE_CHARS)),
                null,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, TRANSCRIPTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.transcript_ready_notification_title))
            .setContentText(previewOf(transcript))
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewOf(transcript)))
            .addAction(0, context.getString(R.string.share_button), share)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    /** Posts a silent notification with the error after transcription has exhausted its retries. */
    private fun notifyFailed(recordingName: String, error: Throwable) {
        if (!canNotify()) return
        val detail = error.message ?: error.javaClass.simpleName
        val fullMessage = "$recordingName\n\n$detail"
        val notificationId = TRANSCRIPTION_FAILURE_NOTIFICATION_BASE_ID + (recordingName.hashCode() and 0xFFFF)
        // Tapping the notification opens MainActivity, which shows this text in a dialog. The
        // per-notification request code + FLAG_UPDATE_CURRENT keep each recording's error distinct.
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TRANSCRIPTION_ERROR, fullMessage)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, TRANSCRIPTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.transcription_failed_notification_title))
            .setContentText(recordingName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullMessage))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }
}
