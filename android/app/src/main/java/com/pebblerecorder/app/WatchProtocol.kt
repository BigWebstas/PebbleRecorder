package com.pebblerecorder.app

import java.util.UUID

/**
 * Mirrors the watch side of the protocol in watch/src/c/watch.c and the message key IDs
 * that pebble-tool auto-assigns from watch/package.json's `messageKeys` list (COMMAND=10000,
 * STATUS=10001 as of this writing — re-check watch/build/js/message_keys.json if that list changes).
 */
object WatchProtocol {
    val APP_UUID: UUID = UUID.fromString("252148a0-e241-4bc2-a9d5-07004df158e9")

    const val KEY_COMMAND: UInt = 10000u
    const val KEY_STATUS: UInt = 10001u

    const val COMMAND_STOP = 0
    const val COMMAND_START = 1

    const val STATUS_IDLE = 0
    const val STATUS_RECORDING = 1
    const val STATUS_ERROR = 2
}
