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
import com.pebblerecorder.app.recording.AutoStop
import com.pebblerecorder.app.recording.AutoStopReason
import com.pebblerecorder.app.recording.ImportedAudio
import com.pebblerecorder.app.recording.LevelMonitor
import com.pebblerecorder.app.recording.QuietMode
import com.pebblerecorder.app.recording.RecordingLocation
import com.pebblerecorder.app.recording.RecordingRetention
import com.pebblerecorder.app.recording.RecordingSettings
import com.pebblerecorder.app.recording.RecordingState
import com.pebblerecorder.app.recording.RecordingStatus
import com.pebblerecorder.app.recording.createRecordingTarget
import com.pebblerecorder.app.recording.recoverOrphanedRecordings
import com.pebblerecorder.app.settings.TranscriptionEngine
import com.pebblerecorder.app.settings.WEBHOOK_PORT
import com.pebblerecorder.app.settings.WatchPrefs
import com.pebblerecorder.app.settings.WebhookPrefs
import com.pebblerecorder.app.tile.RecordingTileService
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
private const val AUTO_STOP_NOTIFICATION_ID = 2
private const val AUTO_DELETE_NOTIFICATION_ID = 3
private const val ACTION_COMMAND = "com.pebblerecorder.app.action.COMMAND"
private const val EXTRA_COMMAND = "command"
private const val ACTION_RETRY_TRANSCRIPTION = "com.pebblerecorder.app.action.RETRY_TRANSCRIPTION"
private const val EXTRA_RECORDING_NAME = "recording_name"
private const val EXTRA_ENGINE = "engine"

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
            ContextCompat.startForegroundService(context, commandIntent(context, command))
        }

        /** Transcribes the delivered recording [name] again, after a failure. */
        fun retryTranscription(context: Context, name: String, engine: TranscriptionEngine? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PebbleListenerService::class.java)
                    .setAction(ACTION_RETRY_TRANSCRIPTION)
                    .putExtra(EXTRA_RECORDING_NAME, name)
                    .putExtra(EXTRA_ENGINE, engine?.name),
            )
        }

        /** For notification actions and widget buttons that run a COMMAND_* without opening the app. */
        fun commandPendingIntent(context: Context, command: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                command, // one request code per command, so each keeps its own PendingIntent
                commandIntent(context, command),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun commandIntent(context: Context, command: Int) =
            Intent(context, PebbleListenerService::class.java)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, command)
    }

    private lateinit var sender: DefaultPebbleSender
    private lateinit var audio: AudioRecorder
    private lateinit var location: RecordingLocation
    private lateinit var levelMonitor: LevelMonitor
    private lateinit var quietMode: QuietMode
    private lateinit var transcription: TranscriptionPipeline

    // Bookmark times (seconds into the running recording) and the limits it stops itself at.
    private val bookmarks = mutableListOf<Int>()
    private var autoStop = AutoStop(0, 0)

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
        transcription.onFinished = { succeeded -> activeWatch?.let { sendNotice(it, succeeded) } }
        createNotificationChannel()
        coroutineScope.launch(Dispatchers.IO) { recoverOrphanedRecordings(this@PebbleListenerService) { transcription.finish(it, null) } }
        startLevelStream()
        startRetentionLoop()
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
        if (intent?.action == ACTION_RETRY_TRANSCRIPTION) {
            val engine = intent.getStringExtra(EXTRA_ENGINE)
                ?.let { name -> TranscriptionEngine.entries.firstOrNull { it.name == name } }
            intent.getStringExtra(EXTRA_RECORDING_NAME)?.let { transcription.retry(it, engine) }
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
        WatchProtocol.COMMAND_BOOKMARK -> bookmark()
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

    /** Tells the watch (if it's showing our app) that a transcription finished, so it can buzz. */
    private fun sendNotice(watch: WatchIdentifier, succeeded: Boolean) {
        coroutineScope.launch {
            sender.sendDataToPebble(
                WatchProtocol.APP_UUID,
                mapOf(
                    WatchProtocol.KEY_NOTICE to PebbleDictionaryItem.Int32(
                        if (succeeded) WatchProtocol.NOTICE_TRANSCRIBED else WatchProtocol.NOTICE_TRANSCRIPTION_FAILED,
                    ),
                ),
                watches = listOf(watch),
            )
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
            bookmarks.clear()
            autoStop = AutoStop(
                RecordingSettings.maxMinutes(this) * 60,
                RecordingSettings.silenceMinutes(this) * 60,
            )
            Log.d(TAG, "Recording started: ${target.uri}")
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            target.delete(this)
            releaseRecording()
            WatchProtocol.STATUS_ERROR
        }
    }

    /** Marks the current point of the recording; the transcript lists the marks. */
    @Synchronized
    private fun bookmark(): Int {
        if (audio.isActive) bookmarks.add(audio.elapsedSeconds())
        return currentStatus()
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
                transcription.finish(finishedTarget, startLocation, bookmarks.toList())
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

    // ---- Automatic deletion ------------------------------------------------------------------

    /**
     * Applies the "delete after N days" rule when the service starts and again shortly after every
     * midnight, for as long as it runs (which is always - it's the persistent foreground service).
     * A phone asleep at midnight does it when it next wakes the service.
     */
    private fun startRetentionLoop() {
        coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val removed = RecordingRetention.sweep(this@PebbleListenerService)
                if (removed > 0) notifyAutoDeleted(removed)
                delay(RecordingRetention.millisUntilNextMidnight(System.currentTimeMillis()) + 30_000L)
            }
        }
    }

    private fun notifyAutoDeleted(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.auto_deleted_title))
            .setContentText(resources.getQuantityString(R.plurals.auto_deleted_text, count, count))
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(AUTO_DELETE_NOTIFICATION_ID, notification)
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
            // Samples are taken every 100 ms but everything else - progress, auto-stop, the message
            // to the watch - still happens once per batch of four (400 ms), on the batch's peak.
            val batch = IntArray(WatchLevels.SAMPLES_PER_SEND)
            var count = 0
            var heard = false // whether any sample in the batch came from a mic
            while (isActive) {
                delay(WatchLevels.SAMPLE_MS)
                val watch = activeWatch
                val recording = audio.isActive && !audio.isPaused
                if (watch == null || recording) stopMonitor(demote = watch == null)
                // The recorder's peak is read every sample while recording - the watch's level meter
                // and the silence auto-stop both need it, and each read resets it.
                val amplitude = when {
                    recording -> audio.maxAmplitude()
                    watch != null -> monitorPeak()
                    else -> null
                }
                batch[count++] = amplitude ?: 0
                if (amplitude != null) heard = true
                if (count < batch.size) continue
                count = 0
                val batchHeard = heard
                heard = false

                if (audio.isActive) {
                    RecordingStatus.updateProgress(audio.elapsedSeconds(), audio.sizeBytes())
                }
                val peak = batch.max()
                if (recording) checkAutoStop(peak)
                else if (audio.isActive) autoStop.reset() // paused
                if (watch == null || !batchHeard) continue
                // LEVEL (the batch peak) keeps watch apps from before LEVELS working.
                sender.sendDataToPebble(
                    WatchProtocol.APP_UUID,
                    mapOf(
                        WatchProtocol.KEY_LEVEL to PebbleDictionaryItem.Int32(WatchLevels.toLevel(peak)),
                        WatchProtocol.KEY_LEVELS to PebbleDictionaryItem.Bytes(WatchLevels.toBytes(batch)),
                    ),
                    watches = listOfNotNull(watch),
                )
            }
        }
    }

    /** Stops the recording if it has hit its length limit or gone quiet for too long (see [AutoStop]). */
    private fun checkAutoStop(peak: Int) {
        val reason = autoStop.onTick(audio.elapsedSeconds(), peak) ?: return
        coroutineScope.launch(Dispatchers.IO) {
            val status = stopRecording()
            activeWatch?.let { replyToWatch(status, it) }
            notifyAutoStopped(reason)
        }
    }

    private fun notifyAutoStopped(reason: AutoStopReason) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.auto_stopped_title))
            .setContentText(
                getString(
                    if (reason == AutoStopReason.SILENCE) R.string.auto_stopped_silence else R.string.auto_stopped_max,
                ),
            )
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(AUTO_STOP_NOTIFICATION_ID, notification)
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
        refreshSurfaces()
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
        refreshSurfaces()
    }

    private fun promoteToPaused() {
        startForegroundTyped(
            buildNotification(R.string.paused_notification_title, active = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        RecordingStatus.update(RecordingState.PAUSED)
        refreshSurfaces()
    }

    /** Pushes the new state to the home-screen widget and the Quick Settings tile. */
    private fun refreshSurfaces() {
        RecordingStatusWidgetProvider.refreshAll(this)
        RecordingTileService.requestRefresh(this)
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
            .apply {
                if (!active) return@apply
                // Pause/Resume and Stop right in the shade (and on the lock screen).
                val paused = titleRes == R.string.paused_notification_title
                addAction(
                    0,
                    getString(if (paused) R.string.resume_button else R.string.pause_button),
                    commandPendingIntent(
                        this@PebbleListenerService,
                        if (paused) WatchProtocol.COMMAND_RESUME else WatchProtocol.COMMAND_PAUSE,
                    ),
                )
                addAction(
                    0,
                    getString(R.string.stop_button),
                    commandPendingIntent(this@PebbleListenerService, WatchProtocol.COMMAND_STOP),
                )
            }
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
        // close() unbinds from the Pebble app and throws if that binding was never made.
        runCatching { sender.close() }
        super.onDestroy()
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
