package com.pebblerecorder.app.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the on-device transcriber is working on right now, for the main screen. */
object TranscriptionStatus {
    /** [percent] is null while the audio is still being decoded and whisper hasn't reported yet. */
    data class Active(val name: String, val startedAtMs: Long, val percent: Int?)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active

    fun begin(name: String) {
        _active.value = Active(name, System.currentTimeMillis(), null)
    }

    fun progress(percent: Int) {
        _active.value = _active.value?.copy(percent = percent)
    }

    fun end() {
        _active.value = null
    }
}
