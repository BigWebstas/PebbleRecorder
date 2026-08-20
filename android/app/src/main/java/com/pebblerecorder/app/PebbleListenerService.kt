package com.pebblerecorder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
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
 * On COMMAND_START it writes AAC/M4A audio via MediaRecorder into a file created in the
 * user-chosen SAF folder (RecordingFolderPrefs). It self-starts as a foreground service for the
 * duration of the recording so capture survives the companion app unbinding this service (which
 * happens whenever the watchapp itself is closed on the watch, per onAppClosed below).
 */
class PebbleListenerService : BasePebbleListenerService() {

    private lateinit var sender: DefaultPebbleSender
    private var recorder: MediaRecorder? = null
    private var outputFile: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        sender = DefaultPebbleSender(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
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

        // Mark this already-bound service as "started" too, so it outlives the companion app
        // unbinding it (e.g. when the watchapp is closed) for as long as recording is in progress.
        ContextCompat.startForegroundService(this, Intent(this, PebbleListenerService::class.java))

        return try {
            val pfd = contentResolver.openFileDescriptor(file.uri, "w")
                ?: error("openFileDescriptor returned null for ${file.uri}")
            outputFile = pfd

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

    private fun stopRecording(): Int {
        if (recorder == null) {
            return WatchProtocol.STATUS_IDLE
        }
        return try {
            recorder?.stop()
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

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID opened on $watch")
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID closed on $watch")
    }

    override fun onDestroy() {
        if (recorder != null) {
            stopRecording()
        }
        sender.close()
        super.onDestroy()
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
