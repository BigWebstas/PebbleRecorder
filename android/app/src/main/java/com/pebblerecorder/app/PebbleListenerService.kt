package com.pebblerecorder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    private var recordingFile: DocumentFile? = null
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

        val folder = RecordingFolderPrefs.get(this)?.let { DocumentFile.fromTreeUri(this, it) }
        if (folder == null || !folder.canWrite()) {
            Log.w(TAG, "No writable recording folder configured")
            return WatchProtocol.STATUS_ERROR
        }

        val fileName = "recording-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.m4a"
        val file = folder.createFile("audio/mp4", fileName)
        if (file == null) {
            Log.w(TAG, "Failed to create $fileName in recording folder")
            return WatchProtocol.STATUS_ERROR
        }

        promoteToRecording()

        return try {
            val pfd = contentResolver.openFileDescriptor(file.uri, "w")
                ?: error("openFileDescriptor returned null for ${file.uri}")
            outputFile = pfd
            recordingFile = file

            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            Log.d(TAG, "Recording started: ${file.uri}")
            WatchProtocol.STATUS_RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder()
            WatchProtocol.STATUS_ERROR
        }
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
        val finishedFile = recordingFile
        return try {
            recorder?.stop()
            if (finishedFile != null) {
                transcribeIfEnabled(finishedFile)
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
        recordingFile = null
        isPaused = false

        promoteToArmed()
    }

    private fun transcribeIfEnabled(audioFile: DocumentFile) {
        if (!GeminiPrefs.isEnabled(this)) {
            return
        }
        coroutineScope.launch {
            GeminiTranscriber.transcribe(this@PebbleListenerService, audioFile)
                .onSuccess { transcript ->
                    writeTranscript(audioFile, transcript)
                    Log.d(TAG, "Transcribed ${audioFile.name}")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to transcribe ${audioFile.name}", error)
                }
        }
    }

    private fun writeTranscript(audioFile: DocumentFile, transcript: String) {
        val parent = audioFile.parentFile
        val transcriptName = audioFile.name?.substringBeforeLast('.')?.plus(".md")
        if (parent == null || transcriptName == null) {
            Log.w(TAG, "Could not resolve transcript path for ${audioFile.name}")
            return
        }
        val transcriptFile = parent.createFile("text/markdown", transcriptName)
        if (transcriptFile == null) {
            Log.w(TAG, "Failed to create $transcriptName")
            return
        }
        contentResolver.openOutputStream(transcriptFile.uri)?.use {
            it.write(transcript.toByteArray(Charsets.UTF_8))
        }
    }

    private fun promoteToArmed() {
        startForegroundTyped(
            buildNotification(R.string.armed_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun promoteToRecording() {
        startForegroundTyped(
            buildNotification(R.string.recording_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun promoteToPaused() {
        startForegroundTyped(
            buildNotification(R.string.paused_notification_title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop recording during teardown", e)
        }
        recorder?.release()
        recorder = null
        outputFile?.close()
        outputFile = null
        sender.close()
        super.onDestroy()
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
