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
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pebblerecorder.app.R
import com.pebblerecorder.app.recording.RecordingTarget
import com.pebblerecorder.app.settings.GeminiPrefs
import com.pebblerecorder.app.ui.MainActivity
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

/**
 * What happens to a recording once it has stopped: optionally transcribed with Gemini, then
 * delivered to the recording folder. Recordings stay in private storage until then, so a
 * folder-watcher sees the audio and its transcript appear together.
 */
class TranscriptionPipeline(private val context: Context, private val scope: CoroutineScope) {

    /**
     * Finished recordings not yet copied to the recording folder (still transcribing, or copying).
     * Tracked so [deliverPending] can still deliver them if the service is torn down mid-transcription.
     */
    private val pending = CopyOnWriteArraySet<RecordingTarget>()

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
     * If Gemini is on, transcribes [target] (retrying transient failures - see
     * [GeminiTranscriber.transcribe]) and writes the transcript sibling; then finalizes the
     * recording. The recording is finalized even when transcription ultimately fails - a missing
     * transcript shouldn't cost the user their audio.
     */
    fun finish(target: RecordingTarget, location: Location?) {
        pending.add(target)
        scope.launch(Dispatchers.IO) {
            try {
                if (!GeminiPrefs.isEnabled(context)) return@launch
                GeminiTranscriber.transcribe(context, target.uri)
                    .onSuccess { transcript ->
                        val locationTag = location?.let {
                            runCatching { reverseGeocodeTag(it) }.getOrNull()
                        }
                        target.transcriptFile.writeText(buildTranscriptMarkdown(transcript, location, locationTag))
                        Log.d(TAG, "Transcribed ${target.displayName}")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Gave up transcribing ${target.displayName} after retries", error)
                        notifyFailed(target.displayName, error)
                    }
            } finally {
                target.finalize(context)
                pending.remove(target)
            }
        }
    }

    /** Delivers recordings still waiting on a transcript - they won't get one now, but the audio shouldn't stay stuck in private storage. */
    fun deliverPending() {
        pending.forEach { runCatching { it.finalize(context) } }
        pending.clear()
    }

    private fun buildTranscriptMarkdown(transcript: String, location: Location?, locationTag: String?): String =
        buildString {
            append("---\n")
            append("recorded: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                append("location: $lat, $lon\n")
                append("maps: https://www.google.com/maps?q=$lat,$lon\n")
            }
            append("---\n\n")
            if (locationTag != null) {
                append(locationTag)
                append("\n\n")
            }
            append(transcript)
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

    /** Posts a silent notification with the error after transcription has exhausted its retries. */
    private fun notifyFailed(recordingName: String, error: Throwable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
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
