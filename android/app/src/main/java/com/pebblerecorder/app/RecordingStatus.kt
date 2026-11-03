package com.pebblerecorder.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RecordingState { IDLE, RECORDING, PAUSED }

/** How far the current recording has got: seconds recorded (pauses excluded) and bytes on disk. */
data class RecordingProgress(val elapsedSeconds: Int = 0, val sizeBytes: Long = 0)

/**
 * In-memory, process-wide record of PebbleListenerService's current recording state, so
 * MainActivity can show it live without polling. Not persisted - a fresh process always starts
 * at IDLE, which PebbleListenerService corrects as soon as it arms/reports in.
 */
object RecordingStatus {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state

    private val _progress = MutableStateFlow(RecordingProgress())
    val progress: StateFlow<RecordingProgress> = _progress

    fun update(newState: RecordingState) {
        _state.value = newState
    }

    fun updateProgress(elapsedSeconds: Int, sizeBytes: Long) {
        _progress.value = RecordingProgress(elapsedSeconds, sizeBytes)
    }
}
