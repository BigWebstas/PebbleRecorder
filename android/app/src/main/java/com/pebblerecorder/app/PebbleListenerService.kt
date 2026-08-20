package com.pebblerecorder.app

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "PebbleListenerService"

/**
 * Bound by the Pebble/Core companion app while the watch trigger app is open on a paired watch.
 *
 * This skeleton doesn't record audio yet — it just logs the incoming COMMAND and echoes back a
 * stub STATUS reply so the watch's Idle/Recording/Error state machine can be exercised end to end.
 * Real MediaRecorder wiring replaces the stub reply in a later milestone.
 */
class PebbleListenerService : BasePebbleListenerService() {

    private lateinit var sender: DefaultPebbleSender

    override fun onCreate() {
        super.onCreate()
        sender = DefaultPebbleSender(this)
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
            WatchProtocol.COMMAND_START -> WatchProtocol.STATUS_RECORDING
            WatchProtocol.COMMAND_STOP -> WatchProtocol.STATUS_IDLE
            else -> WatchProtocol.STATUS_ERROR
        }
        coroutineScope.launch {
            val result = sender.sendDataToPebble(
                WatchProtocol.APP_UUID,
                mapOf(WatchProtocol.KEY_STATUS to PebbleDictionaryItem.Int32(replyStatus)),
                watches = listOf(watch),
            )
            Log.d(TAG, "Sent status=$replyStatus to watch=$watch, result=$result")
        }

        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID opened on $watch")
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "Watch app $watchappUUID closed on $watch")
    }

    override fun onDestroy() {
        sender.close()
        super.onDestroy()
    }
}

private fun intValueOf(item: PebbleDictionaryItem): Int? = when (item) {
    is PebbleDictionaryItem.Int32 -> item.value
    is PebbleDictionaryItem.UInt32 -> item.value.toInt()
    else -> null
}
