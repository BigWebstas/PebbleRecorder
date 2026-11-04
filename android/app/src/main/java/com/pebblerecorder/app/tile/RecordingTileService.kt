package com.pebblerecorder.app.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pebblerecorder.app.R
import com.pebblerecorder.app.connectivity.PebbleListenerService
import com.pebblerecorder.app.connectivity.WatchProtocol
import com.pebblerecorder.app.recording.RecordingState
import com.pebblerecorder.app.recording.RecordingStatus

/** Quick Settings tile: tap to start a recording, tap again to stop it. */
class RecordingTileService : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        val recording = RecordingStatus.state.value != RecordingState.IDLE
        PebbleListenerService.sendCommand(
            this,
            if (recording) WatchProtocol.COMMAND_STOP else WatchProtocol.COMMAND_START,
        )
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = RecordingStatus.state.value
        tile.state = if (state == RecordingState.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = getString(
            when (state) {
                RecordingState.IDLE -> R.string.tile_idle
                RecordingState.RECORDING -> R.string.widget_status_recording
                RecordingState.PAUSED -> R.string.widget_status_paused
            },
        )
        tile.updateTile()
    }

    companion object {
        /** Asks the system to re-render the tile (if it's showing) after the recording state changed. */
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, RecordingTileService::class.java))
        }
    }
}
