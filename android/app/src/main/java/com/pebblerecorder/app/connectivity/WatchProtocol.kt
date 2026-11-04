package com.pebblerecorder.app.connectivity

import java.util.UUID

/**
 * Mirrors the watch side of the protocol in watch/src/c/watch.c and the message key IDs
 * that pebble-tool auto-assigns from watch/package.json's `messageKeys` list (COMMAND=10000,
 * STATUS=10001, ELAPSED=10002, LEVEL=10003, TOUCH_ENABLED=10004, NOTICE=10005 as of this writing — re-check watch/build/js/message_keys.json if that list changes).
 */
object WatchProtocol {
    val APP_UUID: UUID = UUID.fromString("252148a0-e241-4bc2-a9d5-07004df158e9")

    const val KEY_COMMAND: UInt = 10000u
    const val KEY_STATUS: UInt = 10001u

    /** Seconds the current recording has run, pauses excluded; sent with RECORDING/PAUSED. */
    const val KEY_ELAPSED: UInt = 10002u

    /** Live mic level 0..100 (dB scale), streamed to the watch while recording. */
    const val KEY_LEVEL: UInt = 10003u
    const val KEY_TOUCH_ENABLED: UInt = 10004u

    /** A one-off message for the watch to flash: one of the NOTICE_* values. */
    const val KEY_NOTICE: UInt = 10005u

    const val COMMAND_STOP = 0
    const val COMMAND_START = 1
    const val COMMAND_PAUSE = 2
    const val COMMAND_RESUME = 3

    /** Drops a bookmark at the current point of the running recording. */
    const val COMMAND_BOOKMARK = 4

    const val NOTICE_TRANSCRIBED = 1
    const val NOTICE_TRANSCRIPTION_FAILED = 2

    const val STATUS_IDLE = 0
    const val STATUS_RECORDING = 1
    const val STATUS_ERROR = 2
    const val STATUS_PAUSED = 3
}
