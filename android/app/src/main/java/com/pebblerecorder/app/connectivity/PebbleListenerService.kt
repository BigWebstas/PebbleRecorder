package com.pebblerecorder.app.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pebblerecorder.app.R
import com.pebblerecorder.app.recording.RecordingSettings
import com.pebblerecorder.app.recording.RecordingState
import com.pebblerecorder.app.recording.RecordingStatus
import com.pebblerecorder.app.recording.RecordingTarget
import com.pebblerecorder.app.recording.createRecordingTarget
import com.pebblerecorder.app.recording.recoverOrphanedRecordings
import com.pebblerecorder.app.settings.GeminiPrefs
import com.pebblerecorder.app.settings.RecordingFolderPrefs
import com.pebblerecorder.app.settings.WEBHOOK_PORT
import com.pebblerecorder.app.settings.WatchPrefs
import com.pebblerecorder.app.settings.WebhookPrefs
import com.pebblerecorder.app.transcription.GeminiTranscriber
import com.pebblerecorder.app.ui.MainActivity
import com.pebblerecorder.app.ui.widget.RecordingStatusWidgetProvider
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "PebbleListenerService"
private const val NOTIFICATION_CHANNEL_ID = "recording"
private const val NOTIFICATION_ID = 1
private const val LEVEL_INTERVAL_MS = 400L
private const val MONITOR_SAMPLE_RATE = 16_000
private const val TRANSCRIPTION_CHANNEL_ID = "transcription"
// Failure notifications are keyed off the recording name (offset from this base) so a repeated
// failure for the same file updates in place while different files each get their own.
private const val TRANSCRIPTION_FAILURE_NOTIFICATION_BASE_ID = 100

/**
 * Bound by the Pebble/Core companion app while the watch trigger app is open on a paired watch.
 *
 * Also self-started by MainActivity as soon as the app is opened, and kept running persistently
 * as a low-priority "armed" (specialUse type) foreground service from then on - this is required
 * because Android forbids starting a *new* microphone-type foreground service while the app has
 * no visible UI, which is exactly the state we're in when a watch COMMAND arrives via the AIDL
 * bind. Adding the microphone type to an *already-foregrounded* service is allowed though, so as
 * long as MainActivity has been opened at least once since the process started, COMMAND_START can
 * promote this already-armed service to also hold the microphone type for the duration of the
 * recording, then demote back to armed-only on COMMAND_STOP.
 *
 * On COMMAND_START it writes AAC/M4A audio via MediaRecorder into a file created in the
 * user-chosen SAF folder (RecordingFolderPrefs).
 */
class PebbleListenerService : BasePebbleListenerService() {

    private lateinit var sender: DefaultPebbleSender
    private var recorder: MediaRecorder? = null
    private var outputFile: ParcelFileDescriptor? = null
    private var recordingTarget: RecordingTarget? = null
    private var recordingLocation: Location? = null
    private var pendingLocationListener: LocationListener? = null
    private var isPaused = false

    // Recording clock for the watch's timer (SystemClock.elapsedRealtime, ms). Pauses are
    // subtracted so the watch can re-sync after it was closed or disconnected mid-recording.
    private var recordingStartedAt = 0L
    private var pausedAt = 0L
    private var pausedTotalMs = 0L

    // The watch currently showing our app (null when closed), and the loop streaming it the mic level.
    @Volatile private var activeWatch: WatchIdentifier? = null
    private var levelJob: Job? = null
    private var monitor: AudioRecord? = null

