package com.pebblerecorder.app.connectivity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pebblerecorder.app.R
import com.pebblerecorder.app.recording.AudioRecorder
import com.pebblerecorder.app.recording.ImportedAudio
import com.pebblerecorder.app.recording.LevelMonitor
import com.pebblerecorder.app.recording.QuietMode
import com.pebblerecorder.app.recording.RecordingLocation
import com.pebblerecorder.app.recording.RecordingSettings
import com.pebblerecorder.app.recording.RecordingState
import com.pebblerecorder.app.recording.RecordingStatus
import com.pebblerecorder.app.recording.createRecordingTarget
import com.pebblerecorder.app.recording.recoverOrphanedRecordings
import com.pebblerecorder.app.settings.WEBHOOK_PORT
import com.pebblerecorder.app.settings.WatchPrefs
import com.pebblerecorder.app.settings.WebhookPrefs
import com.pebblerecorder.app.transcription.TranscriptionPipeline
import com.pebblerecorder.app.ui.MainActivity
import com.pebblerecorder.app.widget.RecordingStatusWidgetProvider
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "PebbleListenerService"
private const val NOTIFICATION_CHANNEL_ID = "recording"
// A channel's importance can't be raised after creation, so the "recording now" notice gets its
// own, default-importance channel - it shows above the silent notifications instead of among them.
private const val ACTIVE_CHANNEL_ID = "recording_active"
private const val NOTIFICATION_ID = 1
private const val LEVEL_INTERVAL_MS = 400L
private const val ACTION_COMMAND = "com.pebblerecorder.app.action.COMMAND"
private const val EXTRA_COMMAND = "command"

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
 * On COMMAND_START it records through [AudioRecorder]; when the recording stops, [TranscriptionPipeline]
 * transcribes it (if enabled) and delivers it to the user-chosen folder (RecordingFolderPrefs).
 * This class only owns the glue: commands in (watch, webhook, app buttons), the foreground
 * notification and service type, and the mic level stream out to the watch.
 */
class PebbleListenerService : BasePebbleListenerService() {

