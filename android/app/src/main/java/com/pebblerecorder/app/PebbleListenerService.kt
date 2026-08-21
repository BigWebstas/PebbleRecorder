package com.pebblerecorder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "PebbleListenerService"
private const val NOTIFICATION_CHANNEL_ID = "recording"
private const val NOTIFICATION_ID = 1

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

    override fun onCreate() {
        super.onCreate()
        sender = DefaultPebbleSender(this)
        createNotificationChannel()
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
            val result = sender.sendDataToPebble(
                WatchProtocol.APP_UUID,
                mapOf(WatchProtocol.KEY_STATUS to PebbleDictionaryItem.Int32(status)),
                watches = listOf(watch),
            )
            Log.d(TAG, "Sent status=$status to watch=$watch, result=$result")
        }
    }

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

        val finalName = "recording-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-rec.m4a"
        val target = createRecordingTarget(finalName)
        if (target == null) {
            Log.w(TAG, "Failed to create $finalName - no writable folder configured and no Downloads fallback")
            return WatchProtocol.STATUS_ERROR
        }

        return try {
            promoteToRecording()

            val pfd = contentResolver.openFileDescriptor(target.uri, "w")
                ?: error("openFileDescriptor returned null for ${target.uri}")
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
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            Log.d(TAG, "Recording started: ${target.uri}")
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            target.delete(this)
            releaseRecorder()
            WatchProtocol.STATUS_ERROR
        }
    }

    /**
     * Prefers the user-chosen SAF folder (RecordingFolderPrefs); falls back to the public
     * Downloads/PebbleRecorder folder via MediaStore if none is set (or it's no longer writable -
     * e.g. the user revoked the permission), so recording works before anyone's opened the app's
     * settings. MediaStore.Downloads only exists on API 29+; below that, a missing folder is just
     * an error, same as before this fallback existed.
     */
    private fun createRecordingTarget(finalName: String): RecordingTarget? {
        val folderUri = RecordingFolderPrefs.get(this)
        if (folderUri != null) {
            val folder = DocumentFile.fromTreeUri(this, folderUri)
            if (folder != null && folder.canWrite()) {
                // Record into a hidden dotfile so other apps watching the folder (a Syncthing
                // sync, a file browser, Joplin's own folder scan, ...) don't pick up a
                // partial/corrupt file mid-recording. There's no real cross-app file lock
                // available over SAF - most apps (Syncthing included) don't honor advisory locks
                // - so a naming convention every filesystem tool already understands is the
                // reliable way to hide a WIP file. Revealed (renamed) once MediaRecorder finishes.
                val file = folder.createFile("audio/mp4", ".$finalName")
                if (file != null) {
                    return RecordingTarget.Saf(file, finalName)
                }
                Log.w(TAG, "Failed to create .$finalName in configured recording folder")
            } else {
                Log.w(TAG, "Configured recording folder is not writable, falling back to Downloads")
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        return createDownloadsRecordingTarget(this, finalName)
    }

    private fun pauseRecording(): Int {
        val activeRecorder = recorder ?: return WatchProtocol.STATUS_ERROR
        if (isPaused) {
            return WatchProtocol.STATUS_PAUSED
        }
        return try {
            activeRecorder.pause()
            isPaused = true
            promoteToPaused()
            WatchProtocol.STATUS_PAUSED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
            WatchProtocol.STATUS_ERROR
        }
    }

    private fun resumeRecording(): Int {
        val activeRecorder = recorder
        if (activeRecorder == null || !isPaused) {
            return WatchProtocol.STATUS_ERROR
        }
        return try {
            activeRecorder.resume()
            isPaused = false
            promoteToRecording()
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
            WatchProtocol.STATUS_ERROR
        }
    }

    private fun stopRecording(): Int {
        if (recorder == null) {
            return WatchProtocol.STATUS_IDLE
        }
        val finishedTarget = recordingTarget
        val location = recordingLocation
        return try {
            recorder?.stop()
            if (finishedTarget != null) {
                finishedTarget.finalize(this)
                transcribeIfEnabled(finishedTarget, location)
            }
            WatchProtocol.STATUS_IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording cleanly", e)
            WatchProtocol.STATUS_ERROR
        } finally {
            releaseRecorder()
        }
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

    private fun transcribeIfEnabled(target: RecordingTarget, location: Location?) {
        if (!GeminiPrefs.isEnabled(this)) {
            return
        }
        coroutineScope.launch {
            GeminiTranscriber.transcribe(this@PebbleListenerService, target.uri)
                .onSuccess { transcript ->
                    val locationTag = location?.let {
                        runCatching { reverseGeocodeTag(it) }.getOrNull()
                    }
                    writeTranscript(target, transcript, location, locationTag)
                    Log.d(TAG, "Transcribed ${target.displayName}")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to transcribe ${target.displayName}", error)
                }
        }
    }

    private fun writeTranscript(target: RecordingTarget, transcript: String, location: Location?, locationTag: String?) {
        val transcriptName = target.displayName.substringBeforeLast('.').removeSuffix("-rec").plus("-txt.md")
        val transcriptUri = target.createSibling(this, transcriptName, "text/markdown")
        if (transcriptUri == null) {
            Log.w(TAG, "Failed to create $transcriptName")
            return
        }
        contentResolver.openOutputStream(transcriptUri)?.use {
            it.write(buildTranscriptMarkdown(transcript, location, locationTag).toByteArray(Charsets.UTF_8))
        }
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
    }

    private fun promoteToRecording() {
        startForegroundTyped(
            buildNotification(R.string.recording_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        RecordingStatus.update(RecordingState.RECORDING)
    }

    private fun promoteToPaused() {
        startForegroundTyped(
            buildNotification(R.string.paused_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        RecordingStatus.update(RecordingState.PAUSED)
    }

    private fun startForegroundTyped(notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID closed on $watch")
    }

    override fun onDestroy() {
        try {
            recorder?.stop()
            recordingTarget?.finalize(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop recording during teardown", e)
        }
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