    private val webhook = WebhookServer(token = { WebhookPrefs.token(this) }, handle = ::handleWebhook)
    // Held as a field: SharedPreferences keeps listeners in a weak map.
    private val webhookPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncWebhookServer() }
    private var monitorMicKey: String? = null

    /**
     * Finished recordings not yet copied to the recording folder (still transcribing, or copying).
     * They stay in private storage until then, so a folder-watcher sees the audio and its
     * transcript appear together. Tracked here so [onDestroy] can still deliver them if the
     * service is torn down mid-transcription.
     */
    private val pendingTranscriptionTargets = CopyOnWriteArraySet<RecordingTarget>()

    override fun onCreate() {
        super.onCreate()
        sender = DefaultPebbleSender(this)
        createNotificationChannel()
        coroutineScope.launch(Dispatchers.IO) { recoverOrphanedRecordings(this@PebbleListenerService) }
        startLevelStream()
        syncWebhookServer()
        WebhookPrefs.registerListener(this, webhookPrefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToArmed()
        return START_STICKY
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WatchProtocol.APP_UUID) {
            return ReceiveResult.Nack
        }

        val command = data[WatchProtocol.KEY_COMMAND]?.let(::intValueOf)
        if (command == null) {
            Log.w(TAG, "Message from $watch missing COMMAND key: $data")
            return ReceiveResult.Nack
        }

        Log.d(TAG, "Received command=$command from watch=$watch")
        activeWatch = watch

        val replyStatus = when (command) {
            WatchProtocol.COMMAND_START -> startRecording()
            WatchProtocol.COMMAND_STOP -> stopRecording()
            WatchProtocol.COMMAND_PAUSE -> pauseRecording()
            WatchProtocol.COMMAND_RESUME -> resumeRecording()
            else -> WatchProtocol.STATUS_ERROR
        }
        replyToWatch(replyStatus, watch)

        return ReceiveResult.Ack
    }

    private fun replyToWatch(status: Int, watch: WatchIdentifier) {
        coroutineScope.launch {
            val message = mutableMapOf<UInt, PebbleDictionaryItem>(
                WatchProtocol.KEY_STATUS to PebbleDictionaryItem.Int32(status),
                WatchProtocol.KEY_TOUCH_ENABLED to
                    PebbleDictionaryItem.Int32(if (WatchPrefs.isTouchEnabled(this@PebbleListenerService)) 1 else 0),
            )
            if (status == WatchProtocol.STATUS_RECORDING || status == WatchProtocol.STATUS_PAUSED) {
                message[WatchProtocol.KEY_ELAPSED] = PebbleDictionaryItem.Int32(elapsedSeconds())
            }
            val result = sender.sendDataToPebble(
                WatchProtocol.APP_UUID,
                message,
                watches = listOf(watch),
            )
            Log.d(TAG, "Sent status=$status to watch=$watch, result=$result")
        }
    }

    @Synchronized
    private fun startRecording(): Int {
        if (recorder != null) {
            Log.d(TAG, "Start requested but already recording")
            return WatchProtocol.STATUS_RECORDING
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted, cannot start recording")
            return WatchProtocol.STATUS_ERROR
        }

        // Read once so changing a setting mid-recording doesn't affect the one in progress.
        val format = RecordingSettings.format(this)
        val finalName = "recording-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-rec.${format.extension}"
        val target = createRecordingTarget(this, finalName)
        if (target == null) {
            Log.w(TAG, "Failed to create $finalName - no writable folder configured and no Downloads fallback")
            return WatchProtocol.STATUS_ERROR
        }

        return try {
            stopMonitor(demote = false) // MediaRecorder needs the mic to itself on older Android
            promoteToRecording()

            val pfd = ParcelFileDescriptor.open(
                target.file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            outputFile = pfd
            recordingTarget = target
            // Captured now (where the recording started), not at stop time, so it heads into
            // the transcript header if Gemini transcription is on. Best-effort - no location
            // permission or fix available just means the header omits it.
            recordingLocation = if (GeminiPrefs.isEnabled(this) && GeminiPrefs.isLocationEnabled(this)) {
                // getLastKnownLocation alone is a stale cache - often null if no other app has
                // requested a fix recently, which is why the tag would sometimes go missing.
                // Also kick off a fresh fix in the background to upgrade recordingLocation before
                // the recording stops, falling back to whatever the cache had in the meantime.
                requestFreshLocation()
                getBestLastKnownLocation()
            } else {
                null
            }

            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                format.configure(this, RecordingSettings.quality(this@PebbleListenerService))
                RecordingSettings.preferredMic(this@PebbleListenerService)?.let { setPreferredDevice(it) }
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            recordingStartedAt = SystemClock.elapsedRealtime()
            pausedTotalMs = 0
            pausedAt = 0
            Log.d(TAG, "Recording started: ${target.uri}")
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            target.delete(this)
            releaseRecorder()
            WatchProtocol.STATUS_ERROR
        }
    }

    @Synchronized
    private fun pauseRecording(): Int {
        val activeRecorder = recorder ?: return WatchProtocol.STATUS_ERROR
        if (isPaused) {
            return WatchProtocol.STATUS_PAUSED
        }
        return try {
            activeRecorder.pause()
            isPaused = true
            pausedAt = SystemClock.elapsedRealtime()
            promoteToPaused()
            WatchProtocol.STATUS_PAUSED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
            WatchProtocol.STATUS_ERROR
        }
    }

    @Synchronized
    private fun resumeRecording(): Int {
        val activeRecorder = recorder
        if (activeRecorder == null || !isPaused) {
            return WatchProtocol.STATUS_ERROR
        }
        return try {
            activeRecorder.resume()
            isPaused = false
            pausedTotalMs += SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0
            promoteToRecording()
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
            WatchProtocol.STATUS_ERROR
        }
    }

    @Synchronized
    private fun stopRecording(): Int {
        if (recorder == null) {
            return WatchProtocol.STATUS_IDLE
        }
        val finishedTarget = recordingTarget
        val location = recordingLocation
        return try {
            recorder?.stop()
            if (finishedTarget != null) {
                finishRecording(finishedTarget, location)
            }
            WatchProtocol.STATUS_IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording cleanly", e)
            WatchProtocol.STATUS_ERROR
        } finally {
            releaseRecorder()
        }
    }

    /**
     * Also publishes recording progress (time, file size) to the app's status screen.
     *
     * Streams the mic level to the watch app a few times a second, for as long as it's open -
     * recording or not, so the user can check the phone hears them before pressing start. While
     * recording, MediaRecorder already has the mic and its getMaxAmplitude (peak since the last
     * call) is free; otherwise a small [AudioRecord] monitor is opened just while a watch is
     * watching. Runs for the life of the service; idle ticks are cheap.
     */
    private fun startLevelStream() {
        levelJob = coroutineScope.launch {
            while (isActive) {
                delay(LEVEL_INTERVAL_MS)
                val watch = activeWatch
                if (recorder != null) {
                    RecordingStatus.updateProgress(elapsedSeconds(), recordingTarget?.file?.length() ?: 0L)
                }
                val recording = recorder != null && !isPaused
                if (watch == null || recording) stopMonitor(demote = watch == null)
                val amplitude = when {
                    watch == null -> null
                    recording -> runCatching { recorder?.maxAmplitude }.getOrNull()
                    else -> monitorPeak()
                } ?: continue
                // Map -60..0 dBFS onto 0..100 - quiet rooms read near zero, speech mid-to-high.
                val db = 20 * log10(amplitude.coerceAtLeast(1) / 32768.0)
                val level = ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)
                sender.sendDataToPebble(
                    WatchProtocol.APP_UUID,
                    mapOf(WatchProtocol.KEY_LEVEL to PebbleDictionaryItem.Int32(level)),
                    watches = listOfNotNull(watch),
                )
            }
        }
    }

    /** Peak sample (0..32767) heard since the last call, opening the monitor first if needed. */
    @Synchronized
    @SuppressLint("MissingPermission")
    private fun monitorPeak(): Int? {
        // The user picked a different mic in settings: reopen on it.
        if (monitor != null && monitorMicKey != RecordingSettings.micKey(this)) stopMonitor(demote = false)
        if (monitor == null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            try {
                // Android only hands the mic to a foreground service of the microphone type.
                if (recorder == null) promoteToMonitoring()
                val buffer = AudioRecord(
                    MediaRecorder.AudioSource.MIC, MONITOR_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, MONITOR_SAMPLE_RATE, // 1 s of 16-bit samples
                )
                RecordingSettings.preferredMic(this)?.let { buffer.preferredDevice = it }
                if (buffer.state != AudioRecord.STATE_INITIALIZED) {
                    buffer.release()
                    return null
                }
                buffer.startRecording()
                monitor = buffer
                monitorMicKey = RecordingSettings.micKey(this)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open level monitor", e)
                return null
            }
        }
        val samples = ShortArray(MONITOR_SAMPLE_RATE / 2)
        val read = monitor?.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING) ?: return null
        if (read <= 0) return 0
        var peak = 0
        for (i in 0 until read) peak = maxOf(peak, abs(samples[i].toInt()))
        return peak
    }

    /** Releases the level monitor; [demote] drops the mic foreground type again if nothing else needs it. */
    @Synchronized
    private fun stopMonitor(demote: Boolean) {
        val open = monitor ?: return
        monitor = null
        runCatching { open.stop() }
        open.release()
        if (demote && recorder == null) promoteToArmed()
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MediaRecorder", e)
        }
        recorder = null

        try {
            outputFile?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close output file descriptor", e)
        }
        outputFile = null
        recordingTarget = null
        recordingLocation = null
        getSystemService(LocationManager::class.java)?.let { clearPendingLocationRequest(it) }
        isPaused = false

        promoteToArmed()
    }

    /**
     * If Gemini is on, transcribes [target] (retrying transient failures - see
     * [GeminiTranscriber.transcribe]) and writes the transcript sibling; then finalizes the
     * recording. The audio is only copied into the recording folder once this completes, so
     * folder-watching apps see the audio and its transcript together.
     *
     * The recording is finalized even when transcription ultimately fails - a missing transcript
     * shouldn't cost the user their audio.
     */
    private fun finishRecording(target: RecordingTarget, location: Location?) {
        pendingTranscriptionTargets.add(target)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (!GeminiPrefs.isEnabled(this@PebbleListenerService)) return@launch
                GeminiTranscriber.transcribe(this@PebbleListenerService, target.uri)
                    .onSuccess { transcript ->
                        val locationTag = location?.let {
                            runCatching { reverseGeocodeTag(it) }.getOrNull()
                        }
                        writeTranscript(target, transcript, location, locationTag)
                        Log.d(TAG, "Transcribed ${target.displayName}")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Gave up transcribing ${target.displayName} after retries", error)
                        notifyTranscriptionFailed(target.displayName, error)
                    }
            } finally {
                target.finalize(this@PebbleListenerService)
                pendingTranscriptionTargets.remove(target)
            }
        }
    }

    private fun writeTranscript(target: RecordingTarget, transcript: String, location: Location?, locationTag: String?) {
        target.transcriptFile.writeText(buildTranscriptMarkdown(transcript, location, locationTag))
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
        val addressLine = getFirstAddress(Geocoder(this, Locale.getDefault()), location)
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

    /**
     * Best-effort, synchronous last-known location. This is a passive cache - it's often null or
     * stale if no app has requested a fix recently, which is why [requestFreshLocation] also runs
     * alongside it to upgrade [recordingLocation] in the background before the recording stops.
     * Returns null if location permission isn't granted or no provider has a cached fix yet.
     */
    private fun getBestLastKnownLocation(): Location? {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return null
        }
        val locationManager = getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * Actively requests one fresh location fix in the background and, if it arrives before the
     * recording stops, overwrites [recordingLocation] with it. Doesn't block recording start -
     * [getBestLastKnownLocation]'s cached value (possibly null) is used immediately as a fallback.
     * Gives up and stops listening after 15s so a GPS-less environment doesn't leak the listener.
     */
    private fun requestFreshLocation() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return
        }
        val locationManager = getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return

        clearPendingLocationRequest(locationManager)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                recordingLocation = location
                clearPendingLocationRequest(locationManager)
            }
        }
        pendingLocationListener = listener
        try {
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request a fresh location fix", e)
            pendingLocationListener = null
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ clearPendingLocationRequest(locationManager) }, 15_000)
    }

    private fun clearPendingLocationRequest(locationManager: LocationManager) {
        pendingLocationListener?.let {
            runCatching { locationManager.removeUpdates(it) }
            pendingLocationListener = null
        }
    }

    private fun promoteToArmed() {
        startForegroundTyped(
            buildNotification(R.string.armed_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        RecordingStatus.update(RecordingState.IDLE)
        RecordingStatusWidgetProvider.refreshAll(this)
    }

    /** Mic access for the idle level meter: no recording state, same notification as armed. */
    private fun promoteToMonitoring() {
        startForegroundTyped(
            buildNotification(R.string.armed_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun promoteToRecording() {
        startForegroundTyped(
            buildNotification(R.string.recording_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        RecordingStatus.update(RecordingState.RECORDING)
        RecordingStatusWidgetProvider.refreshAll(this)
    }

    private fun promoteToPaused() {
        startForegroundTyped(
            buildNotification(R.string.paused_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        RecordingStatus.update(RecordingState.PAUSED)
        RecordingStatusWidgetProvider.refreshAll(this)
    }

    private fun startForegroundTyped(notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.recording_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        // IMPORTANCE_LOW: shows in the shade but never makes a sound or a heads-up - the failure
        // notice is informational, not urgent.
        manager.createNotificationChannel(
            NotificationChannel(
                TRANSCRIPTION_CHANNEL_ID,
                getString(R.string.transcription_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** Posts a silent notification with the error after transcription has exhausted its retries. */
    private fun notifyTranscriptionFailed(recordingName: String, error: Throwable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
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
            this,
            notificationId,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TRANSCRIPTION_ERROR, fullMessage)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, TRANSCRIPTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.transcription_failed_notification_title))
            .setContentText(recordingName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullMessage))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun buildNotification(titleRes: Int): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(titleRes))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID opened on $watch")
        if (watchappUUID == WatchProtocol.APP_UUID) {
            // Push current status as soon as the watchapp opens, so the watch can flag a stuck/
            // unresponsive phone side immediately instead of waiting for the user to press a
            // button and get a silent failure.
            activeWatch = watch
            replyToWatch(currentStatus(), watch)
        }
    }

    private fun elapsedSeconds(): Int {
        if (recorder == null) return 0
        val now = if (isPaused) pausedAt else SystemClock.elapsedRealtime()
        return ((now - recordingStartedAt - pausedTotalMs) / 1000).toInt().coerceAtLeast(0)
    }

    /**
     * Local webhook (see [WebhookServer]): /toggle starts a recording or stops the running one
     * (paused counts as running), so a single trigger - e.g. Pebble Index's webhook - can both
     * start and stop. The watch is told about the new state like after one of its own commands.
     */
    private fun handleWebhook(method: String, path: String): WebhookServer.Response {
        val status = when (path) {
            "/status" -> currentStatus()
            "/start" -> startRecording()
            "/stop" -> stopRecording()
            "/toggle" -> if (recorder == null) startRecording() else stopRecording()
            else -> return WebhookServer.Response(404, """{"error":"not found"}""")
        }
        if (path != "/status") activeWatch?.let { replyToWatch(status, it) }
        val name = when (status) {
            WatchProtocol.STATUS_IDLE -> "idle"
            WatchProtocol.STATUS_RECORDING -> "recording"
            WatchProtocol.STATUS_PAUSED -> "paused"
            else -> "error"
        }
        val body = """{"status":"$name","elapsed":${elapsedSeconds()}}"""
        return WebhookServer.Response(if (status == WatchProtocol.STATUS_ERROR) 500 else 200, body)
    }

    /** Runs the webhook server exactly while the setting is on; called at startup and on every change. */
    private fun syncWebhookServer() {
        if (WebhookPrefs.isEnabled(this)) webhook.start(WEBHOOK_PORT) else webhook.stop()
    }

    private fun currentStatus(): Int = when {
        recorder == null -> WatchProtocol.STATUS_IDLE
        isPaused -> WatchProtocol.STATUS_PAUSED
        else -> WatchProtocol.STATUS_RECORDING
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID closed on $watch")
        if (activeWatch == watch) activeWatch = null
    }

    override fun onDestroy() {
        WebhookPrefs.unregisterListener(this, webhookPrefsListener)
        webhook.stop()
        levelJob?.cancel()
        stopMonitor(demote = false)
        try {
            recorder?.stop()
            recordingTarget?.finalize(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop recording during teardown", e)
        }
        // Deliver any recordings still waiting on a transcript - they won't get one now, but the
        // audio shouldn't be left stuck in private storage.
        pendingTranscriptionTargets.forEach { runCatching { it.finalize(this) } }
        pendingTranscriptionTargets.clear()
        recorder?.release()
        recorder = null
        outputFile?.close()
        outputFile = null
        getSystemService(LocationManager::class.java)?.let { clearPendingLocationRequest(it) }
        sender.close()
        super.onDestroy()
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
