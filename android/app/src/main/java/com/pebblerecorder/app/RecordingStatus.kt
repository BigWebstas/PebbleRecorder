package com.pebblerecorder.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RecordingState { IDLE, RECORDING, PAUSED }

/**
 * In-memory, process-wide record of PebbleListenerService's current recording state, so
 * MainActivity can show it live without polling. Not persisted - a fresh process always starts
 * at IDLE, which PebbleListenerService corrects as soon as it arms/reports in.
 */
object RecordingStatus {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state

    fun update(newState: RecordingState) {
        _state.value = newState
    }
}