    companion object {
        /** Runs a [WatchProtocol] COMMAND_* from the app itself, the same way the watch would. */
        fun sendCommand(context: Context, command: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PebbleListenerService::class.java)
                    .setAction(ACTION_COMMAND)
                    .putExtra(EXTRA_COMMAND, command),
            )
        }
    }

    private lateinit var sender: DefaultPebbleSender
    private lateinit var audio: AudioRecorder
    private lateinit var location: RecordingLocation
    private lateinit var levelMonitor: LevelMonitor
    private lateinit var quietMode: QuietMode
    private lateinit var transcription: TranscriptionPipeline

    // The watch currently showing our app (null when closed), and the loop streaming it the mic level.
    @Volatile private var activeWatch: WatchIdentifier? = null
    private var levelJob: Job? = null

    private val webhook = WebhookServer(
        token = { WebhookPrefs.token(this) },
        handle = ::handleWebhook,
        upload = ::handleUpload,
    )
    // Held as a field: SharedPreferences keeps listeners in a weak map.
    private val webhookPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncWebhookServer() }

    override fun onCreate() {
        super.onCreate()
        sender = DefaultPebbleSender(this)
        audio = AudioRecorder(this)
        location = RecordingLocation(this)
        levelMonitor = LevelMonitor(this)
        quietMode = QuietMode(this)
        quietMode.exit() // a previous process may have died mid-recording with Do Not Disturb on
        transcription = TranscriptionPipeline(this, coroutineScope)
        createNotificationChannel()
        coroutineScope.launch(Dispatchers.IO) { recoverOrphanedRecordings(this@PebbleListenerService) }
        startLevelStream()
        syncWebhookServer()
        WebhookPrefs.registerListener(this, webhookPrefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshForeground()
        if (intent?.action == ACTION_COMMAND) {
            val command = intent.getIntExtra(EXTRA_COMMAND, -1)
            coroutineScope.launch(Dispatchers.IO) {
                val status = runCommand(command)
                activeWatch?.let { replyToWatch(status, it) }
            }
        }
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

        replyToWatch(runCommand(command), watch)

        return ReceiveResult.Ack
    }

    /** Runs a COMMAND_* and returns the STATUS_* to report back. */
    private fun runCommand(command: Int): Int = when (command) {
        WatchProtocol.COMMAND_START -> startRecording()
        WatchProtocol.COMMAND_STOP -> stopRecording()
        WatchProtocol.COMMAND_PAUSE -> pauseRecording()
        WatchProtocol.COMMAND_RESUME -> resumeRecording()
        else -> WatchProtocol.STATUS_ERROR
    }

    private fun replyToWatch(status: Int, watch: WatchIdentifier) {
        coroutineScope.launch {
            val message = mutableMapOf<UInt, PebbleDictionaryItem>(
                WatchProtocol.KEY_STATUS to PebbleDictionaryItem.Int32(status),
                WatchProtocol.KEY_TOUCH_ENABLED to
                    PebbleDictionaryItem.Int32(if (WatchPrefs.isTouchEnabled(this@PebbleListenerService)) 1 else 0),
            )
            if (status == WatchProtocol.STATUS_RECORDING || status == WatchProtocol.STATUS_PAUSED) {
                message[WatchProtocol.KEY_ELAPSED] = PebbleDictionaryItem.Int32(audio.elapsedSeconds())
            }
            val result = sender.sendDataToPebble(
                WatchProtocol.APP_UUID,
                message,
                watches = listOf(watch),
            )
            Log.d(TAG, "Sent status=$status to watch=$watch, result=$result")
        }
    }

    // ---- Recording control -------------------------------------------------------------------

    @Synchronized
    private fun startRecording(): Int {
        if (audio.isActive) {
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
            quietMode.enter()
            location.begin()
            audio.start(target, format)
            Log.d(TAG, "Recording started: ${target.uri}")
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            target.delete(this)
            releaseRecording()
            WatchProtocol.STATUS_ERROR
        }
    }

    @Synchronized
    private fun pauseRecording(): Int {
        if (!audio.isActive) return WatchProtocol.STATUS_ERROR
        if (audio.isPaused) return WatchProtocol.STATUS_PAUSED
        return try {
            audio.pause()
            promoteToPaused()
            WatchProtocol.STATUS_PAUSED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
            WatchProtocol.STATUS_ERROR
        }
    }

    @Synchronized
    private fun resumeRecording(): Int {
        if (!audio.isActive || !audio.isPaused) return WatchProtocol.STATUS_ERROR
        return try {
            audio.resume()
            promoteToRecording()
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
            WatchProtocol.STATUS_ERROR
        }
    }

    @Synchronized
    private fun stopRecording(): Int {
        if (!audio.isActive) return WatchProtocol.STATUS_IDLE
        val finishedTarget = audio.target
        val startLocation = location.current
        return try {
            audio.stop()
            if (finishedTarget != null) {
                transcription.finish(finishedTarget, startLocation)
            }
            WatchProtocol.STATUS_IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording cleanly", e)
            WatchProtocol.STATUS_ERROR
        } finally {
            releaseRecording()
        }
    }

    private fun releaseRecording() {
        audio.release()
        location.cancel()
        quietMode.exit()
        promoteToArmed()
    }

    private fun currentStatus(): Int = when {
        !audio.isActive -> WatchProtocol.STATUS_IDLE
        audio.isPaused -> WatchProtocol.STATUS_PAUSED
        else -> WatchProtocol.STATUS_RECORDING
    }

    // ---- Mic level stream --------------------------------------------------------------------

    /**
     * Also publishes recording progress (time, file size) to the app's status screen.
     *
     * Streams the mic level to the watch app a few times a second, for as long as it's open -
     * recording or not, so the user can check the phone hears them before pressing start. While
     * recording, MediaRecorder already has the mic and its getMaxAmplitude (peak since the last
     * call) is free; otherwise a small [LevelMonitor] is opened just while a watch is watching.
     * Runs for the life of the service; idle ticks are cheap.
     */
    private fun startLevelStream() {
        levelJob = coroutineScope.launch {
            while (isActive) {
                delay(LEVEL_INTERVAL_MS)
                val watch = activeWatch
                if (audio.isActive) {
                    RecordingStatus.updateProgress(audio.elapsedSeconds(), audio.sizeBytes())
                }
                val recording = audio.isActive && !audio.isPaused
                if (watch == null || recording) stopMonitor(demote = watch == null)
                val amplitude = when {
                    watch == null -> null
                    recording -> audio.maxAmplitude()
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

    /** Under the service lock so the mic's foreground type can't change mid-way through [startRecording]. */
    @Synchronized
    private fun monitorPeak(): Int? = levelMonitor.peak { if (!audio.isActive) promoteToMonitoring() }

    /** Releases the level monitor; [demote] drops the mic foreground type again if nothing else needs it. */
    @Synchronized
    private fun stopMonitor(demote: Boolean) {
        if (levelMonitor.stop() && demote && !audio.isActive) promoteToArmed()
    }

    // ---- Foreground service and notifications ------------------------------------------------

    /** Re-asserts the foreground notification for whatever state we're actually in. */
    private fun refreshForeground() = when {
        !audio.isActive -> promoteToArmed()
        audio.isPaused -> promoteToPaused()
        else -> promoteToRecording()
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
            buildNotification(R.string.recording_notification_title, active = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        RecordingStatus.update(RecordingState.RECORDING)
        RecordingStatusWidgetProvider.refreshAll(this)
    }

    private fun promoteToPaused() {
        startForegroundTyped(
            buildNotification(R.string.paused_notification_title, active = true),
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
        manager.createNotificationChannel(
            NotificationChannel(
                ACTIVE_CHANNEL_ID,
                getString(R.string.recording_active_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** [active]: a recording is running (or paused), so use the louder channel; otherwise the silent "armed" one. */
    private fun buildNotification(titleRes: Int, active: Boolean = false): Notification =
        NotificationCompat.Builder(this, if (active) ACTIVE_CHANNEL_ID else NOTIFICATION_CHANNEL_ID)
            .setOnlyAlertOnce(true) // alert when recording starts, not again on pause/resume
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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

    // ---- Watch app lifecycle and webhook -----------------------------------------------------

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

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID closed on $watch")
        if (activeWatch == watch) activeWatch = null
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
            "/toggle" -> if (!audio.isActive) startRecording() else stopRecording()
            else -> return WebhookServer.Response(404, """{"error":"not found"}""")
        }
        if (path != "/status") activeWatch?.let { replyToWatch(status, it) }
        val name = when (status) {
            WatchProtocol.STATUS_IDLE -> "idle"
            WatchProtocol.STATUS_RECORDING -> "recording"
            WatchProtocol.STATUS_PAUSED -> "paused"
            else -> "error"
        }
        val body = """{"status":"$name","elapsed":${audio.elapsedSeconds()}}"""
        return WebhookServer.Response(if (status == WatchProtocol.STATUS_ERROR) 500 else 200, body)
    }

    /**
     * A recording sent along with a webhook request (e.g. made on Pebble Index): written to the
     * same private storage as our own recordings and handed to the same pipeline, so it gets
     * transcribed (if enabled) and delivered to the recording folder exactly like one recorded here.
     */
    private fun handleUpload(mime: String, filename: String?, audio: InputStream): WebhookServer.Response {
        val head = ByteArray(16)
        var headSize = 0
        while (headSize < head.size) {
            val n = audio.read(head, headSize, head.size - headSize)
            if (n < 0) break
            headSize += n
        }
        if (headSize == 0) return WebhookServer.Response(400, """{"error":"empty recording"}""")
        val extension = ImportedAudio.extensionOf(head.copyOf(headSize), filename, mime)
            ?: return WebhookServer.Response(415, """{"error":"unsupported audio type"}""")

        val finalName = "recording-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-rec.$extension"
        val target = createRecordingTarget(this, finalName)
            ?: return WebhookServer.Response(500, """{"error":"no folder to save to"}""")
        return try {
            target.file.outputStream().use { out ->
                out.write(head, 0, headSize)
                audio.copyTo(out)
            }
            // No location: it's the phone's position now, not where the recording was made.
            transcription.finish(target, null)
            Log.d(TAG, "Received uploaded recording $finalName (${target.file.length()} bytes)")
            WebhookServer.Response(200, """{"status":"received","name":"$finalName"}""")
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed", e)
            target.delete(this)
            WebhookServer.Response(500, """{"error":"upload failed"}""")
        }
    }

    /** Runs the webhook server exactly while the setting is on; called at startup and on every change. */
    private fun syncWebhookServer() {
        if (WebhookPrefs.isEnabled(this)) webhook.start(WEBHOOK_PORT) else webhook.stop()
    }

    override fun onDestroy() {
        WebhookPrefs.unregisterListener(this, webhookPrefsListener)
        webhook.stop()
        levelJob?.cancel()
        stopMonitor(demote = false)
        try {
            audio.stop()
            audio.target?.finalize(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop recording during teardown", e)
        }
        transcription.deliverPending()
        audio.release()
        location.cancel()
        quietMode.exit()
        sender.close()
        super.onDestroy()
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
